package com.vanvatcorporation.doubleclips.activities.main;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.TemplatePreviewActivity;
import com.vanvatcorporation.doubleclips.helper.ImageHelper;
import com.vanvatcorporation.doubleclips.helper.TemplateApi;
import com.vanvatcorporation.doubleclips.helper.TemplateSearchHints;
import com.vanvatcorporation.doubleclips.impl.TrendingTickerView;
import com.vanvatcorporation.doubleclips.activities.TemplateSearchActivity;
import com.vanvatcorporation.doubleclips.helper.TemplateFeedSource;
import com.vanvatcorporation.doubleclips.helper.TemplatePager;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The Templates tab: the signed-in account's suggestion feed (server side ranking), loaded a page at a time as the user
 * scrolls. Typing in the search box switches to a server-side search of the same shape; clearing it returns to the feed.
 * Pull to refresh starts a new feed session.
 */
public class TemplateAreaScreen extends BaseAreaScreen {
    /** The search bar: a button whose text is the hot-search ticker. */
    public View searchBar;
    public TrendingTickerView searchTicker;
    public RecyclerView templateRecyclerView;
    public TemplateGridAdapter templateAdapter;
    public SwipeRefreshLayout templateSwipeRefreshLayout;

    /** The preview pager gets at most this many templates in the Intent; it loads the rest itself. */
    private static final int HANDOFF_MAX = 100;
    /** Start the next page when this few rows are left below the last visible one. */
    private static final int PREFETCH_DISTANCE = 6;

    private TemplatePager pager;

    private final TemplateApi.StateListener stateListener = id -> {
        if (templateAdapter != null) templateAdapter.notifyTemplateChanged(id);
    };

    private final Runnable hintsListener = () -> {
        if (searchTicker != null) searchTicker.setTerms(TemplateSearchHints.getInstance().trendingTexts());
    };


    public TemplateAreaScreen(Context context) {
        super(context);
    }

    public TemplateAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TemplateAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TemplateAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    public void init() {
        super.init();

        searchBar = findViewById(R.id.searchBar);
        searchTicker = findViewById(R.id.searchTicker);
        templateRecyclerView = findViewById(R.id.templateRecyclerView);
        templateSwipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        final StaggeredGridLayoutManager layoutManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        templateRecyclerView.setLayoutManager(layoutManager);

        templateAdapter = new TemplateGridAdapter(getContext(), this::openPreview);
        templateRecyclerView.setAdapter(templateAdapter);

        pager = new TemplatePager(getContext(), new TemplatePager.Listener() {
            @Override public void onReset() {
                templateAdapter.clear();
            }
            @Override public void onPage(List<TemplateData> items, boolean hasMore) {
                templateAdapter.addAll(items);
            }
            @Override public void onLoading(boolean loading) {
                if (!loading) templateSwipeRefreshLayout.setRefreshing(false);
            }
            @Override public void onError(String message) {
                templateSwipeRefreshLayout.setRefreshing(false);
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });

        // Infinite scroll: ask for the next page a few rows before the end.
        templateRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                int[] last = layoutManager.findLastVisibleItemPositions(null);
                int max = 0;
                for (int p : last) max = Math.max(max, p);
                if (max >= templateAdapter.getItemCount() - PREFETCH_DISTANCE) pager.loadNext();
            }
        });

        // The bar is a button: the hot searches take turns inside it, a tap opens the search window.
        searchTicker.setHint(getContext().getString(R.string.template_search_hint));
        searchTicker.setTerms(TemplateSearchHints.getInstance().trendingTexts());
        searchBar.setOnClickListener(v -> openSearch());

        templateSwipeRefreshLayout.setOnRefreshListener(this::reload);

        reload();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TemplateApi.addStateListener(stateListener);
        TemplateSearchHints.getInstance().addListener(hintsListener);
        TemplateSearchHints.getInstance().refresh(getContext(), false);   // the ticker's hot searches
        if (templateAdapter != null) templateAdapter.refreshAll();   // a preview may have changed things while we were away
    }

    @Override
    protected void onDetachedFromWindow() {
        TemplateApi.removeStateListener(stateListener);
        TemplateSearchHints.getInstance().removeListener(hintsListener);
        super.onDetachedFromWindow();
    }

    /** Starts over: a new feed session. (Searching happens in the search window.) */
    public void reload() {
        templateSwipeRefreshLayout.setRefreshing(true);
        pager.start(TemplateFeedSource.feed());
    }

    /** Opens the search window. The hot search showing in the bar is its prompt, and the titles on screen back up its suggestions. */
    private void openSearch() {
        Intent intent = new Intent(getContext(), TemplateSearchActivity.class);
        String hot = searchTicker.currentTerm();
        if (hot != null) intent.putExtra(TemplateSearchActivity.EXTRA_PLACEHOLDER, hot);
        ArrayList<String> titles = new ArrayList<>();
        for (TemplateData d : templateAdapter.getItems()) {
            String title = d.getTemplateTitle();
            if (title != null && !title.trim().isEmpty() && titles.size() < 100) titles.add(title.trim());
        }
        intent.putStringArrayListExtra(TemplateSearchActivity.EXTRA_TITLES, titles);
        getContext().startActivity(intent);
    }

    private void openPreview(List<TemplateData> items, int position) {
        @SuppressLint("UnsafeOptInUsageError") Intent intent = new Intent(getContext(), TemplatePreviewActivity.class);
        int end = Math.min(items.size(), position + HANDOFF_MAX);
        TemplateData[] handoff = new ArrayList<>(items.subList(position, end)).toArray(new TemplateData[0]);
        intent.putExtra("TemplateDataList", handoff);
        intent.putExtra("TemplateFeedSource", pager.getSource());
        // The preview keeps loading where this screen stopped, but only if it was handed everything loaded so far.
        intent.putExtra("TemplateNextCursor", end == items.size() ? pager.getCursor() : null);
        getContext().startActivity(intent);
    }



    public static class TemplateData implements Serializable {

        public String version;
        private String templateAuthor;
        private String templateId;
        private String templateTitle;
        private String templateDescription;
        private String ffmpegCommand;
        private String templateSnapshotLink;
        private String templateVideoLink;
        private long templateTimestamp;
        private long templateDuration;
        private int templateTotalClip;
        private String[] additionalResourceName;
        /** URL of the template's timeline JSON (same format as a project's timeline, possibly wrapped). Null/empty = the old, ffmpeg-only kind. */
        private String templateTimelineLink;
        /** Folder URL (ends with "/") the template's resource files live in: additionalResourceName[i] is at templateContentLink + name. */
        private String templateContentLink;
        /** Server says whether ffmpegCommand can render this template. Null = older server: assume yes when there is a command. */
        private Boolean ffmpegAvailable;
        private int viewCount;
        private int useCount;
        private int heartCount;
        /** Comments on the template, as the server counts them (the list in `comments` is only filled once the panel is opened). */
        private int commentCount;
        private ArrayList<TemplateComment> comments = new ArrayList<>();
        public boolean isLiked;
        public boolean isBookmarked;
        private int bookmarkCount;


        private transient Bitmap cacheThumbnailBitmap;
        private transient Bitmap cacheAuthorAvatarBitmap;

//        public TemplateData(String templateAuthor, String templateId, String templateTitle, String templateDescription, String ffmpegCommand, String templateSnapshotLink, String templateVideoLink, long templateTimestamp, long templateDuration, int templateTotalClip, String[] additionalResourceName) {
//            this.templateAuthor = templateAuthor;
//            this.templateId = templateId;
//            this.templateTitle = templateTitle;
//            this.templateDescription = templateDescription;
//            this.ffmpegCommand = ffmpegCommand;
//            this.templateSnapshotLink = templateSnapshotLink;
//            this.templateVideoLink = templateVideoLink;
//            this.templateTimestamp = templateTimestamp;
//            this.templateDuration = templateDuration;
//            this.templateTotalClip = templateTotalClip;
//            this.additionalResourceName = additionalResourceName;
//        }


        public String getTemplateAuthor() {
            return templateAuthor;
        }
        public String getTemplateId() {
            return templateId;
        }
        public String getTemplateTitle() {
            return templateTitle;
        }
        public String getTemplateDescription() {
            return templateDescription;
        }
        public String getFfmpegCommand() {
            return ffmpegCommand;
        }
        public String getTemplateTimelineLink() { return templateTimelineLink == null ? "" : templateTimelineLink.trim(); }
        public boolean hasTimeline() { return !getTemplateTimelineLink().isEmpty(); }
        public String getTemplateContentLink() { return templateContentLink; }
        /** FFmpeg can render it: the server says so, or (older server) there is a command. */
        public boolean isFfmpegAvailable() {
            if (ffmpegAvailable != null) return ffmpegAvailable;
            return ffmpegCommand != null && !ffmpegCommand.trim().isEmpty();
        }
        /**
         * Folder URL (ends with "/") of the template's resource files: the server's templateContentLink, else derived from the
         * preview video's address (.../{user}/{id}/preview.mp4 -> .../{user}/{id}/content/).
         */
        public String getResolvedContentLink() {
            String given = templateContentLink == null ? "" : templateContentLink.trim();
            if (!given.isEmpty()) return given.endsWith("/") ? given : given + "/";
            if (templateVideoLink == null || templateVideoLink.trim().isEmpty()) return "";
            String v = templateVideoLink.trim();
            int slash = v.lastIndexOf('/');
            return slash < 0 ? "" : v.substring(0, slash + 1) + "content/";
        }
        public String getTemplateSnapshotLink() {
            return templateSnapshotLink;
        }
        public String getTemplateVideoLink() {
            return templateVideoLink;
        }
        public long getTemplateTimestamp() {
            return templateTimestamp;
        }
        public long getTemplateDuration() {
            return templateDuration;
        }
        public int getTemplateClipCount() {
            return templateTotalClip;
        }
        public String[] getTemplateAdditionalResourcesName() {
            return additionalResourceName;
        }

        public int getViewCount() {
            return viewCount;
        }
        public int getUseCount() {
            return useCount;
        }
        public int getHeartCount() {
            return heartCount;
        }
        public ArrayList<TemplateComment> getComments() {
            if(comments == null) comments = new ArrayList<>();
            return comments;
        }
        public int getBookmarkCount() {
            return bookmarkCount;
        }
        public int getCommentCount() {
            return commentCount;
        }
        /** Applies the numbers the server last reported (see TemplateApi.State). */
        public void setStats(int view, int use, int heart, int bookmark, int comment) {
            viewCount = view;
            useCount = use;
            heartCount = heart;
            bookmarkCount = bookmark;
            commentCount = comment;
        }


        public String getTemplateLocation() {
            return "/" + templateAuthor + "/" + templateId;
        }

        public Bitmap getCacheThumbnailBitmap(Context context) throws ExecutionException, InterruptedException {
            if(cacheThumbnailBitmap == null)
            {
                Future<Bitmap> bitmapFuture = Executors.newSingleThreadExecutor().submit(() -> {
                    try {
                        Bitmap thumbnailBitmap = ImageHelper.getImageBitmapFromNetwork(context, getTemplateSnapshotLink());

                        cacheThumbnailBitmap = thumbnailBitmap;
                        return thumbnailBitmap;
                    } catch (Exception e) {
                        LoggingManager.LogExceptionToNoteOverlay(context, e);
                        return null;
                    }
                });

                return bitmapFuture.get();
            }
            else return cacheThumbnailBitmap;
        }

        public Bitmap getCacheAuthorAvatarBitmap(Context context) throws ExecutionException, InterruptedException {
            if(cacheAuthorAvatarBitmap == null)
            {
                Future<Bitmap> bitmapFuture = Executors.newSingleThreadExecutor().submit(() -> {
                    try {
                        Bitmap thumbnailBitmap = ImageHelper.getImageBitmapFromNetwork(context, "https://account.vanvatcorp.com/api/avatar/9da1e7af-25f8-5543-8a56-5c69f8143e0f");

                        cacheAuthorAvatarBitmap = thumbnailBitmap;
                        return thumbnailBitmap;
                    } catch (Exception e) {
                        LoggingManager.LogExceptionToNoteOverlay(context, e);
                        return null;
                    }
                });

                return bitmapFuture.get();
            }
            else return cacheAuthorAvatarBitmap;
        }


        public void setTemplateDuration(long amount) {
            templateDuration = amount;
        }
        public void setFfmpegCommand(String cmd) {
            ffmpegCommand = cmd;
        }
        public void setHeartCount(int amount) {
            heartCount = amount;
        }
        public void addComment(TemplateComment comment) {
            comments.add(comment);
        }
        public void setBookmarkCount(int amount) {
            bookmarkCount = amount;
        }


        public static class TemplateComment {
            public String username;
            public String content;
            public long commentTimestamp;
            public int heartCount;
            TemplateComment[] replies;

            public TemplateComment() {

            }
        }

    }
}
