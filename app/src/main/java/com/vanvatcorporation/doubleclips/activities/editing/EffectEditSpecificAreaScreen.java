package com.vanvatcorporation.doubleclips.activities.editing;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import com.vanvatcorporation.doubleclips.ClipAnimationAssets;
import com.vanvatcorporation.doubleclips.EffectStyle;
import com.vanvatcorporation.doubleclips.EffectStyleCatalog;
import com.vanvatcorporation.doubleclips.EffectStyleLoader;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.StyleTileRenderer;
import com.vanvatcorporation.doubleclips.impl.TileGridDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The effect clip's settings: the style (a tile grid, every style drawn live, see StyleTileRenderer), the strength
 * (effect.params["intensity"], what it scales depends on the style; hidden for a style with nothing to scale) and the
 * duration.
 */
public class EffectEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {

    public Button effectEditContent;
    public EditText effectDurationContent;
    private View strengthGroup;
    private TextView strengthText, strengthValue;
    private SeekBar strengthSeek;

    /** The stored style id (may be one this build doesn't know: it is kept until the user picks another). */
    private String styleId = "";
    private float strength = 1f;

    public EffectEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public EffectEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EffectEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public EffectEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    public void init()
    {
        super.init();
        effectEditContent = findViewById(R.id.effectContent);
        effectDurationContent = findViewById(R.id.durationContent);
        strengthGroup = findViewById(R.id.strengthGroup);
        strengthText = findViewById(R.id.strengthText);
        strengthValue = findViewById(R.id.strengthValue);
        strengthSeek = findViewById(R.id.strengthContent);

        ClipAnimationAssets.loadAll(getContext()); // registers the effect styles; no-op after the first call

        effectEditContent.setOnClickListener(v -> openStyleGrid());
        strengthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                EffectStyle style = EffectStyleLoader.get(styleId);
                if (style == null || !style.hasIntensity()) return;
                strength = style.getIntensityMin() + (style.getIntensityMax() - style.getIntensityMin()) * progress / 100f;
                showStrength();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        onClose.add(() -> {
            effectDurationContent.clearFocus();
        });
    }

    // ---- state ---------------------------------------------------------------------------------

    /** Shows this effect (called when the panel opens on a clip). {@code strength} is effect.params["intensity"], 1 when absent. */
    public void setEffect(String styleId, float strength) {
        this.styleId = styleId == null ? "" : styleId;
        this.strength = strength;
        refresh();
    }

    public String getStyleId() { return styleId; }

    /** The strength to store (1 when the style has nothing to scale). */
    public float getStrength() {
        EffectStyle style = EffectStyleLoader.get(styleId);
        return style != null && style.hasIntensity() ? strength : 1f;
    }

    private void refresh() {
        EffectStyle style = EffectStyleLoader.get(styleId);
        effectEditContent.setText(style != null ? style.getName() : (styleId.isEmpty() ? "Choose..." : styleId + " (not installed)"));
        if (style != null && style.hasIntensity()) {
            strength = Math.min(Math.max(strength, style.getIntensityMin()), style.getIntensityMax());
            strengthGroup.setVisibility(View.VISIBLE);
            strengthText.setText(style.getIntensityLabel());
            float span = style.getIntensityMax() - style.getIntensityMin();
            strengthSeek.setProgress(span <= 0f ? 0 : Math.round((strength - style.getIntensityMin()) / span * 100f));
            showStrength();
        } else {
            strengthGroup.setVisibility(View.GONE);
        }
    }

    private void showStrength() {
        strengthValue.setText(String.format(Locale.US, "%.2fx", strength));
    }

    // ---- the grid ------------------------------------------------------------------------------

    private void openStyleGrid() {
        final Context context = getContext();
        final StyleTileRenderer renderer = StyleTileRenderer.get(context);
        List<TileGridDialog.Tile> tiles = new ArrayList<>();
        for (EffectStyle style : EffectStyleCatalog.ordered(EffectStyleLoader.list())) {
            final String id = style.getId();
            tiles.add(new TileGridDialog.Tile(id, style.getName(), style.getAuthor(), "OpenGL", new TileGridDialog.AsyncPreview() {
                @Override public void staticFrame(Consumer<Bitmap> onReady) {
                    renderer.staticTile(StyleTileRenderer.Kind.EFFECT, id, onReady::accept);
                }

                @Override public void loopFrames(Consumer<List<Bitmap>> onReady) {
                    renderer.loopFrames(StyleTileRenderer.Kind.EFFECT, id, onReady::accept);
                }
            }, false));
        }
        // Every effect style is OpenGL-only (FFmpeg can't filter the layers below an effect clip): the badge says so.
        TileGridDialog.show(context, "Effect", tiles, EffectStyleLoader.canonical(styleId), new TileGridDialog.Listener() {
            @Override public void onPick(TileGridDialog.Tile tile) {
                if (!tile.id.equals(EffectStyleLoader.canonical(styleId))) {
                    styleId = tile.id;
                    strength = 1f; // a new style starts at its own look
                }
                refresh();
            }

            @Override public void onDelete(TileGridDialog.Tile tile) { }
        }, null, null);
    }
}
