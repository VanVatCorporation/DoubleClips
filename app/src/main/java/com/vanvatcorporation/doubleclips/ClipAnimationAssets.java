package com.vanvatcorporation.doubleclips;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Android adapter for {@link ClipAnimationLoader}: loads the animations bundled in
 * {@code src/main/assets/animations/*.json}. Everything else (parsing, validation,
 * the registry) is plain Java in the loader, so the desktop port only needs its own
 * 20-line version of this class.
 */
public final class ClipAnimationAssets {

    public static final String ASSET_DIR = "animations";

    private static boolean builtInsLoaded = false;

    private ClipAnimationAssets() {}

    /**
     * Loads every bundled animation into the registry. Safe to call as often as you like
     * (it only does the work once it has found the folder). Returns human-readable problems,
     * one per bad file - an empty list means everything loaded. A bad file never stops the
     * others from loading.
     */
    public static synchronized List<String> loadBuiltIns(Context context) {
        List<String> errors = new ArrayList<>();
        if (builtInsLoaded) return errors;

        AssetManager assets = context.getAssets();
        String[] names;
        try {
            names = assets.list(ASSET_DIR);
        } catch (IOException e) {
            errors.add("can't list assets/" + ASSET_DIR + ": " + e.getMessage());
            return errors;
        }
        if (names == null || names.length == 0) {
            errors.add("assets/" + ASSET_DIR + " is missing or empty - no animations available");
            return errors;
        }
        Arrays.sort(names);

        // Files are independent except "mirrorOf" ones, which need their base registered first.
        // Rather than parse order out of file names, keep retrying the ones that were only
        // waiting on a base until a whole pass makes no progress.
        List<String> pending = new ArrayList<>();
        for (String n : names) if (n.endsWith(".json")) pending.add(n);
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            List<String> stillPending = new ArrayList<>();
            for (String n : pending) {
                String path = ASSET_DIR + "/" + n;
                try {
                    ClipAnimationLoader.register(readAsset(assets, path), n, true);
                    progress = true;
                } catch (ClipAnimationLoader.FormatException e) {
                    if (e.missingMirrorBase) stillPending.add(n);
                    else progress = true; // permanently bad: report once, don't retry
                    if (!e.missingMirrorBase) errors.add(e.getMessage());
                } catch (IOException e) {
                    errors.add(n + ": can't read file: " + e.getMessage());
                    progress = true;
                }
            }
            pending = stillPending;
        }
        // whatever is still waiting on a base that never appeared
        for (String n : pending) errors.add(n + ": its \"mirrorOf\" animation was never loaded");

        builtInsLoaded = true;
        return errors;
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
