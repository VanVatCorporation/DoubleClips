package com.vanvatcorporation.doubleclips.activities.main;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vanvatcorporation.doubleclips.ProjectZip;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.MainActivity;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.RetrofitClient;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;
import com.vanvatcorporation.drivekit.DriveClient;
import com.vanvatcorporation.drivekit.DriveException;
import com.vanvatcorporation.drivekit.Models;
import com.vanvatcorporation.drivekit.OpenEditzClient;
import com.vanvatcorporation.drivekit.ResumableUpload;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Storage tab: your OpenEditz folder in Van Vat Drive.
 * Back up projects as ZIPs, restore them, upload and download files. Everything here stays inside OpenEditz
 * (the full Drive: sharing, trash, other folders, lives in the Van Vat Drive app).
 * It signs in with the account login the app already has: the JWT is the "authToken" cookie in the cookie jar.
 */
public class StorageAreaScreen extends BaseAreaScreen {
    /** Set by MainActivity: system file picker for "Upload". */
    public ActivityResultLauncher<Intent> filePickerLauncher;

    private interface IoTask<T> { T run() throws Exception; }
    private interface Done<T> { void run(T value); }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<Models.Node> trail = new ArrayList<>();   // folders entered below OpenEditz
    private final ArrayList<Models.Node> items = new ArrayList<>();

    private DriveClient drive;
    private OpenEditzClient openEditz;
    private Models.Me me;
    private boolean loadedOnce;

    private SwipeRefreshLayout swipe;
    private RecyclerView recycler;
    private TextView quotaText, pathText, emptyText;
    private ProgressBar quotaBar;
    private Adapter adapter;

    public StorageAreaScreen(Context context) { super(context); }
    public StorageAreaScreen(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        swipe = findViewById(R.id.swipeRefreshLayout);
        recycler = findViewById(R.id.storageRecyclerView);
        quotaText = findViewById(R.id.storageQuotaText);
        quotaBar = findViewById(R.id.storageQuotaBar);
        pathText = findViewById(R.id.storagePathText);
        emptyText = findViewById(R.id.storageEmptyText);

        adapter = new Adapter();
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        recycler.setAdapter(adapter);
        swipe.setOnRefreshListener(this::reload);

        findViewById(R.id.storageBackupButton).setOnClickListener(v -> chooseProjectToBackUp());
        findViewById(R.id.storageUploadButton).setOnClickListener(v -> pickFiles());
        findViewById(R.id.storageFolderButton).setOnClickListener(v -> askText("New folder", "Folder name", "", "Create", this::createFolder));
        pathText.setOnClickListener(v -> { if (!trail.isEmpty()) { trail.remove(trail.size() - 1); reload(); } });
        renderHeader();
    }

    /** MainActivity calls this when the tab becomes visible. */
    public void onShown() {
        if (!loadedOnce) reload();
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private MainActivity activity() {
        Context c = getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof MainActivity) return (MainActivity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private boolean hasSession() {
        return RetrofitClient.getInstance(getContext()).getAuthToken() != null;
    }

    private synchronized OpenEditzClient openEditz() {
        if (openEditz == null) {
            drive = new DriveClient(DriveClient.DEFAULT_BASE, new DriveClient.Auth() {
                @Override public String token() { return RetrofitClient.getInstance(getContext()).getAuthToken(); }
                @Override public void onRefreshed(String token) { /* the cookie jar stays the source of truth */ }
                @Override public void onUnauthorized() { ui.post(() -> toast("Your session expired. Please sign in again from the Profile tab.")); }
            });
            openEditz = new OpenEditzClient(drive);
        }
        return openEditz;
    }

    private void toast(String message) { LoggingManager.LogToToast(getContext(), message); }

    private <T> void async(IoTask<T> task, Done<T> done) {
        io.execute(() -> {
            try {
                T result = task.run();
                ui.post(() -> { if (done != null) done.run(result); });
            } catch (Exception e) {
                ui.post(() -> {
                    swipe.setRefreshing(false);
                    toast(e instanceof DriveException ? e.getMessage() : "Storage: " + e.getMessage());
                });
            }
        });
    }

    private Models.Node currentFolder() { return trail.isEmpty() ? null : trail.get(trail.size() - 1); }

    private static String bytes(long n) {
        if (n < 1024) return n + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = n;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    private static String sanitize(String name) {
        String s = name == null ? "project" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "project" : s;
    }

    // ── load ────────────────────────────────────────────────────────────────

    private void reload() {
        if (!hasSession()) {
            swipe.setRefreshing(false);
            items.clear();
            adapter.notifyDataSetChanged();
            showEmpty("Sign in from the Profile tab to back up your projects to OpenEditz.");
            return;
        }
        swipe.setRefreshing(true);
        final Models.Node folder = currentFolder();
        async(() -> {
            List<Models.Node> list = new ArrayList<>(openEditz().list(folder));
            Collections.sort(list, (a, b) -> a.isFolder != b.isFolder ? (a.isFolder ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
            Models.Me fresh = drive.me();
            return new Object[]{list, fresh};
        }, r -> {
            loadedOnce = true;
            swipe.setRefreshing(false);
            items.clear();
            @SuppressWarnings("unchecked") List<Models.Node> list = (List<Models.Node>) r[0];
            items.addAll(list);
            me = (Models.Me) r[1];
            adapter.notifyDataSetChanged();
            renderHeader();
            if (items.isEmpty()) showEmpty("Nothing here yet.\nBack up a project or upload files.");
            else emptyText.setVisibility(View.GONE);
        });
    }

    private void showEmpty(String text) {
        emptyText.setText(text);
        emptyText.setVisibility(View.VISIBLE);
    }

    private void renderHeader() {
        StringBuilder path = new StringBuilder("OpenEditz");
        for (Models.Node n : trail) path.append("  ›  ").append(n.name);
        pathText.setText(trail.isEmpty() ? path : "‹  " + path + "   (tap to go back)");

        if (me == null) {
            quotaText.setText("");
            quotaBar.setProgress(0);
            return;
        }
        String text = bytes(me.usedBytes) + " of " + bytes(me.quotaBytes) + " used";
        if (me.usage != null) {
            text += "\nToday: ↑ " + bytes(me.usage.uploadedBytes) + " / " + bytes(me.usage.uploadLimit)
                    + "  ·  ↓ " + bytes(me.usage.downloadedBytes) + " / " + bytes(me.usage.downloadLimit);
        }
        quotaText.setText(text);
        quotaBar.setProgress(me.quotaBytes <= 0 ? 0 : (int) Math.min(1000, me.usedBytes * 1000 / me.quotaBytes));
    }

    // ── actions ─────────────────────────────────────────────────────────────

    private void createFolder(String name) {
        final Models.Node folder = currentFolder();
        async(() -> openEditz().createFolder(name, folder), n -> reload());
    }

    private void rename(Models.Node node, String name) {
        if (name.equals(node.name)) return;
        async(() -> openEditz().rename(node, name), n -> reload());
    }

    private void confirmDelete(Models.Node node) {
        new AlertDialog.Builder(getContext())
                .setTitle("Delete “" + node.name + "” for good?")
                .setMessage("This frees the space right away and cannot be undone.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (d, w) -> async(() -> { openEditz().delete(node); return Boolean.TRUE; }, ok -> reload()))
                .show();
    }

    private void showMenu(Models.Node node) {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (node.isFolder) {
            labels.add("Open");
            actions.add(() -> { trail.add(node); reload(); });
        } else {
            if (node.name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                labels.add("Restore into DoubleClips");
                actions.add(() -> restore(node));
            }
            labels.add("Save to Downloads");
            actions.add(() -> saveToDownloads(node));
        }
        labels.add("Rename");
        actions.add(() -> askText("Rename", "Name", node.name, "Save", name -> rename(node, name)));
        labels.add("Delete");
        actions.add(() -> confirmDelete(node));

        new AlertDialog.Builder(getContext())
                .setTitle(node.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void askText(String title, String hint, String initial, String ok, Done<String> done) {
        EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(hint);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        FrameWrap wrap = new FrameWrap(getContext(), input);
        new AlertDialog.Builder(getContext())
                .setTitle(title)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(ok, (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) done.run(text);
                })
                .show();
    }

    /** Gives a dialog EditText some side margin. */
    private static final class FrameWrap extends android.widget.FrameLayout {
        FrameWrap(Context c, View child) {
            super(c);
            int pad = (int) (20 * c.getResources().getDisplayMetrics().density);
            setPadding(pad, pad / 2, pad, 0);
            addView(child, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    // ── upload files ────────────────────────────────────────────────────────

    private void pickFiles() {
        if (!hasSession()) { toast("Sign in from the Profile tab first."); return; }
        if (filePickerLauncher == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        filePickerLauncher.launch(intent);
    }

    /** Called by MainActivity with the picker result. */
    public void uploadPicked(Intent data) {
        final ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;

        long total = 0;
        for (Uri u : uris) total += sizeOf(u);
        if (me != null) {
            long free = Math.max(0, me.quotaBytes - me.usedBytes);
            if (total > free) { toast("Not enough space: " + bytes(free) + " left."); return; }
            if (me.usage != null && total > Math.max(0, me.usage.uploadLimit - me.usage.uploadedBytes)) {
                toast("Daily upload limit: only " + bytes(Math.max(0, me.usage.uploadLimit - me.usage.uploadedBytes)) + " left today.");
                return;
            }
        }

        final long totalBytes = Math.max(1, total);
        final Models.Node target = currentFolder();
        MainActivity act = activity();
        if (act == null) return;
        act.runArchiveJob("Uploading…", "to OpenEditz", (listener, cancel) -> {
            long before = 0;
            for (Uri uri : uris) {
                final String name = displayName(uri);
                File tmp = copyToCache(uri, name);
                try {
                    ResumableUpload.Cancel uc = new ResumableUpload.Cancel();
                    uc.check = cancel::isCancelled;
                    final long base = before;
                    openEditz().upload(tmp, target, name, getContext().getContentResolver().getType(uri),
                            (done, t) -> listener.onProgress((base + done) / (double) totalBytes, name), uc);
                    before += tmp.length();
                } catch (ResumableUpload.CancelledException e) {
                    throw new ProjectZip.CancelledException();
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }, this::reload);
    }

    private long sizeOf(Uri uri) {
        try (Cursor c = getContext().getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) { /* unknown size */ }
        return 0;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContext().getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) { /* fall back */ }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private File copyToCache(Uri uri, String name) throws IOException {
        File dir = new File(getContext().getCacheDir(), "openeditz-upload");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, sanitize(name));
        try (InputStream in = getContext().getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("Can't read " + name);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    // ── back up a project / restore it ──────────────────────────────────────

    private void chooseProjectToBackUp() {
        if (!hasSession()) { toast("Sign in from the Profile tab first."); return; }
        final ArrayList<MainAreaScreen.ProjectData> projects = new ArrayList<>();
        File[] dirs = new File(Constants.DEFAULT_PROJECT_DIRECTORY(getContext())).listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                MainAreaScreen.ProjectData data = MainAreaScreen.ProjectData.loadProperties(getContext(), d.getAbsolutePath());
                if (data != null) projects.add(data);
            }
        }
        if (projects.isEmpty()) { toast("No projects to back up yet."); return; }
        Collections.sort(projects, (a, b) -> Long.compare(b.getProjectTimestamp(), a.getProjectTimestamp()));

        String[] titles = new String[projects.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = projects.get(i).getProjectTitle();
        new AlertDialog.Builder(getContext())
                .setTitle("Back up which project?")
                .setItems(titles, (d, which) -> backUp(projects.get(which)))
                .show();
    }

    /** Packs the project (the same ZIP as "Export project") and uploads it to OpenEditz/Projects. */
    private void backUp(MainAreaScreen.ProjectData project) {
        MainActivity act = activity();
        if (act == null) return;
        act.runArchiveJob("Backing up…", project.getProjectTitle(), (listener, cancel) -> {
            File tmp = new File(getContext().getCacheDir(), "openeditz-backup.zip");
            try {
                listener.onProgress(0, "Packing…");
                ProjectZip.Plan plan = ProjectZip.plan(new File(project.getProjectPath()), true, cancel);
                try (OutputStream out = new FileOutputStream(tmp)) {
                    ProjectZip.write(plan, out, (fraction, name) -> listener.onProgress(fraction * 0.4, "Packing…"), cancel);
                }

                long size = tmp.length();
                Models.Me fresh = openEditz().client().me();
                if (size > Math.max(0, fresh.quotaBytes - fresh.usedBytes)) {
                    throw new IOException("The backup is " + bytes(size) + " but only " + bytes(Math.max(0, fresh.quotaBytes - fresh.usedBytes)) + " of storage is left.");
                }
                if (fresh.usage != null && size > Math.max(0, fresh.usage.uploadLimit - fresh.usage.uploadedBytes)) {
                    throw new IOException("Daily upload limit: the backup is " + bytes(size) + " but only "
                            + bytes(Math.max(0, fresh.usage.uploadLimit - fresh.usage.uploadedBytes)) + " is left today.");
                }

                Models.Node folder = openEditz().ensureFolder("Projects");
                String stamp = new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(new Date());
                ResumableUpload.Cancel uc = new ResumableUpload.Cancel();
                uc.check = cancel::isCancelled;
                try {
                    openEditz().upload(tmp, folder, sanitize(project.getProjectTitle()) + " " + stamp + ".zip", "application/zip",
                            (done, total) -> listener.onProgress(0.4 + 0.6 * done / (double) Math.max(1, total), "Uploading…"), uc);
                } catch (ResumableUpload.CancelledException e) {
                    throw new ProjectZip.CancelledException();
                }
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }, () -> { toast("Backed up to OpenEditz/Projects"); reload(); });
    }

    /** Downloads a project ZIP from OpenEditz and hands it to the normal import. */
    private void restore(Models.Node node) {
        MainActivity act = activity();
        if (act == null) return;
        final File[] saved = new File[1];
        act.runArchiveJob("Downloading…", node.name, (listener, cancel) -> {
            File dir = new File(getContext().getCacheDir(), "openeditz-download");
            saved[0] = openEditz().download(node, dir, (done, total) -> {
                if (total > 0) listener.onProgress(done / (double) total, node.name);
            });
        }, () -> {
            if (saved[0] != null) activity().importProjectArchive(Uri.fromFile(saved[0]));
        });
    }

    // ── save a file to Downloads ────────────────────────────────────────────

    private void saveToDownloads(Models.Node node) {
        toast("Downloading…");
        async(() -> {
            File f = openEditz().download(node, new File(getContext().getCacheDir(), "openeditz-download"), null);
            copyToDownloads(f, node.name, node.mimeType == null ? "application/octet-stream" : node.mimeType);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return Boolean.TRUE;
        }, ok -> toast("Saved to Downloads/OpenEditz"));
    }

    private void copyToDownloads(File src, String name, String mime) throws IOException {
        ContentResolver resolver = getContext().getContentResolver();
        OutputStream out;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OpenEditz");
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Couldn't create the file in Downloads.");
            out = resolver.openOutputStream(uri);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OpenEditz");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            out = new FileOutputStream(new File(dir, name));
        }
        if (out == null) throw new IOException("Couldn't open the destination.");
        try (InputStream in = new java.io.FileInputStream(src); OutputStream os = out) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    // ── list ────────────────────────────────────────────────────────────────

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, meta;
            final ImageButton more;
            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.storageIcon);
                name = v.findViewById(R.id.storageName);
                meta = v.findViewById(R.id.storageMeta);
                more = v.findViewById(R.id.storageMore);
            }
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.cpn_storage_element, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            Models.Node n = items.get(position);
            h.name.setText(n.name);
            String date = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date(Models.parseDate(n.updatedAt)));
            h.meta.setText(n.isFolder ? date : bytes(n.size) + "  ·  " + date);
            h.icon.setImageResource(n.isFolder ? R.drawable.baseline_folder_24 : R.drawable.baseline_file_copy_24);
            h.itemView.setOnClickListener(v -> {
                if (n.isFolder) { trail.add(n); reload(); } else showMenu(n);
            });
            h.itemView.setOnLongClickListener(v -> { showMenu(n); return true; });
            h.more.setOnClickListener(v -> showMenu(n));
        }

        @Override public int getItemCount() { return items.size(); }
    }
}
