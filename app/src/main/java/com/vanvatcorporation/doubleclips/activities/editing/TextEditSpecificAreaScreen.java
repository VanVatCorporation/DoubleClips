package com.vanvatcorporation.doubleclips.activities.editing;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.TextFonts;
import com.vanvatcorporation.doubleclips.TextRasterizer;
import com.vanvatcorporation.doubleclips.TextStyle;
import com.vanvatcorporation.doubleclips.TextStyleLibrary;
import com.vanvatcorporation.doubleclips.impl.TileGridDialog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TextEditSpecificAreaScreen extends BaseEditSpecificAreaScreen {

    public EditText textEditContent;
    public EditText textSizeContent;
    public EditText colorContent, outlineWidthContent, outlineColorContent;
    private TextView stylePresetValue, fontValue, unitModeValue, unitOrderValue;
    private View stylePresetRow, fontRow, unitModeRow, unitOrderRow;
    public EditText staggerContent;
    private String unitMode = "NONE", unitOrder = "FORWARD";
    private String inAnimationId, outAnimationId;
    /** Set when the user picks a style that carries in / out animations; the editor puts them on the clip once, on close. */
    private TextStyle pendingAnimationStyle;

    /** Project folder, for imported fonts. Set by the editor. */
    public String projectPath;
    /** Asks the editor to open the system file picker for a font; the result comes back through {@link #fontImported}. */
    public Runnable onImportFontRequested;

    /** The look being edited: name / id of the style it came from, and the font (which has its own row). */
    private String styleId, styleName, styleAuthor;
    private String fontPath;
    private List<String> supportedEngines;

    public TextEditSpecificAreaScreen(Context context) {
        super(context);
    }

    public TextEditSpecificAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TextEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TextEditSpecificAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void init()
    {
        super.init();
        textEditContent = findViewById(R.id.textContent);
        textSizeContent = findViewById(R.id.sizeContent);
        colorContent = findViewById(R.id.colorContent);
        outlineWidthContent = findViewById(R.id.outlineWidthContent);
        outlineColorContent = findViewById(R.id.outlineColorContent);
        stylePresetRow = findViewById(R.id.stylePresetRow);
        stylePresetValue = findViewById(R.id.stylePresetValue);
        fontRow = findViewById(R.id.fontRow);
        fontValue = findViewById(R.id.fontValue);
        unitModeRow = findViewById(R.id.unitModeRow);
        unitModeValue = findViewById(R.id.unitModeValue);
        unitOrderRow = findViewById(R.id.unitOrderRow);
        unitOrderValue = findViewById(R.id.unitOrderValue);
        staggerContent = findViewById(R.id.staggerContent);
        unitModeRow.setOnClickListener(v -> pickUnitMode());
        unitOrderRow.setOnClickListener(v -> pickUnitOrder());

        stylePresetRow.setOnClickListener(v -> showStyleBrowser());
        fontRow.setOnClickListener(v -> showFontPicker());

        onClose.add(() -> {
            textEditContent.clearFocus();
            textSizeContent.clearFocus();
            colorContent.clearFocus();
            outlineWidthContent.clearFocus();
            outlineColorContent.clearFocus();
            staggerContent.clearFocus();
        });
    }

    private static final String[] MODES = {"NONE", "CHARACTER", "WORD", "LINE"};
    private static final String[] MODE_LABELS = {"Whole text", "Character", "Word", "Line"};
    private static final String[] ORDERS = {"FORWARD", "REVERSE", "CENTER_OUT", "RANDOM"};
    private static final String[] ORDER_LABELS = {"Forward", "Reverse", "From the centre", "Random"};

    private static String labelOf(String[] values, String[] labels, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return labels[i];
        return labels[0];
    }

    private void pickUnitMode() {
        new AlertDialog.Builder(getContext()).setTitle("Animate by").setItems(MODE_LABELS, (d, which) -> {
            unitMode = MODES[which];
            styleId = null;
            styleName = "Custom";
            refreshLabels();
        }).show();
    }

    private void pickUnitOrder() {
        new AlertDialog.Builder(getContext()).setTitle("Order").setItems(ORDER_LABELS, (d, which) -> {
            unitOrder = ORDERS[which];
            styleId = null;
            styleName = "Custom";
            refreshLabels();
        }).show();
    }

    /** The style the user picked this session if it carries in / out animations (then forgets it), else null. */
    public TextStyle takePendingAnimationStyle() {
        TextStyle s = pendingAnimationStyle;
        pendingAnimationStyle = null;
        return s;
    }

    // ======================================================================
    //  Loading / reading the clip's style
    // ======================================================================

    /** Fills every style control from a clip's style (null = the default look). */
    public void showStyle(TextStyle style) {
        TextStyle shown = style != null ? style : TextStyle.DEFAULT;
        colorContent.setText(TextStyle.toHex(shown.colorArgb));
        outlineWidthContent.setText(String.valueOf(shown.outlineWidth));
        outlineColorContent.setText(TextStyle.toHex(shown.outlineColorArgb));
        styleId = shown.id;
        styleName = shown.name;
        styleAuthor = shown.author;
        fontPath = shown.fontPath;
        supportedEngines = shown.supportedEngines == null ? null : new ArrayList<>(shown.supportedEngines);
        unitMode = shown.unitMode == null ? "NONE" : shown.unitMode;
        unitOrder = shown.order == null ? "FORWARD" : shown.order;
        staggerContent.setText(String.valueOf(Math.round(shown.stagger * 100f)));
        inAnimationId = shown.inAnimationId;
        outAnimationId = shown.outAnimationId;
        pendingAnimationStyle = null;
        refreshLabels();
    }

    /**
     * The style the controls describe. If the colours / outline still equal the style it came from, that
     * style's name stays on it; otherwise it is "Custom".
     */
    public TextStyle readStyle(TextStyle previous) {
        TextStyle base = previous != null ? previous : TextStyle.DEFAULT;
        TextStyle result = new TextStyle();
        result.fontPath = fontPath;
        result.colorArgb = TextStyle.parseHex(colorContent.getText().toString(), base.colorArgb);
        result.outlineColorArgb = TextStyle.parseHex(outlineColorContent.getText().toString(), base.outlineColorArgb);
        float width = base.outlineWidth;
        try {
            width = Float.parseFloat(outlineWidthContent.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // keep the previous value
        }
        result.outlineWidth = Math.max(0f, Math.min(64f, width));
        result.supportedEngines = supportedEngines == null ? null : new ArrayList<>(supportedEngines);
        result.unitMode = "NONE".equals(unitMode) ? null : unitMode;
        result.order = unitOrder;
        float staggerPercent = base.stagger * 100f;
        try {
            staggerPercent = Float.parseFloat(staggerContent.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // keep the previous value
        }
        result.stagger = Math.max(0f, Math.min(95f, staggerPercent)) / 100f;
        result.inAnimationId = inAnimationId;
        result.outAnimationId = outAnimationId;
        // Only a unit-animated style needs OpenGL; anything else renders on both engines.
        if (result.animatesPerUnit()) {
            result.supportedEngines = new ArrayList<>();
            result.supportedEngines.add(TextStyle.ENGINE_OPENGL);
        } else {
            result.supportedEngines = null;
        }

        TextStyle source = findStyle(styleId);
        if (source != null && source.sameLookAs(withFont(result, source.fontPath))) {
            result.id = source.id;
            result.name = source.name;
            result.author = source.author;
        } else {
            result.id = null;
            result.name = "Custom";
            result.author = null;
        }
        return result;
    }

    /** {@code look} with {@code font} as its font; used so a preset without a font doesn't count as "changed" by the font row. */
    private static TextStyle withFont(TextStyle look, String font) {
        TextStyle copy = new TextStyle(look);
        if (font == null) copy.fontPath = null;
        return copy;
    }

    private TextStyle findStyle(String id) {
        if (id == null) return null;
        for (TextStyle s : TextStyle.builtIns()) if (id.equals(s.id)) return s;
        for (TextStyle s : TextStyleLibrary.load(getContext())) if (id.equals(s.id)) return s;
        return null;
    }

    private void refreshLabels() {
        stylePresetValue.setText(styleName != null ? styleName : "Custom");
        fontValue.setText(TextFonts.labelFor(projectPath, fontPath));
        unitModeValue.setText(labelOf(MODES, MODE_LABELS, unitMode));
        unitOrderValue.setText(labelOf(ORDERS, ORDER_LABELS, unitOrder));
    }

    /** The look currently in the controls, without touching the clip. */
    private TextStyle currentLook() {
        return readStyle(null);
    }

    // ======================================================================
    //  Style browser
    // ======================================================================

    private Bitmap thumbnail(TextStyle style) {
        final int size = 240;
        Bitmap thumb = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Bitmap text = TextRasterizer.renderText("Aa", 110f, style);
        if (text != null) {
            float scale = Math.min(1f, Math.min(size * 0.82f / text.getWidth(), size * 0.82f / text.getHeight()));
            int w = Math.max(1, Math.round(text.getWidth() * scale));
            int h = Math.max(1, Math.round(text.getHeight() * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(text, w, h, true);
            new Canvas(thumb).drawBitmap(scaled, (size - w) / 2f, (size - h) / 2f, null);
            if (scaled != text) scaled.recycle();
            text.recycle();
        }
        return thumb;
    }

    private TileGridDialog.Tile tileFor(TextStyle style, boolean userOwned) {
        // The thumbnail uses the style's own look but the default font unless the style names one.
        String badge = style.supportsEngine(TextStyle.ENGINE_FFMPEG) ? null : "OpenGL";
        return new TileGridDialog.Tile(style.id, style.name == null ? "Style" : style.name,
                style.author, badge, () -> thumbnail(style), userOwned);
    }

    private void showStyleBrowser() {
        Context context = getContext();
        List<TextStyle> all = new ArrayList<>(TextStyle.builtIns());
        List<TextStyle> mine = TextStyleLibrary.load(context);
        all.addAll(mine);

        List<TileGridDialog.Tile> tiles = new ArrayList<>();
        for (TextStyle s : all) tiles.add(tileFor(s, mine.contains(s)));

        TileGridDialog.show(context, "Text styles", tiles, styleId, new TileGridDialog.Listener() {
            @Override
            public void onPick(TileGridDialog.Tile tile) {
                for (TextStyle s : all) {
                    if (s.id != null && s.id.equals(tile.id)) {
                        applyStyle(s);
                        return;
                    }
                }
            }

            @Override
            public void onDelete(TileGridDialog.Tile tile) {
                try {
                    TextStyleLibrary.delete(context, tile.id);
                } catch (IOException e) {
                    new AlertDialog.Builder(context).setTitle("Couldn't delete").setMessage(e.getMessage()).show();
                }
                showStyleBrowser();
            }
        }, "Save current look as a style", this::promptSaveStyle);
    }

    /** Copies a style's values into the controls. A style with no font keeps the clip's current font. */
    private void applyStyle(TextStyle style) {
        colorContent.setText(TextStyle.toHex(style.colorArgb));
        outlineWidthContent.setText(String.valueOf(style.outlineWidth));
        outlineColorContent.setText(TextStyle.toHex(style.outlineColorArgb));
        if (style.fontPath != null) fontPath = style.fontPath;
        unitMode = style.unitMode == null ? "NONE" : style.unitMode;
        unitOrder = style.order == null ? "FORWARD" : style.order;
        staggerContent.setText(String.valueOf(Math.round(style.stagger * 100f)));
        inAnimationId = style.inAnimationId;
        outAnimationId = style.outAnimationId;
        pendingAnimationStyle = (style.inAnimationId != null || style.outAnimationId != null) ? style : null;
        styleId = style.id;
        styleName = style.name;
        styleAuthor = style.author;
        supportedEngines = style.supportedEngines == null ? null : new ArrayList<>(style.supportedEngines);
        refreshLabels();
    }

    private void promptSaveStyle() {
        Context context = getContext();
        final EditText input = new EditText(context);
        input.setHint("Style name");
        input.setSingleLine(true);
        new AlertDialog.Builder(context)
                .setTitle("Save as a style")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        TextStyle saved = TextStyleLibrary.save(context, currentLook(), input.getText().toString());
                        styleId = saved.id;
                        styleName = saved.name;
                        styleAuthor = null;
                        refreshLabels();
                    } catch (IOException e) {
                        new AlertDialog.Builder(context).setTitle("Couldn't save").setMessage(e.getMessage()).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ======================================================================
    //  Font picker
    // ======================================================================

    private void showFontPicker() {
        if (projectPath == null) return;
        List<TextFonts.Choice> choices = TextFonts.list(projectPath);
        String[] labels = new String[choices.size() + 1];
        for (int i = 0; i < choices.size(); i++) {
            TextFonts.Choice c = choices.get(i);
            labels[i] = c.group.equals("Default") ? c.label : c.label + "  (" + c.group.toLowerCase() + ")";
        }
        labels[choices.size()] = "Import a font file...";

        new AlertDialog.Builder(getContext())
                .setTitle("Font")
                .setItems(labels, (d, which) -> {
                    if (which == choices.size()) {
                        if (onImportFontRequested != null) onImportFontRequested.run();
                        return;
                    }
                    fontPath = choices.get(which).path;
                    styleId = null; // a different font is no longer the saved style
                    styleName = "Custom";
                    refreshLabels();
                })
                .show();
    }

    /** The editor calls this with the project-relative path of a font it just imported. */
    public void fontImported(String relativePath) {
        fontPath = relativePath;
        styleId = null;
        styleName = "Custom";
        refreshLabels();
    }
}
