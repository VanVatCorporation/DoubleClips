package com.vanvatcorporation.doubleclips.activities.main;

import android.content.Context;
import android.util.AttributeSet;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.assets.AssetDetailSheet;
import com.vanvatcorporation.doubleclips.assets.AssetsBrowserView;

/**
 * The Assets tab (it replaced the Search tab): the community's videos, images, audio, fonts, effects and animation packs, live from
 * the server and shared through Van Vat Drive. Nothing is requested until the tab is first shown.
 */
public class AssetsAreaScreen extends BaseAreaScreen {
    public AssetsBrowserView browser;

    public AssetsAreaScreen(Context context) {
        super(context);
    }

    public AssetsAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AssetsAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AssetsAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void init() {
        super.init();
        browser = findViewById(R.id.assetsBrowser);
    }

    /** The projects a font can go into (the tab has no open project). */
    public void setProjectsProvider(AssetDetailSheet.ProjectsProvider provider) {
        if (browser != null) browser.setProjectsProvider(provider);
    }

    /** MainActivity calls this when the tab becomes visible. */
    public void onShown() {
        if (browser != null) browser.ensureLoaded();
    }
}
