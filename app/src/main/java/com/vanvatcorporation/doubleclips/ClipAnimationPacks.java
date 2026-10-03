package com.vanvatcorporation.doubleclips;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Installable animation packs: a .zip that adds more in / out animations to the app.
 *
 * PACK FORMAT (the zip may contain exactly these entries and nothing else):
 * <pre>
 *   pack.json                       { "schema": 1, "id": "my-pack", "name": "My Pack", "version": 1,
 *                                     "author": "...", "description": "..." }      // id: [a-z0-9_-]{1,64}
 *   animations/in/&lt;id&gt;.json         one animation file each (see ClipAnimationLoader for the format);
 *   animations/out/&lt;id&gt;.json        the file name must equal the animation's "id", and an animation in
 *                                     in/ must be an "in" one, in out/ an "out" one
 * </pre>
 * Installed packs live in {@code <packsDir>/<packId>/...} (same layout) and are loaded into the
 * {@link ClipAnimationLoader} registry at startup, after the built-in animations.
 *
 * SECURITY. A pack is an untrusted download, so installation is strict and bounded and writes nothing
 * until EVERYTHING has been validated in memory:
 *  - the zip is read as a stream and only the whitelisted entry names above are accepted - so "..",
 *    absolute paths, backslashes, sub-folders, symlink-like tricks and stray files are all rejected
 *    outright ("zip slip" can't happen: no entry name is ever used as a path, the file name is rebuilt from
 *    the validated animation id);
 *  - hard caps counted on the bytes actually read (never on sizes the zip claims): 2 MB compressed,
 *    4 MB uncompressed in total, 512 KB per entry, 80 entries, 32 animations - so a zip bomb stops at the cap;
 *  - every animation goes through the same strict parser as the bundled ones; ids may not collide with a
 *    built-in or another installed animation;
 *  - the install is atomic: files are staged in a hidden folder and swapped in with a rename, so a failed
 *    or interrupted install never leaves a half-written pack, and a failed UPDATE leaves the old version intact.
 * Nothing in a pack is ever executed - animations are numbers plus three fixed formulas.
 *
 * Plain Java (java.io / java.util.zip only), so the desktop port can use it as-is; the Android side
 * (picking the file, the folder to use) is ClipAnimationAssets.
 */
public final class ClipAnimationPacks {

    public static final int SUPPORTED_SCHEMA = 1;
    public static final int MAX_ZIP_BYTES = 2 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 4 * 1024 * 1024;
    public static final int MAX_ENTRY_BYTES = 512 * 1024;
    public static final int MAX_ENTRIES = 80;
    public static final int MAX_ANIMATIONS = 32;

    static final String MANIFEST = "pack.json";
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final Pattern ANIMATION_ENTRY = Pattern.compile("animations/(in|out)/([a-z0-9_-]{1,64})\\.json");
    private static final Set<String> ALLOWED_DIRS = new HashSet<>(Arrays.asList("animations/", "animations/in/", "animations/out/"));

    private ClipAnimationPacks() {}

    /** A pack that can't be installed / removed; the message is written for the user. */
    public static final class PackException extends Exception {
        private static final long serialVersionUID = 1L;
        PackException(String message) { super(message); }
    }

    /** An installed pack, for listing in the UI. */
    public static final class PackInfo {
        public final String id;
        public final String name;
        public final int version;
        public final String author;
        public final String description;
        public final List<String> inIds;
        public final List<String> outIds;
        /** false when the pack's manifest is unreadable: it is still listed so the user can remove it. */
        public final boolean damaged;

        PackInfo(String id, String name, int version, String author, String description,
                 List<String> inIds, List<String> outIds, boolean damaged) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.author = author;
            this.description = description;
            this.inIds = Collections.unmodifiableList(inIds);
            this.outIds = Collections.unmodifiableList(outIds);
            this.damaged = damaged;
        }

        public int animationCount() { return inIds.size() + outIds.size(); }
    }

    public static final class InstallResult {
        public final PackInfo pack;
        /** The version that was replaced, or 0 for a fresh install. */
        public final int replacedVersion;

        InstallResult(PackInfo pack, int replacedVersion) {
            this.pack = pack;
            this.replacedVersion = replacedVersion;
        }
    }

    // ---- reading + validating (no side effects) ---------------------------------------

    /** Everything a pack contains, as text, after the zip-level checks. */
    private static final class Raw {
        String manifestText;
        /** entry path ("animations/in/x.json") -> JSON text, in zip order */
        final Map<String, String> animations = new LinkedHashMap<>();
    }

    private static final class Manifest {
        String id;
        String name;
        int version;
        String author;
        String description;
    }

    /** Fails once more than {@code max} bytes have been read (a file of exactly max bytes is fine). */
    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long max) {
            super(in);
            this.left = max;
        }

        private IOException tooLarge() {
            return new IOException("the file is larger than " + (MAX_ZIP_BYTES / 1024) + " KB");
        }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --left < 0) throw tooLarge();
            return b;
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                left -= n;
                if (left < 0) throw tooLarge();
            }
            return n;
        }
    }

    private static String shown(String entryName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entryName.length() && i < 60; i++) {
            char c = entryName.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return sb.toString();
    }

    private static String decode(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        return (!s.isEmpty() && s.charAt(0) == '\uFEFF') ? s.substring(1) : s; // editors on Windows like to add a BOM
    }

    private static byte[] readBounded(InputStream in, String what) throws IOException, PackException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_ENTRY_BYTES) {
                throw new PackException("'" + shown(what) + "' is larger than " + (MAX_ENTRY_BYTES / 1024) + " KB");
            }
        }
        return out.toByteArray();
    }

    private static Raw readZip(InputStream zipBytes) throws PackException {
        Raw raw = new Raw();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zin = new ZipInputStream(new Limited(zipBytes, MAX_ZIP_BYTES), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new PackException("The pack has too many files (limit " + MAX_ENTRIES + ").");
                String name = e.getName();
                if (e.isDirectory()) {
                    if (!ALLOWED_DIRS.contains(name)) throw new PackException("Unexpected folder in the pack: '" + shown(name) + "'.");
                    continue;
                }
                boolean isManifest = MANIFEST.equals(name);
                if (!isManifest && !ANIMATION_ENTRY.matcher(name).matches()) {
                    throw new PackException("Unexpected file in the pack: '" + shown(name)
                            + "'. A pack may only contain pack.json and animations/in|out/<id>.json.");
                }
                if ((isManifest && raw.manifestText != null) || raw.animations.containsKey(name)) {
                    throw new PackException("The pack lists '" + shown(name) + "' twice.");
                }
                byte[] data = readBounded(zin, name);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) throw new PackException("The pack is larger than " + (MAX_TOTAL_BYTES / 1024 / 1024) + " MB when unpacked.");
                if (isManifest) raw.manifestText = decode(data);
                else raw.animations.put(name, decode(data));
            }
        } catch (ZipException e) {
            throw new PackException("That isn't a valid .zip file (" + e.getMessage() + ").");
        } catch (IOException e) {
            throw new PackException("Couldn't read the pack: " + e.getMessage());
        }
        if (raw.manifestText == null) throw new PackException("pack.json is missing - is this an animation pack .zip?");
        if (raw.animations.isEmpty()) throw new PackException("The pack contains no animations.");
        if (raw.animations.size() > MAX_ANIMATIONS) throw new PackException("The pack has more than " + MAX_ANIMATIONS + " animations.");
        return raw;
    }

    private static Manifest parseManifest(String text) throws PackException {
        try {
            Map<String, Object> m = ClipAnimationLoader.parseObject(text);
            ClipAnimationLoader.allowKeys(m, "$", "schema", "id", "name", "version", "author", "description");
            double schema = ClipAnimationLoader.reqNumber(m, "schema", "$");
            if (schema != SUPPORTED_SCHEMA) {
                throw new ClipAnimationLoader.FormatException("$.schema: unsupported pack schema " + schema + " (this app reads " + SUPPORTED_SCHEMA + ")");
            }
            Manifest mf = new Manifest();
            mf.id = ClipAnimationLoader.reqString(m, "id", "$", 64);
            if (!ID.matcher(mf.id).matches()) {
                throw new ClipAnimationLoader.FormatException("$.id: must be 1-64 characters of a-z 0-9 _ -");
            }
            String name = ClipAnimationLoader.optString(m, "name", "$", 64);
            mf.name = (name == null || name.isEmpty()) ? mf.id : name;
            mf.version = 1;
            if (m.containsKey("version")) {
                double v = ClipAnimationLoader.reqNumber(m, "version", "$");
                if (v != Math.rint(v) || v < 1 || v > 1000000) throw new ClipAnimationLoader.FormatException("$.version: must be a whole number 1-1000000");
                mf.version = (int) v;
            }
            String author = ClipAnimationLoader.optString(m, "author", "$", 64);
            mf.author = author == null ? "" : author;
            String description = ClipAnimationLoader.optString(m, "description", "$", 256);
            mf.description = description == null ? "" : description;
            return mf;
        } catch (ClipAnimationLoader.FormatException e) {
            throw new PackException("pack.json: " + e.getMessage());
        }
    }

    private static ClipAnimation.Direction folderDirection(String entryPath) {
        return entryPath.startsWith("animations/in/") ? ClipAnimation.Direction.IN : ClipAnimation.Direction.OUT;
    }

    private static String stem(String entryPath) {
        Matcher m = ANIMATION_ENTRY.matcher(entryPath);
        if (!m.matches()) throw new IllegalArgumentException(entryPath);
        return m.group(2);
    }

    /**
     * Parses every animation of the pack WITHOUT touching the registry (a mirrorOf may point at another
     * animation of the same pack, or at one that is already registered). Returns them by id, in dependency order.
     */
    private static Map<String, ClipAnimation> parseAll(Map<String, String> animations) throws PackException {
        Map<String, ClipAnimation> staged = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>(animations.keySet());
        Map<String, String> lastError = new LinkedHashMap<>();
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            List<String> next = new ArrayList<>();
            for (String path : pending) {
                try {
                    ClipAnimation a = ClipAnimationLoader.parse(animations.get(path), path, staged, folderDirection(path));
                    if (!a.getId().equals(stem(path))) {
                        throw new PackException(path + ": the file name must match the animation's id ('" + a.getId() + "').");
                    }
                    if (staged.containsKey(a.getId())) throw new PackException("The pack defines the animation id '" + a.getId() + "' twice.");
                    staged.put(a.getId(), a);
                    progress = true;
                } catch (ClipAnimationLoader.FormatException e) {
                    if (!e.missingMirrorBase) throw new PackException(e.getMessage());
                    next.add(path);
                    lastError.put(path, e.getMessage());
                }
            }
            pending = next;
        }
        if (!pending.isEmpty()) throw new PackException(lastError.get(pending.get(0)));
        return staged;
    }

    // ---- install / uninstall ---------------------------------------------------------

    /**
     * Validates the pack in {@code zipBytes} and installs it under {@code packsDir}, replacing an installed
     * pack with the same id (an update). On success its animations are registered in the
     * {@link ClipAnimationLoader} registry immediately. On ANY failure nothing has changed.
     */
    public static synchronized InstallResult install(File packsDir, InputStream zipBytes) throws PackException {
        Raw raw = readZip(zipBytes);
        Manifest mf = parseManifest(raw.manifestText);
        Map<String, ClipAnimation> staged = parseAll(raw.animations);

        // ids this pack already owns (when updating) may be reused; any other registered id may not
        PackInfo existing = findInstalled(packsDir, mf.id);
        Set<String> own = new HashSet<>();
        if (existing != null) {
            own.addAll(existing.inIds);
            own.addAll(existing.outIds);
        }
        for (String id : staged.keySet()) {
            if (ClipAnimationLoader.isBuiltIn(id)) {
                throw new PackException("The animation id '" + id + "' is a built-in animation and can't be provided by a pack.");
            }
            if (ClipAnimationLoader.get(id) != null && !own.contains(id)) {
                throw new PackException("The animation id '" + id + "' is already used by another installed animation.");
            }
        }

        if (!packsDir.isDirectory() && !packsDir.mkdirs()) throw new PackException("Couldn't create the animation packs folder.");
        long stamp = System.nanoTime();
        File staging = new File(packsDir, ".staging-" + stamp);
        File target = new File(packsDir, mf.id);
        File old = new File(packsDir, ".old-" + stamp);
        try {
            writePack(staging, raw);
            boolean hadOld = target.exists();
            if (hadOld && !target.renameTo(old)) throw new PackException("Couldn't replace the installed pack (is it in use?).");
            if (!staging.renameTo(target)) {
                if (hadOld) old.renameTo(target); // put the previous version back
                throw new PackException("Couldn't finish installing the pack.");
            }
            if (hadOld) deleteRecursively(old);
        } catch (IOException e) {
            throw new PackException("Couldn't write the pack: " + e.getMessage());
        } finally {
            if (staging.exists()) deleteRecursively(staging);
        }

        // swap the registry: drop the old version's animations, add the new ones (already validated)
        for (String id : own) ClipAnimationLoader.unregister(id);
        try {
            for (Map.Entry<String, ClipAnimation> e : staged.entrySet()) ClipAnimationLoader.registerParsed(e.getValue(), false, e.getKey());
        } catch (ClipAnimationLoader.FormatException e) {
            throw new PackException(e.getMessage()); // can't happen after the checks above
        }
        return new InstallResult(readInfo(target, mf.id), existing == null ? 0 : existing.version);
    }

    /** Removes an installed pack and its animations (clips that used them keep their saved id and show "not installed"). */
    public static synchronized void uninstall(File packsDir, String packId) throws PackException {
        if (packId == null || !ID.matcher(packId).matches()) throw new PackException("Not a valid pack id.");
        File dir = new File(packsDir, packId);
        if (!dir.isDirectory()) throw new PackException("That pack isn't installed.");
        PackInfo info = readInfo(dir, packId);
        for (String id : info.inIds) ClipAnimationLoader.unregister(id);
        for (String id : info.outIds) ClipAnimationLoader.unregister(id);
        deleteRecursively(dir);
        if (dir.exists()) throw new PackException("Couldn't remove the pack's files.");
    }

    /** The installed packs, sorted by id. Unreadable ones are included (marked damaged) so they can be removed. */
    public static synchronized List<PackInfo> listInstalled(File packsDir) {
        List<PackInfo> out = new ArrayList<>();
        File[] dirs = packsDir == null ? null : packsDir.listFiles();
        if (dirs == null) return out;
        Arrays.sort(dirs);
        for (File d : dirs) {
            if (d.isDirectory() && ID.matcher(d.getName()).matches()) out.add(readInfo(d, d.getName()));
        }
        return out;
    }

    private static PackInfo findInstalled(File packsDir, String id) {
        File d = new File(packsDir, id);
        return d.isDirectory() ? readInfo(d, id) : null;
    }

    private static PackInfo readInfo(File dir, String id) {
        List<String> in = animationIds(new File(dir, "animations/in"));
        List<String> out = animationIds(new File(dir, "animations/out"));
        try {
            Manifest mf = parseManifest(readFile(new File(dir, MANIFEST)));
            if (!mf.id.equals(id)) throw new PackException("folder name differs from pack id");
            return new PackInfo(mf.id, mf.name, mf.version, mf.author, mf.description, in, out, false);
        } catch (PackException | IOException e) {
            return new PackInfo(id, id + " (damaged)", 0, "", "", in, out, true);
        }
    }

    private static List<String> animationIds(File folder) {
        List<String> ids = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files == null) return ids;
        Arrays.sort(files);
        String sub = folder.getName();
        for (File f : files) {
            Matcher m = ANIMATION_ENTRY.matcher("animations/" + sub + "/" + f.getName());
            if (f.isFile() && m.matches()) ids.add(m.group(2));
        }
        return ids;
    }

    // ---- loading installed packs at startup ------------------------------------------

    /**
     * Registers every installed pack's animations (call after the built-ins are loaded). Returns one
     * message per problem; a bad pack or file never stops the others. Also cleans up folders left behind
     * by an interrupted install.
     */
    public static synchronized List<String> loadInstalled(File packsDir) {
        List<String> problems = new ArrayList<>();
        File[] dirs = packsDir == null ? null : packsDir.listFiles();
        if (dirs == null) return problems;
        Arrays.sort(dirs);
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            if (d.getName().startsWith(".")) { deleteRecursively(d); continue; } // .staging-* / .old-* leftovers
            if (!ID.matcher(d.getName()).matches()) continue;
            loadPackDir(d, problems);
        }
        return problems;
    }

    private static void loadPackDir(File dir, List<String> problems) {
        String pack = dir.getName();
        try {
            Manifest mf = parseManifest(readFile(new File(dir, MANIFEST)));
            if (!mf.id.equals(pack)) throw new PackException("pack.json: id '" + mf.id + "' doesn't match the folder name");
        } catch (PackException | IOException e) {
            problems.add("Pack '" + pack + "' skipped: " + e.getMessage());
            return;
        }
        Map<String, String> texts = new LinkedHashMap<>();
        for (String sub : new String[]{"in", "out"}) {
            File[] files = new File(dir, "animations/" + sub).listFiles();
            if (files == null) continue;
            Arrays.sort(files);
            for (File f : files) {
                String entry = "animations/" + sub + "/" + f.getName();
                if (!f.isFile() || !ANIMATION_ENTRY.matcher(entry).matches()) continue;
                try {
                    if (f.length() > MAX_ENTRY_BYTES) throw new IOException("file is too large");
                    texts.put(entry, readFile(f));
                } catch (IOException e) {
                    problems.add("Pack '" + pack + "': " + entry + ": " + e.getMessage());
                }
            }
        }
        // register as they parse so a mirrorOf can find its base; retry the ones still waiting on one
        List<String> pending = new ArrayList<>(texts.keySet());
        boolean progress = true;
        Map<String, String> waiting = new LinkedHashMap<>();
        while (!pending.isEmpty() && progress) {
            progress = false;
            List<String> next = new ArrayList<>();
            for (String entry : pending) {
                try {
                    ClipAnimation a = ClipAnimationLoader.parse(texts.get(entry), "pack '" + pack + "': " + entry, null, folderDirection(entry));
                    if (!a.getId().equals(stem(entry))) {
                        problems.add("Pack '" + pack + "': " + entry + ": the file name must match the animation's id");
                    } else if (ClipAnimationLoader.get(a.getId()) != null) {
                        problems.add("Pack '" + pack + "': animation '" + a.getId() + "' skipped - that id is already in use");
                    } else {
                        ClipAnimationLoader.registerParsed(a, false, entry);
                    }
                    progress = true;
                } catch (ClipAnimationLoader.FormatException e) {
                    if (e.missingMirrorBase) { next.add(entry); waiting.put(entry, e.getMessage()); }
                    else { problems.add(e.getMessage()); progress = true; }
                }
            }
            pending = next;
        }
        for (String entry : pending) problems.add(waiting.get(entry));
    }

    // ---- files -------------------------------------------------------------------------

    private static void writePack(File dir, Raw raw) throws IOException {
        if (!dir.mkdirs()) throw new IOException("can't create " + dir.getName());
        writeText(new File(dir, MANIFEST), raw.manifestText);
        for (Map.Entry<String, String> e : raw.animations.entrySet()) {
            // the file name is rebuilt from the already-validated entry (folder + id), never used as given
            File f = new File(dir, e.getKey());
            File parent = f.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("can't create " + parent.getName());
            writeText(f, e.getValue());
        }
    }

    private static void writeText(File f, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readFile(File f) throws IOException {
        if (f.length() > MAX_ENTRY_BYTES) throw new IOException(f.getName() + " is too large");
        try (InputStream in = new java.io.FileInputStream(f)) {
            return decode(readAll(in));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }
}
