package com.vanvatcorporation.doubleclips;

import android.content.Context;
import android.net.Uri;

import com.vanvatcorporation.doubleclips.constants.Constants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What happens to a community asset once it is downloaded (desktop and iOS: AssetLibrary): an effect style or an animation pack is
 * installed in the app, a font or a video / image / audio file goes into a project. Every method blocks (file work): call it off the
 * main thread. A message of {@link InstallException} is written for the user.
 */
public final class AssetLibrary {
    private AssetLibrary() { }

    /** A downloaded file that can't be installed; the message is written for the user. */
    public static final class InstallException extends Exception {
        private static final long serialVersionUID = 1L;
        public InstallException(String message) { super(message); }
    }

    private static final int MAX_EFFECT_BYTES = 256 * 1024;
    private static final String EFFECTS_DIR = "community_effects";

    public static File effectsDir(Context context) {
        return new File(context.getFilesDir(), EFFECTS_DIR);
    }

    // ---- effects -----------------------------------------------------------------------------------------

    /** Installs a downloaded effect style and returns its name. */
    public static String installEffect(Context context, File file) throws InstallException {
        try {
            if (file.length() > MAX_EFFECT_BYTES) throw new InstallException("This effect file is too big.");
            String text = readUtf8(file);
            ClipAnimationAssets.loadAll(context);   // the built-ins must be known first: a download never replaces one of them
            EffectStyle style = EffectStyleLoader.parse(text, "community asset");
            String id = style.getId();
            File dir = effectsDir(context);
            File target = new File(dir, safeName(id) + ".json");
            if (EffectStyleLoader.get(id) != null && !target.isFile()) {
                throw new InstallException("An effect with this id already exists.");
            }
            if (!dir.isDirectory() && !dir.mkdirs()) throw new InstallException("Couldn't create the effects folder.");
            File part = new File(dir, target.getName() + ".part");
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (target.exists() && !target.delete()) {
                part.delete();
                throw new InstallException("Couldn't replace the installed effect.");
            }
            if (!part.renameTo(target)) {
                part.delete();
                throw new InstallException("Couldn't install the effect.");
            }
            EffectStyleLoader.register(text, "community asset");
            return style.getName();
        } catch (ClipAnimationLoader.FormatException e) {
            throw new InstallException(e.getMessage());
        } catch (IOException e) {
            throw new InstallException("Couldn't install the effect: " + e.getMessage());
        }
    }

    /**
     * Registers the installed effects (called once the built-in styles are loaded, at every start). Returns one line per bad file;
     * a bad file never stops the others.
     */
    public static List<String> loadInstalledEffects(Context context) {
        List<String> problems = new ArrayList<>();
        File[] files = effectsDir(context).listFiles((d, n) -> n.toLowerCase().endsWith(".json"));
        if (files == null) return problems;
        Arrays.sort(files);
        for (File f : files) {
            try {
                EffectStyleLoader.register(readUtf8(f), "community asset");
            } catch (ClipAnimationLoader.FormatException | IOException e) {
                problems.add("installed effect " + f.getName() + ": " + e.getMessage());
            }
        }
        return problems;
    }

    // ---- packs -------------------------------------------------------------------------------------------

    /** Installs a downloaded animation pack and returns its name. */
    public static String installPack(Context context, File file) throws InstallException {
        try {
            return ClipAnimationAssets.importPack(context, Uri.fromFile(file)).pack.name;
        } catch (ClipAnimationPacks.PackException e) {
            throw new InstallException(e.getMessage());
        }
    }

    // ---- fonts -------------------------------------------------------------------------------------------

    /**
     * Copies a downloaded font into the project's Fonts folder and returns the value for a text style's font ("Fonts/Name.ttf"). A
     * different file that has the same name is never overwritten (another clip may use it): the new one becomes name-1.ttf.
     */
    public static String addFont(Context context, String projectPath, File file) throws InstallException {
        String lower = file.getName().toLowerCase();
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf") && !lower.endsWith(".ttc")) {
            throw new InstallException("The editor can use .ttf, .otf and .ttc fonts; this font file isn't supported.");
        }
        if (projectPath == null) throw new InstallException("There is no project to add the font to.");
        File folder = new File(projectPath, TextFonts.PROJECT_FONT_DIR);
        File source = file;
        File temp = null;
        try {
            String name = file.getName();
            File existing = new File(folder, name.replaceAll("[^A-Za-z0-9._ -]", "_"));
            if (existing.exists() && existing.length() != file.length()) {
                int dot = name.lastIndexOf('.');
                String stem = dot > 0 ? name.substring(0, dot) : name;
                String extension = dot > 0 ? name.substring(dot) : "";
                int n = 1;
                File candidate;
                do {
                    candidate = new File(folder, (stem + "-" + n++ + extension).replaceAll("[^A-Za-z0-9._ -]", "_"));
                } while (candidate.exists() && candidate.length() != file.length());
                File dir = new File(context.getCacheDir(), "asset_font_tmp");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Couldn't make a temporary folder.");
                temp = new File(dir, candidate.getName());
                copy(file, temp);
                source = temp;
            }
            return TextFonts.importFont(context, projectPath, Uri.fromFile(source));
        } catch (IOException e) {
            throw new InstallException(e.getMessage() == null ? "Couldn't add the font." : e.getMessage());
        } finally {
            if (temp != null) temp.delete();
        }
    }

    // ---- video / image / audio ---------------------------------------------------------------------------

    /** Copies a downloaded video, image or audio file into the project's Clips folder (never over another file) and returns its name. */
    public static String addMedia(String projectPath, File file) throws InstallException {
        if (projectPath == null) throw new InstallException("There is no project to add the file to.");
        File dir = new File(projectPath, Constants.DEFAULT_CLIP_DIRECTORY);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new InstallException("Couldn't create the project's clips folder.");
        File target = uniqueFileIn(dir, file.getName());
        try {
            copy(file, target);
        } catch (IOException e) {
            target.delete();
            throw new InstallException("Couldn't add the file: " + e.getMessage());
        }
        return target.getName();
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** A file in {@code dir} named like {@code name}, or "name (1).ext", "name (2).ext"... when that is taken. */
    private static File uniqueFileIn(File dir, String name) {
        String safe = new File(name == null || name.isEmpty() ? "file" : name).getName();
        int dot = safe.lastIndexOf('.');
        String stem = dot > 0 ? safe.substring(0, dot) : safe;
        String extension = dot > 0 ? safe.substring(dot) : "";
        File file = new File(dir, safe);
        int n = 1;
        while (file.exists()) file = new File(dir, stem + " (" + n++ + ")" + extension);
        return file;
    }

    /** Copies a file in blocks (a big video doesn't have to fit in memory). */
    public static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    private static String readUtf8(File file) throws IOException {
        byte[] bytes;
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            bytes = out.toByteArray();
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("This isn't an effect file.");
        }
    }

    /** A style id as a file name: the id is the file's identity, so only characters every file system accepts. */
    private static String safeName(String id) {
        String s = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() || s.equals(".") || s.equals("..") ? "effect" : s;
    }
}
