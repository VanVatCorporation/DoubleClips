package com.vanvatcorporation.doubleclips.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen.TemplateData;
import com.vanvatcorporation.doubleclips.activities.main.TemplateGridAdapter;
import com.vanvatcorporation.doubleclips.helper.TemplateApi;
import com.vanvatcorporation.doubleclips.helper.TemplateFeedSource;
import com.vanvatcorporation.doubleclips.helper.TemplatePager;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Profile → Liked templates / Saved templates: the signed-in account's own list, newest first, paged from the server.
 * Opening a card plays the list in the same swipeable preview as the Templates tab, continuing where this screen stopped.
 */
public class TemplateCollectionActivity extends AppCompatActivityImpl {
    private static final int HANDOFF_MAX = 100;
    private static final int PREFETCH_DISTANCE = 6;

    private TemplateFeedSource source;
    private TemplateGridAdapter adapter;
    private TemplatePager pager;
    private SwipeRefreshLayout refresh;
    private TextView emptyText;

    private final TemplateApi.StateListener stateListener = id -> {
        if (adapter != null) adapter.notifyTemplateChanged(id);
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_template_collection);

        source = (TemplateFeedSource) (createrBundle == null ? null : createrBundle.getSerializable("TemplateFeedSource"));
        if (source == null) source = TemplateFeedSource.liked();

        ((TextView) findViewById(R.id.collectionTitle)).setText(
                source.kind == TemplateFeedSource.Kind.BOOKMARKED ? R.string.template_collection_saved
                        : source.kind == TemplateFeedSource.Kind.MINE ? R.string.template_collection_mine
                        : R.string.template_collection_liked);
        findViewById(R.id.collectionBackButton).setOnClickListener(v -> finish());

        refresh = findViewById(R.id.collectionSwipeRefresh);
        emptyText = findViewById(R.id.collectionEmptyText);
        RecyclerView recycler = findViewById(R.id.collectionRecyclerView);

        final StaggeredGridLayoutManager layoutManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        recycler.setLayoutManager(layoutManager);

        adapter = new TemplateGridAdapter(this, this::openPreview);
        recycler.setAdapter(adapter);

        pager = new TemplatePager(this, new TemplatePager.Listener() {
            @Override public void onReset() { adapter.clear(); emptyText.setVisibility(View.GONE); }
            @Override public void onPage(List<TemplateData> items, boolean hasMore) {
                adapter.addAll(items);
                updateEmpty();
            }
            @Override public void onLoading(boolean loading) { if (!loading) refresh.setRefreshing(false); }
            @Override public void onError(String message) {
                refresh.setRefreshing(false);
                Toast.makeText(TemplateCollectionActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });

        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                int[] last = layoutManager.findLastVisibleItemPositions(null);
                int max = 0;
                for (int p : last) max = Math.max(max, p);
                if (max >= adapter.getItemCount() - PREFETCH_DISTANCE) pager.loadNext();
            }
        });

        refresh.setOnRefreshListener(this::reload);
        reload();
    }

    private void reload() {
        refresh.setRefreshing(true);
        pager.start(source);
    }

    private void updateEmpty() {
        emptyText.setVisibility(adapter.getItemCount() == 0 && !pager.hasMore() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        TemplateApi.addStateListener(stateListener);
    }

    @Override
    protected void onStop() {
        TemplateApi.removeStateListener(stateListener);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the preview: whatever was unliked / unsaved there no longer belongs in this list.
        // Only the Liked / Saved lists lose cards on return; "My templates" stays as it is.
        if (adapter != null && source.kind != TemplateFeedSource.Kind.MINE) {
            final boolean liked = source.kind == TemplateFeedSource.Kind.LIKED;
            adapter.retain(d -> liked ? d.isLiked : d.isBookmarked);
            updateEmpty();
        }
    }

    private void openPreview(List<TemplateData> items, int position) {
        Intent intent = new Intent(this, TemplatePreviewActivity.class);
        int end = Math.min(items.size(), position + HANDOFF_MAX);
        TemplateData[] handoff = new ArrayList<>(items.subList(position, end)).toArray(new TemplateData[0]);
        intent.putExtra("TemplateDataList", handoff);
        intent.putExtra("TemplateFeedSource", source);
        intent.putExtra("TemplateNextCursor", end == items.size() ? pager.getCursor() : null);
        startActivity(intent);
    }
}
