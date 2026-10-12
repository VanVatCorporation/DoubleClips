package com.vanvatcorporation.doubleclips.impl;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.StyleTileRenderer;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A grid of square preview tiles, each with a name, an optional "@author" line and an optional
 * badge (used to flag engine-exclusive entries). Built for text styles first; the in / out
 * animation picker is meant to reuse it with GIF previews, so it only knows about tiles.
 */
public final class TileGridDialog {

    /** A preview drawn off the UI thread (the live style tiles): callbacks arrive on the main thread, null = could not be drawn. */
    public interface AsyncPreview {
        /** The tile's still picture. */
        void staticFrame(Consumer<Bitmap> onReady);

        /** The frames of its loop (played while this tile is the selected one), or null when it has none. */
        void loopFrames(Consumer<List<Bitmap>> onReady);
    }

    public static final class Tile {
        public final String id;
        public final String title;
        /** "@name" or null. */
        public final String author;
        /** Short badge such as "OpenGL", or null for none. */
        public final String badge;
        /** Produces the square preview; called lazily, on the UI thread, once per tile. */
        public final Supplier<Bitmap> preview;
        /** Only user-made entries can be deleted (long press). */
        public final boolean deletable;
        /** Set instead of {@link #preview} for live tiles. */
        public final AsyncPreview async;

        public Tile(String id, String title, String author, String badge, Supplier<Bitmap> preview, boolean deletable) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.badge = badge;
            this.preview = preview;
            this.deletable = deletable;
            this.async = null;
        }

        /** A live tile: the picture arrives later and the selected one loops. */
        public Tile(String id, String title, String author, String badge, AsyncPreview async, boolean deletable) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.badge = badge;
            this.preview = null;
            this.deletable = deletable;
            this.async = async;
        }
    }

    public interface Listener {
        void onPick(Tile tile);
        void onDelete(Tile tile);
    }

    private TileGridDialog() { }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static int themeColor(Context c, int attr, int fallback) {
        TypedValue tv = new TypedValue();
        return c.getTheme().resolveAttribute(attr, tv, true) ? (tv.resourceId != 0 ? c.getColor(tv.resourceId) : tv.data) : fallback;
    }

    /** A FrameLayout that is always as tall as it is wide. */
    private static final class SquareFrame extends FrameLayout {
        SquareFrame(Context c) { super(c); }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, widthSpec);
            int w = getMeasuredWidth();
            setMeasuredDimension(w, w);
        }
    }

    /**
     * @param selectedId the tile to highlight, or null
     * @param actionLabel optional button under the grid (e.g. "Save current look as style"), null for none
     */
    public static AlertDialog show(Context context, String title, List<Tile> tiles, String selectedId, Listener listener,
                                   String actionLabel, Runnable action) {
        final int accent = context.getColor(R.color.ios_blue);
        final int primary = themeColor(context, android.R.attr.textColorPrimary, Color.BLACK);
        final int secondary = themeColor(context, android.R.attr.textColorSecondary, Color.GRAY);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 4));

        GridView grid = new GridView(context);
        grid.setNumColumns(3);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(context, 8));
        grid.setVerticalSpacing(dp(context, 10));
        grid.setSelector(android.R.color.transparent);
        int maxHeight = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.55f);
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));

        if (actionLabel != null && action != null) {
            Button button = new Button(context);
            button.setText(actionLabel);
            button.setAllCaps(false);
            root.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            button.setOnClickListener(v -> action.run());
        }

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(root)
                .setNegativeButton("Close", null)
                .create();

        final SparseArray<Bitmap> previews = new SparseArray<>();
        final SparseArray<ImageView> imageViews = new SparseArray<>(); // the cell currently showing each position
        final SparseArray<Boolean> requested = new SparseArray<>();
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return tiles.size(); }
            @Override public Tile getItem(int i) { return tiles.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Tile tile = tiles.get(position);
                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.VERTICAL);

                SquareFrame frame = new SquareFrame(context);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF26262B);
                bg.setCornerRadius(dp(context, 12));
                boolean selected = tile.id != null && tile.id.equals(selectedId);
                if (selected) bg.setStroke(dp(context, 2), accent);
                frame.setBackground(bg);

                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                image.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
                Bitmap bmp = previews.get(position);
                if (bmp == null && tile.preview != null) {
                    try {
                        bmp = tile.preview.get();
                    } catch (RuntimeException ignored) {
                        bmp = null; // a broken style shouldn't take the whole picker down
                    }
                    if (bmp != null) previews.put(position, bmp);
                }
                if (tile.async != null) {
                    if (bmp != null) {
                        image.setImageBitmap(bmp);
                    } else if (requested.get(position) == null) {
                        requested.put(position, true);
                        final int pos = position;
                        tile.async.staticFrame(made -> {
                            if (made == null) return;
                            previews.put(pos, made);
                            ImageView shown = imageViews.get(pos);
                            if (shown != null) shown.setImageBitmap(made);
                        });
                    }
                    imageViews.put(position, image);
                }
                if (bmp != null) image.setImageBitmap(bmp);
                frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                if (tile.badge != null) {
                    TextView badge = new TextView(context);
                    badge.setText(tile.badge);
                    badge.setTextSize(10);
                    badge.setTextColor(Color.WHITE);
                    badge.setTypeface(Typeface.DEFAULT_BOLD);
                    badge.setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2));
                    GradientDrawable pill = new GradientDrawable();
                    pill.setColor(0xCC000000);
                    pill.setCornerRadius(dp(context, 8));
                    badge.setBackground(pill);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP | Gravity.END);
                    lp.setMargins(0, dp(context, 6), dp(context, 6), 0);
                    frame.addView(badge, lp);
                }
                cell.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView name = new TextView(context);
                name.setText(tile.title);
                name.setTextSize(13);
                name.setTextColor(primary);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setPadding(dp(context, 2), dp(context, 4), dp(context, 2), 0);
                cell.addView(name);

                if (tile.author != null) {
                    TextView author = new TextView(context);
                    author.setText(tile.author);
                    author.setTextSize(11);
                    author.setTextColor(secondary);
                    author.setSingleLine(true);
                    author.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    author.setPadding(dp(context, 2), 0, dp(context, 2), 0);
                    cell.addView(author);
                }
                return cell;
            }
        });

        grid.setOnItemClickListener((p, v, position, id) -> {
            dialog.dismiss();
            listener.onPick(tiles.get(position));
        });
        grid.setOnItemLongClickListener((p, v, position, id) -> {
            Tile tile = tiles.get(position);
            if (!tile.deletable) return false;
            new AlertDialog.Builder(context)
                    .setTitle("Delete \"" + tile.title + "\"?")
                    .setMessage("Clips already using this style keep their look.")
                    .setPositiveButton("Delete", (d, w) -> {
                        dialog.dismiss();
                        listener.onDelete(tile);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        dialog.show();

        // The selected live tile loops (the others stay frozen halfway).
        int selectedIndex = -1;
        for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).id != null && tiles.get(i).id.equals(selectedId)) selectedIndex = i;
        if (selectedIndex >= 0 && tiles.get(selectedIndex).async != null) {
            final int index = selectedIndex;
            final boolean[] stopped = {false};
            dialog.setOnDismissListener(d -> stopped[0] = true);
            tiles.get(index).async.loopFrames(frames -> {
                if (frames == null || frames.isEmpty() || stopped[0]) return;
                final Handler handler = new Handler(Looper.getMainLooper());
                handler.post(new Runnable() {
                    int frameIndex = 0;

                    @Override
                    public void run() {
                        if (stopped[0]) return;
                        ImageView shown = imageViews.get(index);
                        if (shown != null) shown.setImageBitmap(frames.get(frameIndex % frames.size()));
                        frameIndex++;
                        handler.postDelayed(this, 1000L / StyleTileRenderer.LOOP_FPS);
                    }
                });
            });
        }
        return dialog;
    }
}
