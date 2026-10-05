package com.vanvatcorporation.doubleclips;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The fonts a text style can use: the default, the ones imported into the project, and the device's own. */
public final class TextFonts {

    /** Folder inside the project that imported fonts are copied into. */
    public static final String PROJECT_FONT_DIR = "Fonts";

    public static final class Choice {
        /** What goes into TextStyle.fontPath (null = default). */
        public final String path;
        public final String label;
        public final String group;

        Choice(String path, String label, String group) {
            this.path = path;
            this.label = label;
            this.group = group;
        }
    }

    private TextFonts() { }

    private static boolean isFontFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc");
    }

    private static String prettyName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String n = dot > 0 ? fileName.substring(0, dot) : fileName;
        return n.replace('_', ' ').replace('-', ' ');
    }

    /** Default, then project fonts, then the device's fonts. */
    public static List<Choice> list(String projectPath) {
        List<Choice> out = new ArrayList<>();
        out.add(new Choice(null, "Default", "Default"));

        File projectDir = new File(projectPath, PROJECT_FONT_DIR);
        File[] imported = projectDir.listFiles();
        if (imported != null) {
            Arrays.sort(imported);
            for (File f : imported) {
                if (f.isFile() && isFontFile(f.getName())) {
                    out.add(new Choice(PROJECT_FONT_DIR + "/" + f.getName(), prettyName(f.getName()), "Imported"));
                }
            }
        }

        File[] system = new File("/system/fonts").listFiles();
        List<Choice> device = new ArrayList<>();
        if (system != null) {
            for (File f : system) {
                String name = f.getName();
                if (!f.isFile() || !isFontFile(name)) continue;
                // The device ships hundreds of script-specific Noto files; keep the useful, readable ones.
                if (name.startsWith("Noto") && !name.startsWith("NotoSerif-") && !name.startsWith("NotoSansMono")) continue;
                if (name.contains("Emoji") || name.contains("Symbols") || name.contains("Cjk")) continue;
                device.add(new Choice(f.getAbsolutePath(), prettyName(name), "Device"));
            }
        }
        Collections.sort(device, (a, b) -> a.label.compareToIgnoreCase(b.label));
        out.addAll(device);
        return out;
    }

    /** The label to show for a style's current font. */
    public static String labelFor(String projectPath, String fontPath) {
        if (fontPath == null || fontPath.isEmpty()) return "Default";
        return prettyName(new File(fontPath).getName());
    }

    /** True if the file starts like a TrueType, OpenType or TrueType-collection font. */
    private static boolean looksLikeFont(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] h = new byte[4];
            if (in.read(h) != 4) return false;
            String s = new String(h, java.nio.charset.StandardCharsets.ISO_8859_1);
            return (h[0] == 0 && h[1] == 1 && h[2] == 0 && h[3] == 0) || s.equals("true") || s.equals("OTTO") || s.equals("ttcf");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Copies a picked font file into the project's Fonts folder (so the project carries it) and returns the
     * value for TextStyle.fontPath ("Fonts/Name.ttf"). Throws with a message fit to show the user.
     */
    public static String importFont(Context context, String projectPath, Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = null;
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) {
            // fall back to the last path segment
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment() == null ? "font.ttf" : uri.getLastPathSegment();
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (!isFontFile(name)) throw new IOException("That doesn't look like a font file (.ttf, .otf or .ttc).");

        File dir = new File(projectPath, PROJECT_FONT_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Couldn't create the project's font folder.");
        File target = new File(dir, name);
        File partial = new File(dir, name + ".part");
        try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(partial)) {
            if (in == null) throw new IOException("Couldn't read that file.");
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        if (!looksLikeFont(partial)) {
            partial.delete();
            throw new IOException("That file isn't a valid font.");
        }
        if (target.exists()) target.delete();
        if (!partial.renameTo(target)) {
            partial.delete();
            throw new IOException("Couldn't save the font into the project.");
        }
        return PROJECT_FONT_DIR + "/" + name;
    }
}
