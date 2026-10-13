package com.vanvatcorporation.doubleclips.activities.editing;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.graphics.Bitmap;
import com.vanvatcorporation.doubleclips.StyleTileRenderer;
import com.vanvatcorporation.doubleclips.impl.TileGridDialog;

import com.vanvatcorporation.doubleclips.ClipAnimationAssets;
import com.vanvatcorporation.doubleclips.FXCommandEmitter;
import com.vanvatcorporation.doubleclips.OpenGLEdit;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.TransitionStyleCatalog;
import com.vanvatcorporation.doubleclips.TransitionStyle;
import com.vanvatcorporation.doubleclips.TransitionStyleLoader;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class TransitionEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {

    public Button applyAllTransitionButton;
    /** Shows the chosen style's name; tapping it opens the tile grid. */
    public Button transitionEditContent;
    public EditText transitionDurationContent;
    public Spinner transitionModeEditContent;
    /** What the style spinner shows, in order: the built-in styles plus the data-driven ones. */
    public List<TransitionStyleCatalog.Entry> styleEntries = new ArrayList<>();
    /** The chosen entry of {@link #styleEntries} (-1 = none). */
    private int selectedIndex = -1;


    public TransitionEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public TransitionEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TransitionEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TransitionEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    public void init()
    {
        super.init();

        applyAllTransitionButton = findViewById(R.id.applyAllButton);
        transitionEditContent = findViewById(R.id.transitionContent);
        transitionDurationContent = findViewById(R.id.durationContent);
        transitionModeEditContent = findViewById(R.id.transitionModeContent);

        refreshStyles(null);
        transitionEditContent.setOnClickListener(v -> openStyleGrid());
        transitionModeEditContent.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, EditingActivity.TransitionClip.TransitionMode.values()));


        onClose.add(() -> {
            transitionDurationContent.clearFocus();
        });
    }

    /**
     * Rebuilds the style list (the data-driven styles may have loaded since) and selects {@code currentStyle}.
     * A stored style that is in neither list stays selectable as "[not installed]" so closing the picker
     * does not overwrite it.
     */
    public void refreshStyles(String currentStyle) {
        ClipAnimationAssets.loadAll(getContext()); // also loads the transition styles; no-op after the first call
        styleEntries = TransitionStyleCatalog.build(
                FXCommandEmitter.FXRegistry.transitionFXMap,
                OpenGLEdit.glTransitionStyles(),
                TransitionStyleLoader.list(),
                OpenGLEdit.SUPPORTS_DATA_DRIVEN_TRANSITIONS,
                currentStyle);
        selectedIndex = TransitionStyleCatalog.indexOf(styleEntries, currentStyle);
        showSelected();
    }

    private void showSelected() {
        transitionEditContent.setText(selectedIndex >= 0 && selectedIndex < styleEntries.size()
                ? styleEntries.get(selectedIndex).label : "Choose...");
    }

    private void openStyleGrid() {
        final Context context = getContext();
        final StyleTileRenderer renderer = StyleTileRenderer.get(context);
        List<TileGridDialog.Tile> tiles = new ArrayList<>();
        for (TransitionStyleCatalog.Entry entry : styleEntries) {
            final String id = entry.id;
            String badge = entry.engine == TransitionStyleCatalog.Engine.OPENGL_ONLY ? "OpenGL"
                    : entry.engine == TransitionStyleCatalog.Engine.FFMPEG_ONLY ? "FFmpeg"
                    : entry.engine == TransitionStyleCatalog.Engine.UNKNOWN ? "?" : null;
            tiles.add(new TileGridDialog.Tile(id, entry.label, null, badge, new TileGridDialog.AsyncPreview() {
                @Override public void staticFrame(Consumer<Bitmap> onReady) {
                    renderer.staticTile(StyleTileRenderer.Kind.TRANSITION, id, onReady::accept);
                }

                @Override public void loopFrames(Consumer<List<Bitmap>> onReady) {
                    renderer.loopFrames(StyleTileRenderer.Kind.TRANSITION, id, onReady::accept);
                }
            }, false));
        }
        String selected = styleIdAt(selectedIndex, null);
        TileGridDialog.show(context, "Transition style", tiles, selected, new TileGridDialog.Listener() {
            @Override public void onPick(TileGridDialog.Tile tile) {
                for (int i = 0; i < styleEntries.size(); i++) {
                    if (styleEntries.get(i).id.equals(tile.id)) {
                        selectedIndex = i;
                        showSelected();
                        // A data-driven style brings its own default duration (Glitch Blur is 2.3 s): a pick the user
                        // made fills it in; opening the panel on a knot (refreshStyles) never does.
                        if (styleEntries.get(i).dataDriven) {
                            TransitionStyle style = TransitionStyleLoader.get(tile.id);
                            if (style != null) {
                                java.text.DecimalFormat format = new java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));
                                transitionDurationContent.setText(format.format(style.getDefaultDuration()));
                            }
                        }
                        return;
                    }
                }
            }

            @Override public void onDelete(TileGridDialog.Tile tile) { }
        }, null, null);
    }

    /** The chosen style's id, or {@code fallback} when nothing is chosen. */
    public String getStyleId(String fallback) {
        return styleIdAt(selectedIndex, fallback);
    }

    /** The style id of an entry position, or {@code fallback} when the position is not valid. */
    public String styleIdAt(int position, String fallback) {
        return position >= 0 && position < styleEntries.size() ? styleEntries.get(position).id : fallback;
    }





}
