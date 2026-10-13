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
    private android.widget.ImageView strengthDiamond;

    /** The slider moved (the owner applies it live), and the strength diamond was tapped. */
    public Runnable onStrengthEdited, onStrengthDiamond;
    /** True from picking a new style until the owner has written its default strength. */
    private boolean styleChanged;

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
        installStrengthDiamond();

        effectEditContent.setOnClickListener(v -> openStyleGrid());
        strengthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                EffectStyle style = EffectStyleLoader.get(styleId);
                if (style == null || !style.hasIntensity()) return;
                strength = style.getIntensityMin() + (style.getIntensityMax() - style.getIntensityMin()) * progress / 100f;
                showStrength();
                if (onStrengthEdited != null) onStrengthEdited.run();
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
        this.styleChanged = false;
        refresh();
    }

    /** Shows another strength (the keyframed value at the playhead) without touching the style. */
    public void setStrengthValue(float strength) {
        this.strength = strength;
        refresh();
    }

    public boolean isStyleChanged() { return styleChanged; }
    public void clearStyleChanged() { styleChanged = false; }

    /** A keyframe diamond after the strength value (hollow = not animated, half = animated, filled = a key at the playhead). */
    private void installStrengthDiamond() {
        if (!(strengthValue.getParent() instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout row = (android.widget.LinearLayout) strengthValue.getParent();
        strengthDiamond = new android.widget.ImageView(getContext());
        strengthDiamond.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = Math.round(5 * getResources().getDisplayMetrics().density);
        strengthDiamond.setPadding(pad, pad, pad, pad);
        strengthDiamond.setContentDescription("Keyframe Strength");
        strengthDiamond.setOnClickListener(v -> { if (onStrengthDiamond != null) onStrengthDiamond.run(); });
        int size = Math.round(32 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(size, size);
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        row.addView(strengthDiamond, row.indexOfChild(strengthValue) + 1, lp);
        setStrengthKeyMode(ClipEditSpecificAreaScreen.KeyMode.OFF, true);
    }

    /** The diamond's state; {@code enabled} is false while the playhead is outside the clip. */
    public void setStrengthKeyMode(ClipEditSpecificAreaScreen.KeyMode mode, boolean enabled) {
        if (strengthDiamond == null) return;
        strengthDiamond.setImageResource(mode == ClipEditSpecificAreaScreen.KeyMode.ON_KEY ? R.drawable.ic_key_on
                : (mode == ClipEditSpecificAreaScreen.KeyMode.ANIMATED ? R.drawable.ic_key_animated : R.drawable.ic_key_off));
        if (mode == ClipEditSpecificAreaScreen.KeyMode.OFF) {
            android.content.res.TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
            strengthDiamond.setImageTintList(a.getColorStateList(0));
            a.recycle();
        } else {
            strengthDiamond.setImageTintList(android.content.res.ColorStateList.valueOf(getContext().getColor(R.color.keyframe_active)));
        }
        strengthDiamond.setEnabled(enabled);
        strengthDiamond.setAlpha(enabled ? 1f : 0.3f);
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
            // (A stored / keyframed value outside the slider's range only moves the thumb to its end; the value itself is kept.)
            strengthGroup.setVisibility(View.VISIBLE);
            strengthText.setText(style.getIntensityLabel());
            float span = style.getIntensityMax() - style.getIntensityMin();
            float shown = Math.min(Math.max(strength, style.getIntensityMin()), style.getIntensityMax());
            strengthSeek.setProgress(span <= 0f ? 0 : Math.round((shown - style.getIntensityMin()) / span * 100f));
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
                    styleChanged = true;
                }
                refresh();
            }

            @Override public void onDelete(TileGridDialog.Tile tile) { }
        }, null, null);
    }
}
