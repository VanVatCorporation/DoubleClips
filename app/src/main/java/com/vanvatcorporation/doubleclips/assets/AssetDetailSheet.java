package com.vanvatcorporation.doubleclips.assets;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.vanvatcorporation.doubleclips.AssetLibrary;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.drivekit.AssetKind;
import com.vanvatcorporation.drivekit.AssetsClient;
import com.vanvatcorporation.drivekit.CommunityAsset;
import com.vanvatcorporation.drivekit.DriveException;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One community asset (desktop: AssetDetailWindow, iOS: AssetDetailView): preview, details, and what to do with it.
 * <p>
 * "Get" downloads the file (sign-in needed; it counts against YOUR daily download allowance, never the poster's) and then, by kind:
 * an effect or an animation pack is installed in the app; video / image / audio / font go into the open project when this was opened
 * from the editor ({@link AssetTarget}). From the Assets tab a font goes into a project you pick, and the other media are saved as a
 * file (a project that is not open has no preview proxies yet: the editor's own import makes them). "Save a copy" always saves the file.
 * <p>
 * The target and the projects list are plain fields set by {@link #show}: when Android recreates the sheet (rotation) they are gone,
 * and it behaves like the tab's sheet.
 */
public class AssetDetailSheet extends BottomSheetDialogFragment {

    /** The projects of the app (the tab has no open project). */
    public interface ProjectsProvider {
        List<MainAreaScreen.ProjectData> projects();
    }

    private static final String ARG_ASSET = "asset";
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    private final Handler ui = new Handler(Looper.getMainLooper());

    private CommunityAsset asset;
    private AssetTarget target;
    private ProjectsProvider projects;

    private TextView notice;
    private MaterialButton getButton, saveButton;
    private View progressRow;
    private LinearProgressIndicator progressBar;
    private boolean busy;
    private AtomicBoolean cancel;
    /** The downloaded file waiting for the user to pick where "Save a copy" puts it. */
    private File pendingSave;

    private final ActivityResultLauncher<Intent> createDocument = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                File file = pendingSave;
                pendingSave = null;
                if (file == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
                saveTo(file, result.getData().getData());
            });

    public static void show(FragmentActivity activity, CommunityAsset asset, AssetTarget target, ProjectsProvider projects) {
        AssetDetailSheet sheet = new AssetDetailSheet();
        Bundle args = new Bundle();
        args.putString(ARG_ASSET, asset.toStoredJson());
        sheet.setArguments(args);
        sheet.target = target;
        sheet.projects = projects;
        sheet.show(activity.getSupportFragmentManager(), "asset_detail");
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String json = getArguments() == null ? null : getArguments().getString(ARG_ASSET);
        asset = json == null ? null : CommunityAsset.fromStoredJson(AssetsSession.get(requireContext()).drive(), json);
        if (asset == null) dismissAllowingStateLoss();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.sheet_asset_detail, container, false);
        if (asset == null) return root;
        Context context = requireContext();

        ((ImageView) root.findViewById(R.id.detailKindIcon)).setImageResource(AssetUi.icon(asset.kind));
        AssetImages.load(root.findViewById(R.id.detailPreview), asset.previewUrl, 1000);
        ((TextView) root.findViewById(R.id.detailTitle)).setText(asset.title);
        ((TextView) root.findViewById(R.id.detailMeta)).setText(AssetUi.singular(context, asset.kind) + "  \u00b7  "
                + AssetUi.size(asset.size) + "  \u00b7  \u2193 " + AssetUi.count(asset.downloads));

        TextView owner = root.findViewById(R.id.detailOwner);
        if (asset.ownerName != null && !asset.ownerName.isEmpty()) owner.setText("@" + asset.ownerName);
        else owner.setVisibility(View.GONE);

        TextView tags = root.findViewById(R.id.detailTags);
        if (asset.tags.isEmpty()) {
            tags.setVisibility(View.GONE);
        } else {
            StringBuilder b = new StringBuilder();
            for (String tag : asset.tags) b.append(b.length() == 0 ? "" : "  ").append('#').append(tag);
            tags.setText(b);
        }

        TextView description = root.findViewById(R.id.detailDescription);
        if (asset.description != null && !asset.description.trim().isEmpty()) description.setText(asset.description.trim());
        else description.setVisibility(View.GONE);

        notice = root.findViewById(R.id.detailNotice);
        progressRow = root.findViewById(R.id.detailProgressRow);
        progressBar = root.findViewById(R.id.detailProgress);
        getButton = root.findViewById(R.id.detailGetButton);
        saveButton = root.findViewById(R.id.detailSaveButton);
        getButton.setText(getLabel());
        getButton.setOnClickListener(v -> get(false));
        saveButton.setOnClickListener(v -> get(true));
        root.findViewById(R.id.detailCancelButton).setOnClickListener(v -> { if (cancel != null) cancel.set(true); });
        root.findViewById(R.id.detailReportButton).setOnClickListener(v -> report());
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        // open fully: the actions are at the bottom of a tall sheet
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetBehavior<?> behavior = ((BottomSheetDialog) getDialog()).getBehavior();
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        }
    }

    @Override
    public void onDestroyView() {
        if (cancel != null) cancel.set(true);   // closing the sheet stops a running download
        super.onDestroyView();
    }

    // ---- small helpers -----------------------------------------------------------------------------------

    private String getLabel() {
        if (asset.kind.isInstalledInApp()) return getString(R.string.assets_install);
        if (target != null) return getString(R.string.assets_add_to, target.projectTitle());
        return getString(R.string.assets_get);
    }

    /** Runs on the main thread, and only while the sheet is still on screen. */
    private void onUi(Runnable r) {
        ui.post(() -> { if (isAdded() && getView() != null) r.run(); });
    }

    private void showNotice(String text, boolean error) {
        if (notice == null) return;
        notice.setText(text);
        notice.setTextColor(error ? 0xFFFF3B30 : 0xFF34C759);
        notice.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean b) {
        busy = b;
        getButton.setEnabled(!b);
        saveButton.setEnabled(!b);
        progressRow.setVisibility(b ? View.VISIBLE : View.GONE);
        if (b) progressBar.setIndeterminate(true);
    }

    private boolean requireSignIn() {
        if (AssetsSession.hasSession(requireContext())) return true;
        showNotice(getString(R.string.assets_sign_in_needed), true);
        return false;
    }

    // ---- get ---------------------------------------------------------------------------------------------

    /** Downloads the file, then installs / adds it ({@code saveCopy} false) or saves it as a file ({@code saveCopy} true). */
    private void get(boolean saveCopy) {
        if (busy || !requireSignIn()) return;
        setBusy(true);
        notice.setVisibility(View.GONE);
        cancel = new AtomicBoolean(false);
        final AtomicBoolean cancelFlag = cancel;
        final Context context = requireContext().getApplicationContext();
        final AssetsClient client = AssetsSession.get(context);
        IO.execute(() -> {
            try {
                File file = client.download(asset, (done, total) -> onUi(() -> {
                    long whole = total > 0 ? total : asset.size;
                    if (whole > 0) {
                        progressBar.setIndeterminate(false);
                        progressBar.setProgressCompat((int) Math.min(100, done * 100 / whole), true);
                    }
                }), cancelFlag);
                onUi(() -> { setBusy(false); finish(file, saveCopy); });
            } catch (DriveException stopped) {
                if ("cancelled".equals(stopped.code)) onUi(() -> setBusy(false));
                else onUi(() -> { setBusy(false); showNotice(describe(stopped), true); });
            } catch (Exception e) {
                onUi(() -> { setBusy(false); showNotice(describe(e), true); });
            }
        });
    }

    private static String describe(Exception e) {
        return e.getMessage() == null || e.getMessage().isEmpty() ? e.toString() : e.getMessage();
    }

    private void finish(File file, boolean saveCopy) {
        if (saveCopy) {
            saveAs(file);
            return;
        }
        switch (asset.kind) {
            case EFFECT:
            case PACK:
                install(file);
                break;
            default:
                if (target != null) {
                    addToTarget(file);
                } else if (asset.kind == AssetKind.FONT) {
                    pickProjectForFont(file);
                } else {
                    showNotice(getString(R.string.assets_save_media_hint), false);
                    saveAs(file);
                }
                break;
        }
    }

    /** An effect or a pack: installed in the app (file work: off the main thread). */
    private void install(File file) {
        final Context context = requireContext().getApplicationContext();
        setBusy(true);
        progressBar.setIndeterminate(true);
        IO.execute(() -> {
            try {
                if (asset.kind == AssetKind.EFFECT) {
                    String name = AssetLibrary.installEffect(context, file);
                    onUi(() -> { setBusy(false); showNotice(getString(R.string.assets_effect_installed, name), false); installed(); });
                } else {
                    String name = AssetLibrary.installPack(context, file);
                    onUi(() -> { setBusy(false); showNotice(getString(R.string.assets_pack_installed, name), false); installed(); });
                }
            } catch (AssetLibrary.InstallException e) {
                onUi(() -> { setBusy(false); showNotice(e.getMessage(), true); });
            }
        });
    }

    private void installed() {
        if (target != null) target.onInstalled(asset.kind);
    }

    /** Media or a font into the editor's open project. */
    private void addToTarget(File file) {
        final AssetTarget t = target;
        setBusy(true);
        progressBar.setIndeterminate(true);
        IO.execute(() -> {
            try {
                String line = t.add(file, asset.kind);
                onUi(() -> { setBusy(false); showNotice(line, false); });
            } catch (AssetLibrary.InstallException e) {
                onUi(() -> { setBusy(false); showNotice(e.getMessage(), true); });
            }
        });
    }

    // ---- a font from the tab: which project? -------------------------------------------------------------

    private void pickProjectForFont(File file) {
        final List<MainAreaScreen.ProjectData> list = projects == null ? new ArrayList<>() : projects.projects();
        if (list == null || list.isEmpty()) {
            showNotice(getString(R.string.assets_no_projects), true);
            return;
        }
        String[] titles = new String[list.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = list.get(i).getProjectTitle();
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.assets_pick_project_title)
                .setItems(titles, (dialog, which) -> {
                    final MainAreaScreen.ProjectData project = list.get(which);
                    final Context context = requireContext().getApplicationContext();
                    setBusy(true);
                    IO.execute(() -> {
                        try {
                            AssetLibrary.addFont(context, project.getProjectPath(), file);
                            onUi(() -> { setBusy(false); showNotice(getString(R.string.assets_font_added, project.getProjectTitle()), false); });
                        } catch (AssetLibrary.InstallException e) {
                            onUi(() -> { setBusy(false); showNotice(e.getMessage(), true); });
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- save a copy -------------------------------------------------------------------------------------

    private void saveAs(File file) {
        pendingSave = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(asset.mimeType != null && !asset.mimeType.isEmpty() ? asset.mimeType : "*/*");
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        try {
            createDocument.launch(intent);
        } catch (RuntimeException e) {
            pendingSave = null;
            showNotice(getString(R.string.assets_save_failed, describe(e)), true);
        }
    }

    private void saveTo(File file, Uri uri) {
        final Context context = requireContext().getApplicationContext();
        IO.execute(() -> {
            try (InputStream in = new FileInputStream(file); OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("Couldn't open the destination.");
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                onUi(() -> showNotice(getString(R.string.assets_saved), false));
            } catch (Exception e) {
                onUi(() -> showNotice(getString(R.string.assets_save_failed, describe(e)), true));
            }
        });
    }

    // ---- report ------------------------------------------------------------------------------------------

    private String reasonLabel(String reason) {
        switch (reason) {
            case "spam": return getString(R.string.assets_reason_spam);
            case "copyright": return getString(R.string.assets_reason_copyright);
            case "inappropriate": return getString(R.string.assets_reason_inappropriate);
            case "malware": return getString(R.string.assets_reason_malware);
            default: return getString(R.string.assets_reason_other);
        }
    }

    private void report() {
        if (!requireSignIn()) return;
        Context context = requireContext();
        final String[] reasons = AssetsClient.REPORT_REASONS;
        String[] labels = new String[reasons.length];
        for (int i = 0; i < labels.length; i++) labels[i] = reasonLabel(reasons[i]);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        Spinner spinner = new Spinner(context);
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, labels));
        EditText note = new EditText(context);
        note.setHint(R.string.assets_report_note_hint);
        box.addView(spinner);
        box.addView(note);

        new AlertDialog.Builder(context)
                .setTitle(R.string.assets_report)
                .setMessage(R.string.assets_report_what)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final String reason = reasons[Math.max(0, spinner.getSelectedItemPosition())];
                    final String text = note.getText().toString();
                    final AssetsClient client = AssetsSession.get(context);
                    IO.execute(() -> {
                        try {
                            client.report(asset, reason, text);
                            onUi(() -> showNotice(getString(R.string.assets_report_thanks), false));
                        } catch (Exception e) {
                            onUi(() -> showNotice(describe(e), true));
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
