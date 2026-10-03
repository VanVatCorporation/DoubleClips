package com.vanvatcorporation.doubleclips.activities.editing;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import com.vanvatcorporation.doubleclips.ClipAnimationAssets;
import com.vanvatcorporation.doubleclips.ClipAnimationPacks;

import java.util.List;

/**
 * The "Animation packs" dialog: lists the installed packs, lets the user import a new .zip pack or
 * remove an installed one. All the real work (validation, install, removal) is in ClipAnimationPacks;
 * this only shows it.
 */
public final class AnimationPackDialog {

    private AnimationPackDialog() {}

    /**
     * @param launchImport opens the system file picker for a pack .zip (the caller owns the result launcher)
     * @param onChanged    called after a pack was removed, so the animation pickers can refresh
     */
    public static void show(Activity activity, Runnable launchImport, Runnable onChanged) {
        List<ClipAnimationPacks.PackInfo> packs = ClipAnimationAssets.installedPacks(activity);
        AlertDialog.Builder b = new AlertDialog.Builder(activity).setTitle("Animation packs");
        if (packs.isEmpty()) {
            b.setMessage("No animation packs installed.\n\nA pack is a .zip with a pack.json and animation .json files. "
                    + "Import one to add more in / out animations to the pickers.");
        } else {
            String[] labels = new String[packs.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = describe(packs.get(i));
            b.setItems(labels, (dialog, which) -> confirmRemove(activity, packs.get(which), onChanged));
        }
        b.setPositiveButton("Import pack...", (dialog, which) -> launchImport.run())
                .setNegativeButton("Close", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private static String describe(ClipAnimationPacks.PackInfo p) {
        StringBuilder sb = new StringBuilder(p.name);
        if (!p.damaged) sb.append("  v").append(p.version);
        if (!p.author.isEmpty()) sb.append("  \u00b7  ").append(p.author);
        sb.append("\n");
        if (p.damaged) {
            sb.append("Unreadable - tap to remove");
        } else {
            sb.append(p.animationCount()).append(p.animationCount() == 1 ? " animation" : " animations");
            if (!p.inIds.isEmpty()) sb.append("  \u00b7  in: ").append(join(p.inIds));
            if (!p.outIds.isEmpty()) sb.append("  \u00b7  out: ").append(join(p.outIds));
        }
        return sb.toString();
    }

    private static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) sb.append(i == 0 ? "" : ", ").append(ids.get(i));
        return sb.toString();
    }

    private static void confirmRemove(Activity activity, ClipAnimationPacks.PackInfo pack, Runnable onChanged) {
        new AlertDialog.Builder(activity)
                .setTitle("Remove \"" + pack.name + "\"?")
                .setMessage("Clips that use its animations keep them selected but will export without them until the pack is installed again.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    try {
                        ClipAnimationAssets.removePack(activity, pack.id);
                        Toast.makeText(activity, "Removed " + pack.name, Toast.LENGTH_SHORT).show();
                        onChanged.run();
                    } catch (ClipAnimationPacks.PackException e) {
                        new AlertDialog.Builder(activity).setTitle("Couldn't remove the pack").setMessage(e.getMessage()).show();
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .show();
    }
}
