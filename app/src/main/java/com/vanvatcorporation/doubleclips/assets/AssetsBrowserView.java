package com.vanvatcorporation.doubleclips.assets;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.drivekit.AssetKind;
import com.vanvatcorporation.drivekit.AssetsClient;
import com.vanvatcorporation.drivekit.CommunityAsset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The community assets, live from the server (desktop: AssetsBrowser, iOS: AssetsView): kind chips, Newest / Most downloaded, a grid
 * that pages in as you scroll, pull to refresh. Used by the Assets tab and by the editor's Assets sheet (which sets an
 * {@link AssetTarget}). Browsing needs no sign-in. A card opens {@link AssetDetailSheet}.
 * <p>
 * Nothing is loaded until {@link #ensureLoaded()} (the tab asks when it becomes visible, so the app start makes no request for it).
 */
public class AssetsBrowserView extends LinearLayout {

    /** Start the next page when this few cards are left below the last visible one. */
    private static final int PREFETCH_DISTANCE = 8;
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    private final Handler ui = new Handler(Looper.getMainLooper());

    private AssetTarget target;
    private AssetDetailSheet.ProjectsProvider projectsProvider;
    private Runnable onPageLoaded;

    private ChipGroup chipGroup;
    private MaterialButton sortButton;
    private SwipeRefreshLayout swipe;
    private RecyclerView recycler;
    private TextView status;
    private AssetsGridAdapter adapter;

    private final List<CommunityAsset> items = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();

    private AssetKind kind;                       // null = every kind
    private String sort = AssetsClient.SORT_NEW;
    private String query = "";
    private String cursor;
    private boolean hasMore = true, loading, failed, started;
    private String errorText;
    /** Bumped at every restart: the answer of an older request is dropped. */
    private int generation;

    public AssetsBrowserView(Context context) {
        super(context);
        init();
    }

    public AssetsBrowserView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AssetsBrowserView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        LayoutInflater.from(getContext()).inflate(R.layout.view_assets_browser, this, true);
        chipGroup = findViewById(R.id.assetsChipGroup);
        sortButton = findViewById(R.id.assetsSortButton);
        swipe = findViewById(R.id.assetsSwipe);
        recycler = findViewById(R.id.assetsRecycler);
        status = findViewById(R.id.assetsStatus);

        buildChips();
        updateSortText();
        sortButton.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(getContext(), sortButton);
            menu.getMenu().add(0, 0, 0, R.string.assets_sort_new);
            menu.getMenu().add(0, 1, 1, R.string.assets_sort_popular);
            menu.setOnMenuItemClickListener(item -> {
                String chosen = item.getItemId() == 1 ? AssetsClient.SORT_POPULAR : AssetsClient.SORT_NEW;
                if (!chosen.equals(sort)) {
                    sort = chosen;
                    updateSortText();
                    start();
                }
                return true;
            });
            menu.show();
        });

        final GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 2);
        recycler.setLayoutManager(layoutManager);
        adapter = new AssetsGridAdapter(getContext(), this::openDetail);
        recycler.setAdapter(adapter);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                failed = false;
                if (layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - PREFETCH_DISTANCE) loadNext();
            }
        });
        swipe.setOnRefreshListener(this::start);
        status.setOnClickListener(v -> { if (failed) start(); });
    }

    private void buildChips() {
        addChip(getContext().getString(R.string.assets_kind_all), null, true);
        for (AssetKind k : AssetKind.values()) addChip(AssetUi.chipTitle(getContext(), k), k, false);
    }

    private void addChip(String text, AssetKind chipKind, boolean selected) {
        Chip chip = (Chip) LayoutInflater.from(getContext()).inflate(R.layout.chip_asset_filter, chipGroup, false);
        chip.setId(View.generateViewId());
        chip.setText(text);
        chip.setChecked(selected);
        chip.setOnCheckedChangeListener((button, checked) -> {
            if (!checked || kind == chipKind) return;
            kind = chipKind;
            start();
        });
        chipGroup.addView(chip);
    }

    private void updateSortText() {
        sortButton.setText(AssetsClient.SORT_POPULAR.equals(sort) ? R.string.assets_sort_popular : R.string.assets_sort_new);
    }

    // ---- outside controls --------------------------------------------------------------------------------

    /** Where media and fonts go (the editor's project), or null in the tab. */
    public void setTarget(AssetTarget target) { this.target = target; }

    /** The projects a font can be added to from the tab (the tab has no open project). */
    public void setProjectsProvider(AssetDetailSheet.ProjectsProvider provider) { this.projectsProvider = provider; }

    /** Runs (on the main thread) after each page arrives. */
    public void setOnPageLoaded(Runnable r) { onPageLoaded = r; }

    /** Search words (matched on title, tags and description); "" = everything. */
    public void setQuery(String text) {
        query = text == null ? "" : text.trim();
        start();
    }

    /** The titles of the first {@code max} assets shown. */
    public List<String> titles(int max) {
        List<String> out = new ArrayList<>();
        for (CommunityAsset a : items) {
            if (out.size() >= max) break;
            out.add(a.title);
        }
        return out;
    }

    /** Loads the first page the first time it is called (the screen is on show now). */
    public void ensureLoaded() {
        if (!started) start();
    }

    // ---- loading -----------------------------------------------------------------------------------------

    /** Starts over from the first page (a new filter, sort or search, or pull to refresh). */
    public void start() {
        started = true;
        generation++;
        items.clear();
        ids.clear();
        adapter.setItems(items);
        cursor = null;
        hasMore = true;
        loading = false;
        failed = false;
        errorText = null;
        updateStatus();
        loadNext();
    }

    private void loadNext() {
        if (loading || !hasMore || !started) return;
        loading = true;
        failed = false;
        updateStatus();
        final int gen = generation;
        final AssetKind askedKind = kind;
        final String askedQuery = query, askedSort = sort, askedCursor = cursor;
        final AssetsClient client = AssetsSession.get(getContext());
        IO.execute(() -> {
            try {
                AssetsClient.Page page = client.list(askedKind, askedQuery, askedSort, askedCursor);
                ui.post(() -> onPage(gen, page));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> onError(gen, message));
            }
        });
    }

    private void onPage(int gen, AssetsClient.Page page) {
        if (gen != generation) return;           // a newer list was started meanwhile
        loading = false;
        swipe.setRefreshing(false);
        for (CommunityAsset a : page.items) {
            if (ids.add(a.id)) items.add(a);
        }
        cursor = page.next;
        hasMore = page.next != null && !page.items.isEmpty();   // an empty page never asks for another
        adapter.setItems(items);
        updateStatus();
        if (onPageLoaded != null) onPageLoaded.run();
        // a tall screen can show a whole page and still have room: ask for the next one
        recycler.post(() -> {
            GridLayoutManager lm = (GridLayoutManager) recycler.getLayoutManager();
            if (lm != null && hasMore && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - PREFETCH_DISTANCE) loadNext();
        });
    }

    private void onError(int gen, String message) {
        if (gen != generation) return;
        loading = false;
        failed = true;
        swipe.setRefreshing(false);
        errorText = getContext().getString(R.string.assets_error_retry, message);
        updateStatus();
    }

    private void updateStatus() {
        String text = null;
        if (errorText != null) text = errorText;
        else if (loading && items.isEmpty()) text = getContext().getString(R.string.assets_loading);
        else if (items.isEmpty() && !hasMore) {
            text = getContext().getString(query.isEmpty() ? R.string.assets_none : R.string.assets_none_found);
        }
        status.setText(text == null ? "" : text);
        status.setVisibility(text == null ? GONE : VISIBLE);
    }

    // ---- opening one -------------------------------------------------------------------------------------

    private void openDetail(CommunityAsset asset) {
        FragmentActivity activity = activity();
        if (activity == null) return;
        AssetDetailSheet.show(activity, asset, target, projectsProvider);
    }

    private FragmentActivity activity() {
        Context c = getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof FragmentActivity) return (FragmentActivity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
