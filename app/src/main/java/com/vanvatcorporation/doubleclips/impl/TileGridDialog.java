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
import android.widget.AbsListView;
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

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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

        /**
         * The frames of its loop (played while the tile is on screen), or null when it has none or {@code wanted} turned
         * false first (asked from the drawing thread: the tile scrolled out of view or the dialog closed).
         */
        void loopFrames(BooleanSupplier wanted, Consumer<List<Bitmap>> onReady);
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
                .setNegativeButton(R.string.close, null)
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
                    .setMessage(R.string.clips_already_using_this_style_kee)
                    .setPositiveButton(R.string.delete, (d, w) -> {
                        dialog.dismiss();
                        listener.onDelete(tile);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });

        dialog.show();

        // Every live tile ON SCREEN plays its loop (like iOS), not only the selected one. Loops are drawn only for the visible
        // tiles (the GPU does it, one tile at a time, and stops for a tile that scrolls away), and only a bounded number of them
        // is kept in memory (about 4 MB each): the ones far from the screen are dropped first and drawn again when scrolled back.
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] stopped = {false};
        final AtomicInteger firstVisible = new AtomicInteger(0), visibleCount = new AtomicInteger(0);
        final Map<Integer, List<Bitmap>> loops = new LinkedHashMap<>();
        final Set<Integer> loopRequested = new HashSet<>(), loopFailed = new HashSet<>();
        final int margin = 2, maxLoops = 15;
        final int[] tick = {0};

        final java.util.function.IntPredicate nearScreen = pos ->
                pos >= firstVisible.get() - margin && pos < firstVisible.get() + visibleCount.get() + margin;

        final Runnable requestLoops = () -> {
            if (stopped[0]) return;
            if (loops.size() > maxLoops) {
                for (Iterator<Integer> it = loops.keySet().iterator(); it.hasNext(); ) {
                    int pos = it.next();
                    if (nearScreen.test(pos)) continue;
                    it.remove();
                    loopRequested.remove(pos);
                }
            }
            int first = firstVisible.get(), last = Math.min(tiles.size(), first + visibleCount.get());
            for (int pos = Math.max(0, first); pos < last; pos++) {
                final Tile tile = tiles.get(pos);
                if (tile.async == null || loopRequested.contains(pos) || loopFailed.contains(pos)) continue;
                loopRequested.add(pos);
                final int position = pos;
                tile.async.loopFrames(() -> !stopped[0] && nearScreen.test(position), frames -> {
                    if (stopped[0]) return;
                    if (frames == null || frames.isEmpty()) {
                        // null: the style cannot be drawn (do not ask again), or the tile left the screen first (ask again later)
                        if (nearScreen.test(position)) loopFailed.add(position);
                        loopRequested.remove(position);
                    } else if (nearScreen.test(position)) {
                        loops.put(position, frames);
                    } else {
                        loopRequested.remove(position);
                    }
                });
            }
        };

        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) { }

            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                firstVisible.set(first);
                visibleCount.set(visible);
                requestLoops.run();
            }
        });

        // One shared clock: every playing tile shows the same frame number, so the grid moves in step.
        final Runnable ticker = new Runnable() {
            @Override public void run() {
                if (stopped[0]) return;
                tick[0]++;
                for (Map.Entry<Integer, List<Bitmap>> entry : loops.entrySet()) {
                    ImageView shown = imageViews.get(entry.getKey());
                    List<Bitmap> frames = entry.getValue();
                    if (shown != null) shown.setImageBitmap(frames.get(tick[0] % frames.size()));
                }
                handler.postDelayed(this, 1000L / StyleTileRenderer.LOOP_FPS);
            }
        };
        dialog.setOnDismissListener(d -> {
            stopped[0] = true;
            handler.removeCallbacks(ticker);
            loops.clear();
        });
        handler.post(ticker);
        grid.post(() -> {
            firstVisible.set(grid.getFirstVisiblePosition());
            visibleCount.set(grid.getChildCount());
            requestLoops.run();
        });
        return dialog;
    }
}
