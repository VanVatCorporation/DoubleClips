package com.vanvatcorporation.doubleclips.assets;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.vanvatcorporation.doubleclips.R;

/**
 * The editor's Assets sheet (iOS: the Assets sheet next to Files; desktop: AssetsWindow): the same live community list as the tab,
 * opened over the editor. What you get goes straight into the open project ({@link AssetTarget}).
 */
public final class AssetsSheet {
    private AssetsSheet() { }

    public static void show(Activity activity, AssetTarget target) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_assets_browser, null);
        ((TextView) root.findViewById(R.id.assetsSheetTitle)).setText(activity.getString(R.string.assets_editor_title, target.projectTitle()));
        AssetsBrowserView browser = root.findViewById(R.id.assetsSheetBrowser);
        browser.setTarget(target);
        // the search window opens over the sheet; what is got there goes into the same project
        root.findViewById(R.id.assetsSheetSearch).setOnClickListener(v -> {
            if (activity instanceof FragmentActivity) {
                AssetSearchFragment.show((FragmentActivity) activity, null, browser.titles(100), target, null);
            }
        });

        // a tall sheet: the list needs the room (a sheet that wraps its content would be as small as its first page)
        int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.88f);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        dialog.setContentView(root);
        dialog.setOnShowListener(d -> {
            BottomSheetBehavior<?> behavior = dialog.getBehavior();
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        });
        dialog.show();
        browser.ensureLoaded();
    }
}
