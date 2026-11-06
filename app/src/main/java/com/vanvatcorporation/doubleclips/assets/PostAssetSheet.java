package com.vanvatcorporation.doubleclips.assets;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.drivekit.AssetKind;
import com.vanvatcorporation.drivekit.AssetsClient;
import com.vanvatcorporation.drivekit.DriveException;
import com.vanvatcorporation.drivekit.Models;
import com.vanvatcorporation.drivekit.MyAsset;
import com.vanvatcorporation.drivekit.OpenEditzClient;
import com.vanvatcorporation.drivekit.Quota;
import com.vanvatcorporation.drivekit.QuotaUi;
import com.vanvatcorporation.drivekit.ResumableUpload;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Post an asset (desktop: PostAssetWindow, iOS: PostAssetView).
 * <ol>
 *   <li>Pick the file: one already in OpenEditz, or one from this phone (it is uploaded to OpenEditz/Assets first).</li>
 *   <li>Describe it: kind (guessed from the file), title, tags, description; a preview picture is made from the file when it can
 *       be (an image, a frame of a video, a font sample) or picked by hand.</li>
 *   <li>Confirm the rights. Posting shares the file as "anyone with the link" (view only) and lists it in the Assets tab for everyone;
 *       restricting it later, from My assets, Drive or OpenEditz, takes it off the tab again.</li>
 * </ol>
 */
public class PostAssetSheet extends BottomSheetDialogFragment {

    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static Runnable pendingOnPosted;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable onPosted;

    private Models.Node node;          // a file already in OpenEditz
    private File local;                // a file from this phone, copied to the cache (uploaded when posting)
    private String localName;
    private byte[] preview;
    private boolean customPicture;     // the user picked the picture: a change of kind keeps it
    private AssetKind kind = AssetKind.IMAGE;
    private boolean working;
    private int fileGeneration;        // bumped at every new file: a preview made for an older choice is dropped

    private View form;
    private TextView fileLabel, workingLabel, errorLabel;
    private ChipGroup kindGroup;
    private final List<Chip> kindChips = new ArrayList<>();
    private TextInputEditText titleField, tagsField, descriptionField;
    private ImageView previewView;
    private CheckBox rights;
    private MaterialButton postButton, cancelButton;
    private LinearProgressIndicator progress;

    private final ActivityResultLauncher<Intent> fileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
                adoptPhoneFile(result.getData().getData());
            });

    private final ActivityResultLauncher<Intent> pictureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
                adoptPicture(result.getData().getData());
            });

    /** @param onPosted runs on the main thread once the asset is posted (the sheet closes itself) */
    public static void show(FragmentActivity activity, Runnable onPosted) {
        PostAssetSheet sheet = new PostAssetSheet();
        sheet.onPosted = onPosted;
        sheet.show(activity.getSupportFragmentManager(), "post_asset");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.sheet_post_asset, container, false);
        form = root.findViewById(R.id.postForm);
        fileLabel = root.findViewById(R.id.postFileLabel);
        workingLabel = root.findViewById(R.id.postWorking);
        errorLabel = root.findViewById(R.id.postError);
        kindGroup = root.findViewById(R.id.postKindGroup);
        titleField = root.findViewById(R.id.postTitle);
        tagsField = root.findViewById(R.id.postTags);
        descriptionField = root.findViewById(R.id.postDescription);
        previewView = root.findViewById(R.id.postPreview);
        rights = root.findViewById(R.id.postRights);
        postButton = root.findViewById(R.id.postSubmit);
        cancelButton = root.findViewById(R.id.postCancel);
        progress = root.findViewById(R.id.postProgress);

        buildKindChips();
        root.findViewById(R.id.postFromOpenEditz).setOnClickListener(v -> pickFromOpenEditz());
        root.findViewById(R.id.postFromPhone).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            fileLauncher.launch(intent);
        });
        root.findViewById(R.id.postChoosePicture).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            pictureLauncher.launch(intent);
        });
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { refresh(); }
        };
        titleField.addTextChangedListener(watcher);
        rights.setOnCheckedChangeListener((b, checked) -> refresh());
        cancelButton.setOnClickListener(v -> { if (!working) dismissAllowingStateLoss(); });
        postButton.setOnClickListener(v -> post());
        refresh();
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) sheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
            BottomSheetBehavior<?> behavior = dialog.getBehavior();
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
            behavior.setDraggable(false);   // the form scrolls; a drag must not close it half-typed
        }
    }

    // ---- state -------------------------------------------------------------------------------------------

    private void buildKindChips() {
        for (AssetKind k : AssetKind.values()) {
            Chip chip = (Chip) LayoutInflater.from(requireContext()).inflate(R.layout.chip_asset_filter, kindGroup, false);
            chip.setId(View.generateViewId());
            chip.setText(AssetUi.singular(requireContext(), k));
            chip.setChecked(k == kind);
            chip.setOnCheckedChangeListener((button, checked) -> {
                if (!checked || kind == k) return;
                kind = k;
                // a new kind: the picture made from the file may be a different one (an image vs a video frame); one picked by hand stays
                if (local != null && !customPicture) makePreviewFromFile();
                refresh();
            });
            kindGroup.addView(chip);
            kindChips.add(chip);
        }
    }

    private String fileName() { return node != null ? node.name : localName; }

    private boolean canPost() {
        return fileName() != null && rights.isChecked() && text(titleField).length() >= 2 && !working;
    }

    private void refresh() {
        if (postButton != null) postButton.setEnabled(canPost());
    }

    private static String text(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private void showError(String text) {
        errorLabel.setText(text == null ? "" : text);
        errorLabel.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private void setWorking(boolean on, String text) {
        working = on;
        setEnabledDeep(form, !on);
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) progress.setIndeterminate(true);
        workingLabel.setText(on && text != null ? text : "");
        setCancelable(!on);
        refresh();
    }

    private static void setEnabledDeep(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabledDeep(group.getChildAt(i), enabled);
        }
    }

    /** Runs on the main thread, and only while the sheet is still on screen. */
    private void onUi(Runnable r) {
        ui.post(() -> { if (isAdded() && getView() != null) r.run(); });
    }

    private static String describe(Exception e) {
        return e.getMessage() == null || e.getMessage().isEmpty() ? e.toString() : e.getMessage();
    }

    // ---- choosing the file -------------------------------------------------------------------------------

    /** A file from this phone: copied to the cache first (the upload reads a real file), then adopted. */
    private void adoptPhoneFile(Uri uri) {
        final Context context = requireContext().getApplicationContext();
        final int generation = ++fileGeneration;
        showError(null);
        IO.execute(() -> {
            try {
                String name = displayName(context, uri);
                File dir = new File(context.getCacheDir(), "asset-post");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Couldn't make a temporary folder.");
                File copy = new File(dir, name.replaceAll("[\\\\/:*?\"<>|]", "_"));
                try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(copy)) {
                    if (in == null) throw new IOException(getString(R.string.assets_post_cant_read));
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
                onUi(() -> {
                    if (generation != fileGeneration) return;
                    node = null;
                    local = copy;
                    localName = name;
                    adopt(name, true);
                });
            } catch (IOException | RuntimeException e) {
                onUi(() -> showError(describe(e)));
            }
        });
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) { /* fall back to the last path segment */ }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "file" : last;
    }

    /** A newly chosen file: guess the kind, suggest a title, make the picture when the file is on this phone. */
    private void adopt(String name, boolean fromPhone) {
        showError(null);
        fileLabel.setText(name);
        AssetKind detected = AssetKind.detect(name);
        if (detected != null) {
            kind = detected;
            for (int i = 0; i < kindChips.size(); i++) kindChips.get(i).setChecked(AssetKind.values()[i] == detected);
        }
        if (text(titleField).isEmpty()) {
            int dot = name.lastIndexOf('.');
            titleField.setText(dot > 0 ? name.substring(0, dot) : name);
        }
        customPicture = false;
        setPreview(null);
        if (fromPhone) makePreviewFromFile();
        refresh();
    }

    private void makePreviewFromFile() {
        final File file = local;
        final AssetKind asked = kind;
        final int generation = fileGeneration;
        if (file == null) return;
        IO.execute(() -> {
            byte[] jpeg = AssetPreviewMaker.jpegFor(file, asked);
            // the choice may have moved on while this ran
            onUi(() -> { if (generation == fileGeneration && !customPicture && asked == kind) setPreview(jpeg); });
        });
    }

    private void adoptPicture(Uri uri) {
        final Context context = requireContext().getApplicationContext();
        IO.execute(() -> {
            byte[] jpeg = AssetPreviewMaker.jpegFromPicture(context, uri);
            onUi(() -> {
                if (jpeg == null) {
                    showError(getString(R.string.assets_post_picture_error));
                } else {
                    showError(null);
                    customPicture = true;
                    setPreview(jpeg);
                }
            });
        });
    }

    private void setPreview(byte[] jpeg) {
        preview = jpeg;
        if (jpeg == null) previewView.setImageDrawable(null);
        else previewView.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length));
    }

    // ---- a file already in OpenEditz ---------------------------------------------------------------------

    private void pickFromOpenEditz() {
        if (!AssetsSession.hasSession(requireContext())) {
            showError(getString(R.string.assets_sign_in_first));
            return;
        }
        browse(null, new ArrayList<>());
    }

    /** One level of the OpenEditz folder as a list: ".." goes up, a folder opens, a file is chosen. */
    private void browse(final Models.Node folder, final List<Models.Node> trail) {
        final OpenEditzClient client = AssetsSession.openEditz(requireContext());
        IO.execute(() -> {
            try {
                List<Models.Node> children = client.list(folder);
                List<Models.Node> sorted = new ArrayList<>(children);
                java.util.Collections.sort(sorted, (a, b) -> a.isFolder != b.isFolder ? (a.isFolder ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
                onUi(() -> showBrowser(folder, trail, sorted));
            } catch (Exception e) {
                onUi(() -> showError(describe(e)));
            }
        });
    }

    private void showBrowser(Models.Node folder, List<Models.Node> trail, List<Models.Node> children) {
        final boolean nested = folder != null;
        List<String> labels = new ArrayList<>();
        if (nested) labels.add(getString(R.string.assets_post_picker_up));
        for (Models.Node n : children) labels.add(n.isFolder ? n.name + "/" : n.name);
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle(folder == null ? "OpenEditz" : folder.name)
                .setNegativeButton(R.string.assets_cancel, null);
        if (labels.isEmpty()) {
            builder.setMessage(R.string.assets_post_picker_empty);
        } else {
            builder.setItems(labels.toArray(new String[0]), (dialog, which) -> {
                if (nested && which == 0) {
                    List<Models.Node> up = new ArrayList<>(trail);
                    Models.Node parent = up.isEmpty() ? null : up.remove(up.size() - 1);
                    browse(parent, up);
                    return;
                }
                Models.Node chosen = children.get(which - (nested ? 1 : 0));
                if (chosen.isFolder) {
                    List<Models.Node> deeper = new ArrayList<>(trail);
                    if (folder != null) deeper.add(folder);
                    browse(chosen, deeper);
                } else {
                    fileGeneration++;
                    node = chosen;
                    local = null;
                    localName = null;
                    adopt(chosen.name, false);
                }
            });
        }
        builder.show();
    }

    // ---- post --------------------------------------------------------------------------------------------

    private void post() {
        if (!canPost()) return;
        final String name = fileName();
        if (!kind.accepts(name)) {
            showError(getString(R.string.assets_post_kind_mismatch, AssetUi.singular(requireContext(), kind)));
            return;
        }
        showError(null);
        final Context context = requireContext().getApplicationContext();
        final Models.Node pickedNode = node;
        final File pickedLocal = local;
        final AssetKind postedKind = kind;
        final String title = text(titleField);
        final String description = text(descriptionField);
        final List<String> tags = AssetsClient.splitTags(text(tagsField));
        final byte[] picture = preview;
        setWorking(true, getString(R.string.assets_post_posting));

        final AssetsClient assets = AssetsSession.get(context);
        final OpenEditzClient openEditz = AssetsSession.openEditz(context);
        IO.execute(() -> {
            try {
                Models.Node target = pickedNode;
                if (target == null && pickedLocal != null) {
                    onUi(() -> workingLabel.setText(R.string.assets_post_uploading));
                    Models.Node folder = openEditz.ensureFolder("Assets");
                    String uploadName = uniqueName(openEditz, folder, pickedLocal.getName());
                    final int[] last = {-1};
                    target = openEditz.upload(pickedLocal, folder, uploadName, null, (done, total) -> {
                        int percent = total > 0 ? (int) (done * 100 / total) : 0;
                        if (percent != last[0]) {
                            last[0] = percent;
                            onUi(() -> {
                                progress.setIndeterminate(false);
                                progress.setProgressCompat(percent, true);
                                workingLabel.setText(getString(R.string.assets_post_uploading_percent, percent));
                            });
                        }
                    }, new ResumableUpload.Cancel());
                    // uploaded: if posting then fails, the next try reuses this file instead of uploading a second copy
                    final Models.Node uploaded = target;
                    onUi(() -> { node = uploaded; local = null; localName = null; });
                }
                if (target == null) return;
                onUi(() -> { progress.setIndeterminate(true); workingLabel.setText(R.string.assets_post_posting); });
                MyAsset asset = assets.publish(target, postedKind, title, description, tags);
                if (picture != null) {
                    try {
                        assets.uploadPreview(asset.id, picture);
                    } catch (IOException | RuntimeException ignored) {
                        // posted without a picture: the tab shows the kind's icon; My assets can add one
                    }
                }
                final Runnable done = onPosted;
                ui.post(() -> {
                    if (isAdded()) {
                        working = false;
                        dismissAllowingStateLoss();
                    }
                    if (done != null) done.run();
                });
            } catch (Exception e) {
                onUi(() -> {
                    setWorking(false, null);
                    if (Quota.isQuotaLimit(e)) QuotaUi.blocked(requireContext(), "Limit reached", describe(e));
                    else showError(describe(e));
                });
            }
        });
    }

    /** A name not yet used in the folder ("beat.mp3" -> "beat (1).mp3"): an upload under a taken name is refused by the server. */
    private static String uniqueName(OpenEditzClient client, Models.Node folder, String name) throws IOException {
        Set<String> taken = new HashSet<>();
        for (Models.Node n : client.list(folder)) {
            if (n.name != null) taken.add(n.name.toLowerCase(Locale.ROOT));
        }
        if (!taken.contains(name.toLowerCase(Locale.ROOT))) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        return name;
    }
}
