package com.vanvatcorporation.doubleclips;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Checks for ClipAnimationPacks: valid installs / updates / removal / startup loading, and a table of
 * hostile or broken zips that must all be rejected without changing anything. Plain Java; one message per failure.
 */
public final class ClipAnimationPackChecks {

    private ClipAnimationPackChecks() {}

    // ---- builders ----------------------------------------------------------------------------------

    /** name, content, name, content ... ; a name ending in '/' is a folder entry; content is a String or byte[]. */
    static byte[] zip(Object... nameContent) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (int i = 0; i < nameContent.length; i += 2) {
                String name = (String) nameContent[i];
                z.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) {
                    Object c = nameContent[i + 1];
                    z.write(c instanceof byte[] ? (byte[]) c : ((String) c).getBytes(StandardCharsets.UTF_8));
                }
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static String manifest(String id, int version) {
        return "{\"schema\":1,\"id\":\"" + id + "\",\"name\":\"Pack " + id + "\",\"version\":" + version
                + ",\"author\":\"tester\",\"description\":\"d\"}";
    }

    static String inAnim(String id) {
        return "{\"schema\":1,\"id\":\"" + id + "\",\"direction\":\"in\",\"channels\":{"
                + "\"offsetX\":{\"kind\":\"knots\",\"points\":[[0,-0.4],[1,0]],\"interp\":\"smooth\"},"
                + "\"opacity\":{\"kind\":\"knots\",\"points\":[[0,0],[1,1]]}}}";
    }

    static String outMirror(String id, String base) {
        return "{\"schema\":1,\"id\":\"" + id + "\",\"direction\":\"out\",\"mirrorOf\":\"" + base + "\"}";
    }

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("packs").toFile();
    }

    private static List<String> names(File dir) {
        List<String> n = new ArrayList<>();
        String[] l = dir.list();
        if (l != null) { Arrays.sort(l); n.addAll(Arrays.asList(l)); }
        return n;
    }

    private static int registered() {
        return ClipAnimationLoader.list(ClipAnimation.Direction.IN).size() + ClipAnimationLoader.list(ClipAnimation.Direction.OUT).size();
    }

    private static void rejects(List<String> fails, File dir, String label, byte[] zipBytes, String messagePart) throws Exception {
        List<String> before = names(dir);
        int regBefore = registered();
        try {
            ClipAnimationPacks.install(dir, new ByteArrayInputStream(zipBytes));
            fails.add("should have been rejected: " + label);
        } catch (ClipAnimationPacks.PackException e) {
            if (messagePart != null && !e.getMessage().toLowerCase().contains(messagePart.toLowerCase())) {
                fails.add(label + ": message should mention '" + messagePart + "' but was: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            fails.add(label + ": wrong exception type " + e);
        }
        if (!before.equals(names(dir))) fails.add(label + ": a rejected pack changed the packs folder: " + names(dir));
        if (regBefore != registered()) fails.add(label + ": a rejected pack changed the registry");
    }

    // ---- the checks ----------------------------------------------------------------------------------

    public static List<String> runAll() throws Exception {
        List<String> fails = new ArrayList<>();
        ClipAnimationLoader.clearForTests();
        String unfoldJson = new String(Files.readAllBytes(new File("src/main/assets/animations/in/unfold.json").toPath()), StandardCharsets.UTF_8);
        ClipAnimationLoader.register(unfoldJson, "unfold.json", true, ClipAnimation.Direction.IN);
        File dir = tempDir();

        // 1. a valid pack: an in animation plus an out animation that mirrors it
        byte[] good = zip("pack.json", manifest("slide-pack", 2), "animations/", null, "animations/in/", null, "animations/out/", null,
                "animations/in/slide_in.json", inAnim("slide_in"), "animations/out/slide_out.json", outMirror("slide_out", "slide_in"));
        ClipAnimationPacks.InstallResult r = ClipAnimationPacks.install(dir, new ByteArrayInputStream(good));
        if (r.pack.version != 2 || !r.pack.id.equals("slide-pack") || !r.pack.name.equals("Pack slide-pack") || !r.pack.author.equals("tester")
                || r.replacedVersion != 0 || r.pack.damaged) fails.add("install result wrong");
        if (!r.pack.inIds.equals(Arrays.asList("slide_in")) || !r.pack.outIds.equals(Arrays.asList("slide_out"))) fails.add("pack animation ids wrong: " + r.pack.inIds + r.pack.outIds);
        ClipAnimation sIn = ClipAnimationLoader.get("slide_in", ClipAnimation.Direction.IN), sOut = ClipAnimationLoader.get("slide_out", ClipAnimation.Direction.OUT);
        if (sIn == null || sOut == null || !sOut.isReversed()) fails.add("installed animations not registered");
        if (!new File(dir, "slide-pack/pack.json").isFile() || !new File(dir, "slide-pack/animations/in/slide_in.json").isFile()
                || !new File(dir, "slide-pack/animations/out/slide_out.json").isFile()) fails.add("pack files not written");
        if (ClipAnimationPacks.listInstalled(dir).size() != 1) fails.add("listInstalled should find 1 pack");
        if (names(dir).size() != 1) fails.add("install left extra folders: " + names(dir));

        // 2. startup loading reproduces it, cleans leftovers, and isolates a broken pack
        ClipAnimationLoader.clearForTests();
        ClipAnimationLoader.register(unfoldJson, "unfold.json", true, ClipAnimation.Direction.IN);
        new File(dir, ".staging-123/animations").mkdirs();
        new File(dir, "broken-pack").mkdirs();
        Files.write(new File(dir, "broken-pack/pack.json").toPath(), "{ nope".getBytes(StandardCharsets.UTF_8));
        List<String> problems = ClipAnimationPacks.loadInstalled(dir);
        if (ClipAnimationLoader.get("slide_in") == null || ClipAnimationLoader.get("slide_out") == null) fails.add("loadInstalled didn't register the good pack");
        if (problems.size() != 1 || !problems.get(0).contains("broken-pack")) fails.add("loadInstalled should report exactly the broken pack: " + problems);
        if (new File(dir, ".staging-123").exists()) fails.add("loadInstalled should remove interrupted-install leftovers");
        boolean sawDamaged = false;
        for (ClipAnimationPacks.PackInfo p : ClipAnimationPacks.listInstalled(dir)) if (p.id.equals("broken-pack") && p.damaged) sawDamaged = true;
        if (!sawDamaged) fails.add("a damaged pack must still be listed (so it can be removed)");
        ClipAnimationPacks.uninstall(dir, "broken-pack");
        if (new File(dir, "broken-pack").exists()) fails.add("damaged pack not removed");

        // 3. update: same pack id, different contents -> old animation gone, replacedVersion reported
        byte[] v3 = zip("pack.json", manifest("slide-pack", 3), "animations/in/pop_in.json", inAnim("pop_in"), "animations/in/slide_in.json", inAnim("slide_in"));
        ClipAnimationPacks.InstallResult up = ClipAnimationPacks.install(dir, new ByteArrayInputStream(v3));
        if (up.replacedVersion != 2 || up.pack.version != 3) fails.add("update result wrong: replaced " + up.replacedVersion);
        if (ClipAnimationLoader.get("slide_out") != null || ClipAnimationLoader.get("pop_in") == null || ClipAnimationLoader.get("slide_in") == null) fails.add("update must swap the animations");
        if (new File(dir, "slide-pack/animations/out/slide_out.json").exists()) fails.add("update left the old files behind");
        if (ClipAnimationPacks.listInstalled(dir).size() != 1 || names(dir).size() != 1) fails.add("update must leave exactly one pack folder: " + names(dir));

        // 4. a failed update keeps the previous version untouched
        rejects(fails, dir, "bad update", zip("pack.json", manifest("slide-pack", 4), "animations/in/slide_in.json", "{ not json"), "slide_in.json");
        if (ClipAnimationLoader.get("pop_in") == null || ClipAnimationPacks.listInstalled(dir).get(0).version != 3) fails.add("a failed update must keep the old version");

        // 5. hostile / broken zips: all rejected, nothing changes
        rejects(fails, dir, "not a zip", "this is not a zip file".getBytes(StandardCharsets.UTF_8), "pack.json");
        rejects(fails, dir, "empty input", new byte[0], "pack.json");
        rejects(fails, dir, "empty zip", zip(), "pack.json");
        rejects(fails, dir, "no manifest", zip("animations/in/a.json", inAnim("a")), "pack.json");
        rejects(fails, dir, "no animations", zip("pack.json", manifest("p1", 1)), "no animations");
        rejects(fails, dir, "zip slip ..", zip("pack.json", manifest("p1", 1), "../evil.json", "{}"), "unexpected file");
        rejects(fails, dir, "zip slip nested ..", zip("pack.json", manifest("p1", 1), "animations/in/../../evil.json", "{}"), "unexpected file");
        rejects(fails, dir, "absolute path", zip("pack.json", manifest("p1", 1), "/etc/cron.d/x.json", "{}"), "unexpected file");
        rejects(fails, dir, "backslash path", zip("pack.json", manifest("p1", 1), "animations\\in\\a.json", inAnim("a")), "unexpected file");
        rejects(fails, dir, "sub folder", zip("pack.json", manifest("p1", 1), "animations/in/sub/a.json", inAnim("a")), "unexpected file");
        rejects(fails, dir, "uppercase name", zip("pack.json", manifest("p1", 1), "animations/in/A.json", inAnim("A")), "unexpected file");
        rejects(fails, dir, "wrong extension", zip("pack.json", manifest("p1", 1), "animations/in/a.txt", inAnim("a")), "unexpected file");
        rejects(fails, dir, "stray file", zip("pack.json", manifest("p1", 1), "animations/in/a.json", inAnim("a"), "readme.txt", "hi"), "unexpected file");
        rejects(fails, dir, "animation outside folders", zip("pack.json", manifest("p1", 1), "animations/a.json", inAnim("a")), "unexpected file");
        rejects(fails, dir, "foreign folder", zip("pack.json", manifest("p1", 1), "other/", null, "animations/in/a.json", inAnim("a")), "unexpected folder");
        rejects(fails, dir, "bad manifest json", zip("pack.json", "{ nope", "animations/in/a.json", inAnim("a")), "pack.json");
        rejects(fails, dir, "manifest schema 2", zip("pack.json", manifest("p1", 1).replace("\"schema\":1", "\"schema\":2"), "animations/in/a.json", inAnim("a")), "schema");
        rejects(fails, dir, "manifest id caps", zip("pack.json", manifest("Bad Id", 1), "animations/in/a.json", inAnim("a")), "id");
        rejects(fails, dir, "manifest id traversal", zip("pack.json", manifest("../x", 1), "animations/in/a.json", inAnim("a")), "id");
        rejects(fails, dir, "manifest unknown key", zip("pack.json", manifest("p1", 1).replace("\"version\"", "\"evil\":1,\"version\""), "animations/in/a.json", inAnim("a")), "unknown key");
        rejects(fails, dir, "manifest version 0", zip("pack.json", manifest("p1", 0), "animations/in/a.json", inAnim("a")), "version");
        rejects(fails, dir, "manifest version 1.5", zip("pack.json", manifest("p1", 1).replace("\"version\":1", "\"version\":1.5"), "animations/in/a.json", inAnim("a")), "version");
        rejects(fails, dir, "file name != id", zip("pack.json", manifest("p1", 1), "animations/in/a.json", inAnim("b")), "file name");
        rejects(fails, dir, "folder != direction", zip("pack.json", manifest("p1", 1), "animations/out/a.json", inAnim("a")), "folder");
        rejects(fails, dir, "invalid animation", zip("pack.json", manifest("p1", 1), "animations/in/a.json", inAnim("a").replace("0.4", "9")), "offsetX");
        rejects(fails, dir, "same id in both folders", zip("pack.json", manifest("p1", 1), "animations/in/a.json", inAnim("a"),
                "animations/out/a.json", "{\"schema\":1,\"id\":\"a\",\"direction\":\"out\",\"channels\":{\"opacity\":{\"kind\":\"constant\",\"value\":0.5}}}"), "twice");
        rejects(fails, dir, "mirror of unknown base", zip("pack.json", manifest("p1", 1), "animations/out/a.json", outMirror("a", "does_not_exist")), "does_not_exist");
        rejects(fails, dir, "id shadows a built-in", zip("pack.json", manifest("p1", 1), "animations/in/unfold.json", inAnim("unfold")), "built-in");
        // a second pack may not take an id another pack owns
        rejects(fails, dir, "id owned by another pack", zip("pack.json", manifest("p2", 1), "animations/in/pop_in.json", inAnim("pop_in")), "already used");

        // limits: counted on bytes actually read
        Object[] many = new Object[2 + 2 * 33];
        many[0] = "pack.json"; many[1] = manifest("p1", 1);
        for (int i = 0; i < 33; i++) { many[2 + 2 * i] = "animations/in/a" + i + ".json"; many[3 + 2 * i] = inAnim("a" + i); }
        rejects(fails, dir, "33 animations", zip(many), "more than");
        byte[] big = new byte[600 * 1024]; Arrays.fill(big, (byte) 'x');
        rejects(fails, dir, "entry over 512 KB", zip("pack.json", manifest("p1", 1), "animations/in/a.json", big), "larger than");
        byte[] zeros = new byte[100 * 1024 * 1024];                      // compresses to ~100 KB: a classic zip bomb
        long t0 = System.currentTimeMillis();
        rejects(fails, dir, "zip bomb (100 MB of zeros)", zip("pack.json", manifest("p1", 1), "animations/in/a.json", zeros), "larger than");
        if (System.currentTimeMillis() - t0 > 20000) fails.add("zip bomb took too long to reject");
        Object[] total = new Object[2 + 2 * 10];
        total[0] = "pack.json"; total[1] = manifest("p1", 1);
        byte[] half = new byte[500 * 1024];
        for (int i = 0; i < 10; i++) { total[2 + 2 * i] = "animations/in/t" + i + ".json"; total[3 + 2 * i] = half; }
        rejects(fails, dir, "5 MB unpacked in total", zip(total), "when unpacked");
        Random rnd = new Random(1);
        Object[] noise = new Object[2 + 2 * 6];
        noise[0] = "pack.json"; noise[1] = manifest("p1", 1);
        for (int i = 0; i < 6; i++) { byte[] b = new byte[400 * 1024]; rnd.nextBytes(b); noise[2 + 2 * i] = "animations/in/n" + i + ".json"; noise[3 + 2 * i] = b; }
        rejects(fails, dir, "2.4 MB of incompressible data", zip(noise), "larger than");
        Object[] dirs = new Object[2 + 2 * 85];
        dirs[0] = "pack.json"; dirs[1] = manifest("p1", 1);
        for (int i = 0; i < 85; i++) { dirs[2 + 2 * i] = "animations/in/e" + i + ".json"; dirs[3 + 2 * i] = inAnim("e" + i); }
        rejects(fails, dir, "too many entries (85 files)", zip(dirs), "too many");

        // 6. an out animation may mirror a BUILT-IN in animation; a UTF-8 BOM (Windows editors) is tolerated
        String bom = "\uFEFF";
        ClipAnimationPacks.InstallResult m = ClipAnimationPacks.install(dir, new ByteArrayInputStream(zip(
                "pack.json", bom + manifest("mirror-pack", 1), "animations/out/unfold_rev.json", bom + outMirror("unfold_rev", "unfold"))));
        ClipAnimation rev = ClipAnimationLoader.get("unfold_rev", ClipAnimation.Direction.OUT);
        if (rev == null || !rev.isReversed() || m.pack.outIds.size() != 1) fails.add("mirror of a built-in / BOM pack failed");

        // 7. uninstall: registry + disk, input checks, built-ins untouched
        ClipAnimationPacks.uninstall(dir, "mirror-pack");
        if (ClipAnimationLoader.get("unfold_rev") != null || new File(dir, "mirror-pack").exists()) fails.add("uninstall must remove animations and files");
        if (ClipAnimationLoader.get("unfold") == null) fails.add("uninstall must never touch built-ins");
        for (String bad : new String[]{"../slide-pack", "nope", "", "A B"}) {
            try { ClipAnimationPacks.uninstall(dir, bad); fails.add("uninstall('" + bad + "') should fail"); } catch (ClipAnimationPacks.PackException expected) { }
        }
        try { ClipAnimationPacks.uninstall(dir, null); fails.add("uninstall(null) should fail"); } catch (ClipAnimationPacks.PackException expected) { }
        if (!new File(dir, "slide-pack").isDirectory()) fails.add("a rejected uninstall removed something");

        // 8. at load time an id that is already taken (e.g. a later app version added a built-in with it) is skipped, not replaced
        ClipAnimationLoader.clearForTests();
        ClipAnimationLoader.register(unfoldJson.replace("\"id\": \"unfold\"", "\"id\": \"pop_in\""), "new-builtin.json", true, ClipAnimation.Direction.IN);
        List<String> clash = ClipAnimationPacks.loadInstalled(dir);
        ClipAnimation pop = ClipAnimationLoader.get("pop_in");
        if (pop == null || !ClipAnimationLoader.isBuiltIn("pop_in") || pop.channels().size() != 7) fails.add("a pack must never replace a built-in at load time");
        if (clash.isEmpty()) fails.add("the clash with a built-in should be reported");
        if (ClipAnimationLoader.get("slide_in") == null) fails.add("the rest of the pack should still load");

        deleteTree(dir);
        return fails;
    }

    private static void deleteTree(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteTree(x);
        f.delete();
    }

    public static void main(String[] args) throws Exception {
        List<String> fails = runAll();
        if (fails.isEmpty()) System.out.println("PACK CHECKS PASSED");
        else {
            for (String f : fails) System.out.println("FAIL: " + f);
            System.exit(1);
        }
    }
}
