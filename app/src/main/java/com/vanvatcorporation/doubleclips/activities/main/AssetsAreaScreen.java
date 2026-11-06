package com.vanvatcorporation.doubleclips.activities.main;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.assets.AssetDetailSheet;
import com.vanvatcorporation.doubleclips.assets.AssetSearchFragment;
import com.vanvatcorporation.doubleclips.assets.AssetSearchHints;
import com.vanvatcorporation.doubleclips.assets.AssetsBrowserView;
import com.vanvatcorporation.doubleclips.assets.AssetsSession;
import com.vanvatcorporation.doubleclips.assets.MyAssetsSheet;
import com.vanvatcorporation.doubleclips.assets.PostAssetSheet;
import com.vanvatcorporation.doubleclips.impl.TrendingTickerView;

/**
 * The Assets tab (it replaced the Search tab): the community's videos, images, audio, fonts, effects and animation packs, live from
 * the server and shared through Van Vat Drive. Nothing is requested until the tab is first shown.
 */
public class AssetsAreaScreen extends BaseAreaScreen {
    public AssetsBrowserView browser;
    private TrendingTickerView searchTicker;
    private AssetDetailSheet.ProjectsProvider projectsProvider;

    /** The hot searches changed: the bar's ticker takes the new ones. */
    private final Runnable hintsListener = () -> {
        if (searchTicker != null) searchTicker.setTerms(AssetSearchHints.getInstance().trendingTexts());
    };

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

        // The bar is a button: the hot searches take turns inside it, a tap opens the search window.
        searchTicker = findViewById(R.id.assetsSearchTicker);
        searchTicker.setHint(getContext().getString(R.string.assets_search_hint));
        searchTicker.setTerms(AssetSearchHints.getInstance().trendingTexts());
        findViewById(R.id.assetsSearchBar).setOnClickListener(v -> openSearch());
        findViewById(R.id.assetsPostButton).setOnClickListener(v -> {
            FragmentActivity activity = activity();
            if (activity == null || !signedIn()) return;
            PostAssetSheet.show(activity, () -> browser.start());       // a new post shows up in the list right away
        });
        findViewById(R.id.assetsMineButton).setOnClickListener(v -> {
            FragmentActivity activity = activity();
            if (activity == null || !signedIn()) return;
            MyAssetsSheet.show(activity, () -> browser.start());        // link changes may have added or removed assets
        });
    }

    private boolean signedIn() {
        if (AssetsSession.hasSession(getContext())) return true;
        Toast.makeText(getContext(), R.string.assets_sign_in_first, Toast.LENGTH_SHORT).show();
        return false;
    }

    private FragmentActivity activity() {
        Context c = getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof FragmentActivity) return (FragmentActivity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** The projects a font can go into (the tab has no open project). */
    public void setProjectsProvider(AssetDetailSheet.ProjectsProvider provider) {
        projectsProvider = provider;
        if (browser != null) browser.setProjectsProvider(provider);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AssetSearchHints.getInstance().addListener(hintsListener);
        AssetSearchHints.getInstance().refresh(getContext(), false);   // the ticker's hot searches
    }

    @Override
    protected void onDetachedFromWindow() {
        AssetSearchHints.getInstance().removeListener(hintsListener);
        super.onDetachedFromWindow();
    }

    /** Opens the search window. The hot search showing in the bar is its prompt, and the titles on screen back up its suggestions. */
    private void openSearch() {
        FragmentActivity activity = activity();
        if (activity == null) return;
        AssetSearchFragment.show(activity, searchTicker.currentTerm(), browser.titles(100), null, projectsProvider);
    }

    /** MainActivity calls this when the tab becomes visible. */
    public void onShown() {
        if (browser != null) browser.ensureLoaded();
    }
}
