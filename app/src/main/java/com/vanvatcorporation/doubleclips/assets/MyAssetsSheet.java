package com.vanvatcorporation.doubleclips.assets;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.drivekit.AssetsClient;
import com.vanvatcorporation.drivekit.MyAsset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * My assets (desktop: MyAssetsWindow, iOS: MyAssetsView): everything I posted, with the state of the link behind it.
 * <ul>
 *   <li>Public: anyone with the link, listed in the Assets tab.</li>
 *   <li>Restricted: no active link, not listed (restrict a file in Drive or OpenEditz and it moves here by itself).</li>
 *   <li>In review: hidden after reports.</li>
 *   <li>File deleted: the file is in the Drive trash.</li>
 * </ul>
 * The switch flips the file's own link; "Remove" takes the entry off the tab and leaves the file in Drive.
 */
public class MyAssetsSheet extends BottomSheetDialogFragment {

    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable onClosed;

    private RecyclerView recycler;
    private TextView status;
    private Adapter adapter;
    private final List<MyAsset> items = new ArrayList<>();
    /** Assets whose picture was replaced here: their link gets a version so the new picture shows (the server caches the old one for a day). */
    private final Map<String, Long> pictureVersion = new HashMap<>();
    private int generation;
    /** The asset whose picture is being chosen (the picker answers later). */
    private MyAsset pictureFor;

    private final ActivityResultLauncher<Intent> pictureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                MyAsset asset = pictureFor;
                pictureFor = null;
                if (asset == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
                changePicture(asset, result.getData().getData());
            });

    /** @param onClosed runs when the sheet closes (the tab reloads its list: link changes may have added or removed assets) */
    public static void show(FragmentActivity activity, Runnable onClosed) {
        MyAssetsSheet sheet = new MyAssetsSheet();
        sheet.onClosed = onClosed;
        sheet.show(activity.getSupportFragmentManager(), "my_assets");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.sheet_my_assets, container, false);
        recycler = root.findViewById(R.id.myAssetsRecycler);
        status = root.findViewById(R.id.myAssetsStatus);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new Adapter();
        recycler.setAdapter(adapter);
        reload();
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
            behavior.setDraggable(false);   // the list scrolls: a drag must not close it
        }
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (onClosed != null) {
            Runnable r = onClosed;
            onClosed = null;
            r.run();
        }
    }

    /** Runs on the main thread, and only while the sheet is still on screen. */
    private void onUi(Runnable r) {
        ui.post(() -> { if (isAdded() && getView() != null) r.run(); });
    }

    private static String describe(Exception e) {
        return e.getMessage() == null || e.getMessage().isEmpty() ? e.toString() : e.getMessage();
    }

    private void showError(String message) {
        new AlertDialog.Builder(requireContext()).setMessage(message).setPositiveButton(R.string.assets_ok, null).show();
    }

    // ---- loading -----------------------------------------------------------------------------------------

    private void reload() {
        final int gen = ++generation;
        status.setText(R.string.assets_my_loading);
        status.setVisibility(View.VISIBLE);
        final AssetsClient client = AssetsSession.get(requireContext());
        IO.execute(() -> {
            try {
                List<MyAsset> mine = client.mine();
                onUi(() -> {
                    if (gen != generation) return;
                    items.clear();
                    items.addAll(mine);
                    redraw();
                });
            } catch (Exception e) {
                String message = describe(e);
                onUi(() -> {
                    if (gen != generation) return;
                    status.setText(message);
                });
            }
        });
    }

    private void replace(MyAsset updated) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(updated.id)) {
                items.set(i, updated);
                break;
            }
        }
        redraw();
    }

    private void redraw() {
        adapter.notifyDataSetChanged();
        status.setText(R.string.assets_my_empty);
        status.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---- actions -----------------------------------------------------------------------------------------

    /** Flips "anyone with the link". A link that comes from a folder can't be removed from here: the server says which folder. */
    private void setPublic(MyAsset asset, boolean on) {
        final AssetsClient client = AssetsSession.get(requireContext());
        IO.execute(() -> {
            try {
                MyAsset updated = on ? client.share(asset) : client.restrict(asset);
                onUi(() -> replace(updated));
            } catch (Exception e) {
                String message = describe(e);
                onUi(() -> {
                    showError(message);
                    reload();     // the real state, whatever the switch said
                });
            }
        });
    }

    private void pickPicture(MyAsset asset) {
        pictureFor = asset;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        pictureLauncher.launch(intent);
    }

    /** Sends the picked picture as the asset's preview (the small JPEG the tab shows); shrunk to fit the 200 KB the server accepts. */
    private void changePicture(MyAsset asset, Uri uri) {
        final Context context = requireContext().getApplicationContext();
        final AssetsClient client = AssetsSession.get(context);
        IO.execute(() -> {
            byte[] jpeg = AssetPreviewMaker.jpegFromPicture(context, uri);
            if (jpeg == null) {
                onUi(() -> showError(getString(R.string.assets_post_picture_error)));
                return;
            }
            try {
                client.uploadPreview(asset.id, jpeg);
                onUi(() -> {
                    pictureVersion.put(asset.id, System.currentTimeMillis());
                    reload();     // the row is rebuilt with the new picture
                });
            } catch (Exception e) {
                String message = describe(e);
                onUi(() -> showError(message));
            }
        });
    }

    private void remove(MyAsset asset) {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.assets_my_remove_title, asset.title))
                .setMessage(R.string.assets_my_remove_message)
                .setPositiveButton(R.string.assets_my_remove_only, (d, w) -> doRemove(asset, false))
                .setNeutralButton(R.string.assets_my_remove_restrict, (d, w) -> doRemove(asset, true))
                .setNegativeButton(R.string.assets_cancel, null)
                .show();
    }

    private void doRemove(MyAsset asset, boolean revoke) {
        final AssetsClient client = AssetsSession.get(requireContext());
        IO.execute(() -> {
            try {
                client.remove(asset, revoke);
                onUi(() -> {
                    for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).id.equals(asset.id)) items.remove(i);
                    redraw();
                });
            } catch (Exception e) {
                String message = describe(e);
                onUi(() -> showError(message));
            }
        });
    }

    private void edit(MyAsset asset) {
        Context context = requireContext();
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText title = new EditText(context);
        title.setHint(R.string.assets_post_title_hint);
        title.setText(asset.title);
        EditText tags = new EditText(context);
        tags.setHint(R.string.assets_post_tags_hint);
        tags.setText(android.text.TextUtils.join(", ", asset.tags));
        EditText description = new EditText(context);
        description.setHint(R.string.assets_post_description_hint);
        description.setText(asset.description == null ? "" : asset.description);
        description.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        description.setMinLines(3);
        box.addView(title);
        box.addView(tags);
        box.addView(description);

        final AssetsClient client = AssetsSession.get(context);
        new AlertDialog.Builder(context)
                .setTitle(R.string.assets_my_edit_title)
                .setView(box)
                .setPositiveButton(R.string.assets_save, (d, w) -> {
                    final String newTitle = title.getText().toString().trim();
                    if (newTitle.length() < 2) {
                        Toast.makeText(context, R.string.assets_my_title_too_short, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final String newDescription = description.getText().toString().trim();
                    final List<String> newTags = AssetsClient.splitTags(tags.getText().toString());
                    IO.execute(() -> {
                        try {
                            MyAsset updated = client.update(asset, newTitle, newDescription, newTags);
                            onUi(() -> replace(updated));
                        } catch (Exception e) {
                            String message = describe(e);
                            onUi(() -> showError(message));
                        }
                    });
                })
                .setNegativeButton(R.string.assets_cancel, null)
                .show();
    }

    // ---- the list ----------------------------------------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.cpn_my_asset_element, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final MyAsset a = items.get(position);
            Context context = h.itemView.getContext();
            h.title.setText(a.title);
            h.meta.setText(AssetUi.singular(context, a.kind) + "  \u00b7  \u2193 " + AssetUi.count(a.downloads));
            h.kindIcon.setImageResource(AssetUi.icon(a.kind));
            Long version = pictureVersion.get(a.id);
            String url = a.previewUrl == null ? null
                    : version == null ? a.previewUrl : a.previewUrl + (a.previewUrl.contains("?") ? "&" : "?") + "v=" + version;
            AssetImages.load(h.preview, url, 240);

            bindBadge(context, h.badge, a);

            // the switch: set without firing the listener, then listen
            h.link.setOnCheckedChangeListener(null);
            h.note.setVisibility(View.VISIBLE);
            if (a.canToggleLink()) {
                h.link.setVisibility(View.VISIBLE);
                h.link.setChecked(MyAsset.STATE_PUBLIC.equals(a.state));
                h.link.setOnCheckedChangeListener((button, checked) -> {
                    button.setEnabled(false);
                    setPublic(a, checked);
                });
                h.link.setEnabled(true);
                if (MyAsset.STATE_PUBLIC.equals(a.state) && "folder".equals(a.via) && a.folderName != null) {
                    h.note.setText(context.getString(R.string.assets_my_through_folder, a.folderName));
                    h.note.setTextColor(0xFF8E8E93);
                } else {
                    h.note.setVisibility(View.GONE);
                }
            } else if (MyAsset.STATE_REVIEW.equals(a.state)) {
                h.link.setVisibility(View.GONE);
                h.note.setText(R.string.assets_my_review_note);
                h.note.setTextColor(0xFFFF9500);
            } else {
                h.link.setVisibility(View.GONE);
                h.note.setText(R.string.assets_my_deleted_note);
                h.note.setTextColor(0xFF8E8E93);
            }

            h.edit.setOnClickListener(v -> edit(a));
            h.picture.setText(a.previewUrl == null ? R.string.assets_my_add_picture : R.string.assets_my_change_picture);
            h.picture.setOnClickListener(v -> pickPicture(a));
            h.remove.setOnClickListener(v -> remove(a));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView title, meta, badge, note;
            final ImageView preview, kindIcon;
            final SwitchCompat link;
            final MaterialButton edit, picture, remove;

            Holder(@NonNull View view) {
                super(view);
                title = view.findViewById(R.id.myTitle);
                meta = view.findViewById(R.id.myMeta);
                badge = view.findViewById(R.id.myBadge);
                note = view.findViewById(R.id.myNote);
                preview = view.findViewById(R.id.myPreview);
                kindIcon = view.findViewById(R.id.myKindIcon);
                link = view.findViewById(R.id.myLinkSwitch);
                edit = view.findViewById(R.id.myEdit);
                picture = view.findViewById(R.id.myPicture);
                remove = view.findViewById(R.id.myRemove);
            }
        }
    }

    /** The coloured pill: Public / Restricted / In review / File deleted. */
    private static void bindBadge(Context context, TextView badge, MyAsset a) {
        int color;
        int text;
        if (MyAsset.STATE_PUBLIC.equals(a.state)) { color = 0xFF34C759; text = R.string.assets_state_public; }
        else if (MyAsset.STATE_RESTRICTED.equals(a.state)) { color = 0xFF8E8E93; text = R.string.assets_state_restricted; }
        else if (MyAsset.STATE_REVIEW.equals(a.state)) { color = 0xFFFF9500; text = R.string.assets_state_review; }
        else { color = 0xFFFF3B30; text = R.string.assets_state_deleted; }
        badge.setText(text);
        badge.setTextColor(color);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(40f * context.getResources().getDisplayMetrics().density);
        pill.setColor(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)));
        badge.setBackground(pill);
    }
}
