package com.vanvatcorporation.doubleclips;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Android adapter for the plain-Java animation classes: finds the animation files and gives them to
 * {@link ClipAnimationLoader} / {@link ClipAnimationPacks}.
 *
 * Bundled animations live in {@code src/main/assets/animations/in/*.json} ("in" animations) and
 * {@code animations/out/*.json} ("out" animations); the folder a file is in must match the direction it
 * declares. Installed packs live in {@code <app files>/animation_packs/<packId>/}.
 * Everything else (parsing, validation, the registry, pack handling) is plain Java, so the desktop port only
 * needs its own small version of this class.
 */
public final class ClipAnimationAssets {

    public static final String ASSET_DIR = "animations";
    public static final String PACKS_DIR = "animation_packs";

    private static boolean builtInsLoaded = false;
    private static boolean packsLoaded = false;

    private ClipAnimationAssets() {}

    /** Loads the bundled animations, then the installed packs. Idempotent; this is what the app calls. */
    public static synchronized List<String> loadAll(Context context) {
        List<String> problems = new ArrayList<>(loadBuiltIns(context));
        problems.addAll(loadInstalledPacks(context));
        return problems;
    }

    public static File packsDir(Context context) {
        return new File(context.getFilesDir(), PACKS_DIR);
    }

    /** One bundled file waiting to be loaded. */
    private static final class Pending {
        final String path;       // asset path, e.g. animations/in/unfold.json
        final String label;      // for messages, e.g. in/unfold.json
        final ClipAnimation.Direction expected;

        Pending(String path, String label, ClipAnimation.Direction expected) {
            this.path = path;
            this.label = label;
            this.expected = expected;
        }
    }

    /**
     * Loads every bundled animation into the registry. Safe to call as often as you like (it only does
     * the work once it has found the folders). Returns human-readable problems, one per bad file - an empty
     * list means everything loaded. A bad file never stops the others from loading.
     */
    public static synchronized List<String> loadBuiltIns(Context context) {
        List<String> errors = new ArrayList<>();
        if (builtInsLoaded) return errors;

        AssetManager assets = context.getAssets();
        List<Pending> pending = new ArrayList<>();
        try {
            String[] root = assets.list(ASSET_DIR);
            if (root != null) {
                for (String n : root) {
                    if (n.endsWith(".json")) {
                        errors.add(n + ": put animation files in " + ASSET_DIR + "/in/ or " + ASSET_DIR + "/out/ - not loaded");
                    }
                }
            }
            for (ClipAnimation.Direction dir : ClipAnimation.Direction.values()) {
                String folder = ASSET_DIR + "/" + dir.json;
                String[] names = assets.list(folder);
                if (names == null) continue;
                Arrays.sort(names);
                for (String n : names) {
                    if (n.endsWith(".json")) pending.add(new Pending(folder + "/" + n, dir.json + "/" + n, dir));
                }
            }
        } catch (IOException e) {
            errors.add("can't list assets/" + ASSET_DIR + ": " + e.getMessage());
            return errors;
        }
        if (pending.isEmpty()) {
            errors.add("assets/" + ASSET_DIR + "/in and /out are missing or empty - no animations available");
            return errors;
        }

        // Files are independent except "mirrorOf" ones, which need their base registered first (an "out"
        // animation often mirrors an "in" one). Rather than work out an order, keep retrying the ones that
        // were only waiting on a base until a whole pass makes no progress.
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            List<Pending> stillPending = new ArrayList<>();
            for (Pending p : pending) {
                try {
                    ClipAnimationLoader.register(readAsset(assets, p.path), p.label, true, p.expected);
                    progress = true;
                } catch (ClipAnimationLoader.FormatException e) {
                    if (e.missingMirrorBase) {
                        stillPending.add(p);
                    } else {
                        errors.add(e.getMessage()); // permanently bad: report once, don't retry
                        progress = true;
                    }
                } catch (IOException e) {
                    errors.add(p.label + ": can't read file: " + e.getMessage());
                    progress = true;
                }
            }
            pending = stillPending;
        }
        for (Pending p : pending) errors.add(p.label + ": its \"mirrorOf\" animation was never loaded");

        builtInsLoaded = true;
        return errors;
    }

    /** Loads the installed animation packs (after the built-ins). Idempotent; installs/removals keep the registry current themselves. */
    public static synchronized List<String> loadInstalledPacks(Context context) {
        if (packsLoaded) return new ArrayList<>();
        loadBuiltIns(context); // pack ids are checked against the built-ins
        packsLoaded = true;
        return ClipAnimationPacks.loadInstalled(packsDir(context));
    }

    /** Installs the animation pack .zip the user picked. Throws a PackException whose message is fit to show. */
    public static synchronized ClipAnimationPacks.InstallResult importPack(Context context, Uri uri)
            throws ClipAnimationPacks.PackException {
        loadAll(context); // so the new pack is checked against everything already installed
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new ClipAnimationPacks.PackException("Couldn't open that file.");
            return ClipAnimationPacks.install(packsDir(context), in);
        } catch (IOException e) {
            throw new ClipAnimationPacks.PackException("Couldn't read that file: " + e.getMessage());
        }
    }

    public static synchronized List<ClipAnimationPacks.PackInfo> installedPacks(Context context) {
        loadAll(context);
        return ClipAnimationPacks.listInstalled(packsDir(context));
    }

    public static synchronized void removePack(Context context, String packId) throws ClipAnimationPacks.PackException {
        loadAll(context);
        ClipAnimationPacks.uninstall(packsDir(context), packId);
    }

    private static String readAsset(AssetManager assets, String path) throws IOException {
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            // read one byte past the limit so an oversized file is rejected by the loader, not truncated into valid JSON
            int limit = ClipAnimationLoader.MAX_JSON_CHARS * 4 + 1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) break;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
