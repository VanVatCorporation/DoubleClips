package com.vanvatcorporation.doubleclips.assets;

import com.vanvatcorporation.doubleclips.AssetLibrary;
import com.vanvatcorporation.drivekit.AssetKind;

import java.io.File;

/**
 * Where downloaded media and fonts go when the Assets browser is opened from the editor: straight into the open project (no
 * project picker), as on iOS and desktop. The main Assets tab has no target: effects and packs are still installed, fonts go into
 * a project the user picks, and the other media are saved as a file.
 */
public interface AssetTarget {
    /** The open project's title, for the button ("Add to My Video"). */
    String projectTitle();

    /**
     * Adds a downloaded video / image / audio / font to the project. Called on a worker thread; the editor refreshes its own lists.
     * @return a short line for the user ("Added to the project files")
     */
    String add(File file, AssetKind kind) throws AssetLibrary.InstallException;

    /** An effect or an animation pack was just installed in the app: refresh whatever lists show them. Called on the main thread. */
    default void onInstalled(AssetKind kind) { }
}
