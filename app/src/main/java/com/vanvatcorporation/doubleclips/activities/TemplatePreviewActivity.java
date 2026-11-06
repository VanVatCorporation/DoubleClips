package com.vanvatcorporation.doubleclips.activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.gson.Gson;
import com.vanvatcorporation.doubleclips.ExoPlayerExtended;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.export.VideoPropertiesExportSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen;
import com.vanvatcorporation.doubleclips.activities.template.CommentsAreaScreen;
import com.vanvatcorporation.doubleclips.dynamiclibs.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.helper.TemplateApi;
import com.vanvatcorporation.doubleclips.helper.TemplateFeedSource;
import com.vanvatcorporation.doubleclips.helper.TemplatePager;
import com.vanvatcorporation.doubleclips.helper.AlgorithmHelper;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.helper.ImageHelper;
import com.vanvatcorporation.doubleclips.helper.ParserHelper;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;
import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import com.vanvatcorporation.doubleclips.TemplateTimelineStripView;
import com.vanvatcorporation.doubleclips.TemplateTimelineLoader;
import com.vanvatcorporation.doubleclips.TemplateTimelineInfo;
import android.os.Looper;
import android.os.Handler;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@UnstableApi
public class TemplatePreviewActivity extends AppCompatActivityImpl {

    ViewPager2 mediaViewPager;
    private VideoAdapter adapter;
    private List<TemplateAreaScreen.TemplateData> dataList;
    ExoPlayerExtended exoPlayer;
    ProgressBar mediaLoadingIcon;
    ImageView mediaPausedIcon, mediaPlaybackErrorIcon;

    RelativeLayout commentLayout;
    CommentsAreaScreen commentsAreaScreen;



    private static SimpleCache simpleCache;
    public static SimpleCache getSimpleCache() {
        return simpleCache;
    }


    /** Continues the list the user came from (feed / search / liked / saved) when they swipe near the end. */
    private TemplatePager templatePager;
    /** Templates whose view was already reported from this screen (the server dedupes too; this saves the request). */
    private final java.util.Set<String> viewReported = new HashSet<>();

    // ===========================       TEMPLATE STRIP       ====================================
    /** The strip of the page that is on screen; follows the player's clock. */
    private TemplateTimelineStripView activeStrip;
    private final Handler stripHandler = new Handler(Looper.getMainLooper());
    private boolean stripScrubbing, resumeAfterScrub;
    private final Runnable stripTicker = new Runnable() {
        @Override public void run() {
            if (activeStrip != null && exoPlayer != null && exoPlayer.getExoPlayer() != null && !stripScrubbing) {
                activeStrip.setCurrentTime(exoPlayer.getExoPlayer().getCurrentPosition() / 1000.0);
            }
            stripHandler.postDelayed(this, 33);
        }
    };

    private void bindActiveStrip(TemplateTimelineStripView strip) {
        activeStrip = strip;
        stripScrubbing = false;
        if (strip == null) return;
        strip.setListener(new TemplateTimelineStripView.Listener() {
            @Override public void onScrub(double timeSeconds) {
                if (exoPlayer == null || exoPlayer.getExoPlayer() == null) return;
                if (!stripScrubbing) {
                    stripScrubbing = true;
                    resumeAfterScrub = exoPlayer.getExoPlayer().isPlaying();
                    exoPlayer.getExoPlayer().pause();
                }
                exoPlayer.getExoPlayer().seekTo((long) (timeSeconds * 1000));
            }
            @Override public void onScrubEnd() {
                stripScrubbing = false;
                if (resumeAfterScrub && exoPlayer != null && exoPlayer.getExoPlayer() != null) exoPlayer.getExoPlayer().play();
            }
        });
        stripHandler.removeCallbacks(stripTicker);
        stripHandler.post(stripTicker);
    }

    // ===========================       TEMPLATE STRIP       ====================================




    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_template_preview);



        File cacheDir = new File(getCacheDir(), "media");
        long cacheSize = 100 * 1024 * 1024; // 100 MB


        simpleCache = new SimpleCache(
                cacheDir,
                new LeastRecentlyUsedCacheEvictor(cacheSize),
                new StandaloneDatabaseProvider(this)
        );


        dataList = new ArrayList<>(Arrays.asList((TemplateAreaScreen.TemplateData[]) Objects.requireNonNull(createrBundle.getSerializable("TemplateDataList"))));
        for (TemplateAreaScreen.TemplateData d : dataList) TemplateApi.overlay(this, d);   // newest numbers the app knows


        mediaLoadingIcon = findViewById(R.id.mediaLoadingIcon);
        mediaPausedIcon = findViewById(R.id.mediaPausedIcon);
        mediaPlaybackErrorIcon = findViewById(R.id.mediaPlaybackErrorIcon);

        commentLayout = findViewById(R.id.commentLayout);


        mediaViewPager = findViewById(R.id.mediaViewPager);

        adapter = new VideoAdapter(dataList);
        mediaViewPager.setAdapter(adapter);

        TemplateFeedSource feedSource = (TemplateFeedSource) createrBundle.getSerializable("TemplateFeedSource");
        if (feedSource != null) {
            templatePager = new TemplatePager(this, new TemplatePager.Listener() {
                @Override public void onReset() { }
                @Override public void onLoading(boolean loading) { }
                @Override public void onError(String message) { }
                @Override public void onPage(java.util.List<TemplateAreaScreen.TemplateData> items, boolean hasMore) {
                    int start = dataList.size();
                    dataList.addAll(items);
                    adapter.notifyItemRangeInserted(start, items.size());
                }
            });
            java.util.List<String> have = new ArrayList<>();
            for (TemplateAreaScreen.TemplateData d : dataList) have.add(d.getTemplateId());
            templatePager.resume(feedSource, createrBundle.getString("TemplateNextCursor"), have);
        }

        mediaViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                setupMediaPlayer(position);
                reportView(position);
                // Load more when near end
                if (position >= dataList.size() - 2) {
                    loadMoreVideos();
                }
            }
        });

        exoPlayer = new ExoPlayerExtended(this);
        exoPlayer.getExoPlayer().setRepeatMode(Player.REPEAT_MODE_ONE);

        findViewById(R.id.backButton).setOnClickListener(v -> {
            finish();
        });
        findViewById(R.id.moreButton).setOnClickListener(this::showMoreMenu);

        setupSpecificEdit();
    }


    /** The "..." menu: Hide this template. */
    private void showMoreMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, R.string.template_hide);
        menu.setOnMenuItemClickListener(item -> {
            hideCurrentTemplate();
            return true;
        });
        menu.show();
    }

    /** Asks, then hides the template on this phone and closes the preview (the list underneath drops its card). */
    private void hideCurrentTemplate() {
        int position = mediaViewPager.getCurrentItem();
        if (position < 0 || position >= dataList.size()) return;
        final TemplateAreaScreen.TemplateData data = dataList.get(position);
        String title = data.getTemplateTitle();
        String name = title == null || title.trim().isEmpty() ? getString(R.string.template_hide_fallback_name) : title.trim();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.template_hide_title, name))
                .setMessage(R.string.template_hide_message)
                .setPositiveButton(R.string.template_hide_confirm, (dialog, which) -> {
                    com.vanvatcorporation.doubleclips.helper.HiddenTemplates.getInstance(this).hide(data);
                    finish();
                })
                .setNegativeButton(R.string.assets_cancel, null)
                .show();
    }

    private void setupSpecificEdit() {


        // ===========================       COMMENT ZONE       ====================================
        commentsAreaScreen = (CommentsAreaScreen) LayoutInflater.from(this).inflate(R.layout.view_template_comments, null);
        commentLayout.addView(commentsAreaScreen);
        // ===========================       COMMENT ZONE       ====================================


        // ===========================       COMMENT ZONE       ====================================

        commentsAreaScreen.onClose.add(() -> {


        });
        commentsAreaScreen.onOpen.add(() -> {
            TemplateAreaScreen.TemplateData data = adapter.getTemplateData(mediaViewPager.getCurrentItem());
            commentsAreaScreen.fetchComments(data);
        });


        // ===========================       COMMENT ZONE       ====================================


    }



    void adjustMaximumAspectRatio(int width, int height, SurfaceView previewSurfaceView)
    {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        // Extract width and height in pixels
        int displayWidth = displayMetrics.widthPixels;
        int displayHeight = displayMetrics.heightPixels;

        int[] res = AlgorithmHelper.calculateTargetRatio(displayWidth, displayHeight, width, height);

        ViewGroup.LayoutParams surfaceParams = previewSurfaceView.getLayoutParams();
        surfaceParams.width = res[0];
        surfaceParams.height = res[1];
        previewSurfaceView.setLayoutParams(surfaceParams);
    }


    private void loadMoreVideos() {
        if (templatePager != null) templatePager.loadNext();
    }

    /** The template on this page came on screen: tell the server (it counts once per viewer per 30 minutes). */
    private void reportView(int position) {
        if (position < 0 || position >= dataList.size()) return;
        String id = dataList.get(position).getTemplateId();
        if (viewReported.add(id)) TemplateApi.postView(this, id);
    }

    void setupMediaPlayer(int position) {


        try {
            mediaLoadingIcon.setVisibility(View.VISIBLE);
            View view = Objects.requireNonNull(((RecyclerView) mediaViewPager.getChildAt(0)).findViewHolderForAdapterPosition(position)).itemView;
            SurfaceView surfaceView = view.findViewById(R.id.previewSurfaceView);
            ImageView thumbnailView = view.findViewById(R.id.thumbnailView);
            TextView durationClipCount = view.findViewById(R.id.durationClipCount);
            bindActiveStrip(view.findViewById(R.id.templateStrip));

            SurfaceView previousSurfaceView = exoPlayer.getVideoSurfaceView();
            if(previousSurfaceView != null) {
                previousSurfaceView.setVisibility(View.GONE);
            }
            exoPlayer.setVideoSurfaceView(surfaceView);
            exoPlayer.getVideoSurfaceView().setVisibility(View.VISIBLE);

            ImageView previousThumbnailView = exoPlayer.getThumbnailView();
            if(previousThumbnailView != null) {
                previousThumbnailView.setVisibility(View.VISIBLE);
            }
            exoPlayer.setThumbnailView(thumbnailView);


            CacheDataSource.Factory cacheDataSourceFactory =
                    new CacheDataSource.Factory()
                            .setCache(getSimpleCache())
                            .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory())
                            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

            MediaSource mediaSource = new ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(dataList.get(position).getTemplateVideoLink()));

            exoPlayer.getExoPlayer().setMediaSource(mediaSource);
            exoPlayer.getExoPlayer().setVolume(1);


            // Add listener for errors
            exoPlayer.getExoPlayer().addListener(new Player.Listener() {
                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    LoggingManager.LogToToast(TemplatePreviewActivity.this, error.getMessage());
                    mediaLoadingIcon.setVisibility(View.VISIBLE);
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        // Equivalent to MediaPlayer's onPrepared
                        // Player is ready to start playback
                        durationClipCount.setText(DateHelper.convertTimestampToMMSSFormat(exoPlayer.getExoPlayer().getDuration()));
                        dataList.get(position).setTemplateDuration(exoPlayer.getExoPlayer().getDuration());
                        TemplateTimelineStripView page = view.findViewById(R.id.templateStrip);
                        if (page != null && (page.getInfo() == null || page.getInfo().timeline == null) && !dataList.get(position).hasTimeline()) {
                            page.setInfo(TemplateTimelineInfo.legacy(dataList.get(position), exoPlayer.getExoPlayer().getDuration() / 1000.0));
                        }

                        mediaLoadingIcon.setVisibility(View.GONE);
                        mediaPlaybackErrorIcon.setVisibility(View.GONE);
                    }
                    if (state == Player.STATE_BUFFERING) {
                        mediaLoadingIcon.setVisibility(View.VISIBLE);
                        mediaPlaybackErrorIcon.setVisibility(View.GONE);
                    }
                    if (state == Player.STATE_IDLE) {
                        mediaPlaybackErrorIcon.setVisibility(View.GONE);
                        mediaLoadingIcon.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onVideoSizeChanged(VideoSize videoSize) {
                    Player.Listener.super.onVideoSizeChanged(videoSize);
                    adjustMaximumAspectRatio(videoSize.width, videoSize.height, surfaceView);
                }

                @Override
                public void onRenderedFirstFrame() {
                    exoPlayer.getThumbnailView().setVisibility(View.GONE);
                }

                // The view is reported when the page is selected (reportView); completion-based views are a later idea.

            });

            exoPlayer.getExoPlayer().setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .setUsage(C.USAGE_MEDIA)
                            .build(),
                    true
            );


            // Prepare and play
            exoPlayer.getExoPlayer().prepare();
            exoPlayer.getExoPlayer().setPlayWhenReady(true);


        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(this, e);
        }

    }

    public void resumePlayer() {
        try {
            if (exoPlayer.getExoPlayer() != null) {
                exoPlayer.getExoPlayer().play();
                mediaPausedIcon.setVisibility(View.GONE);

                //mediaLoadingIcon.setVisibility(View.GONE);
            }
        } catch (IllegalStateException e) {
            LoggingManager.LogExceptionToNoteOverlay(this, e);
        }
    }
    public void pausePlayer() {
        try {
            if (exoPlayer.getExoPlayer() != null) {
                exoPlayer.getExoPlayer().pause();
                mediaPausedIcon.setVisibility(View.VISIBLE);
            }
        } catch (IllegalStateException e) {
            LoggingManager.LogExceptionToNoteOverlay(this, e);
        }
    }


    @Override
    public void finish() {
        super.finish();
        stripHandler.removeCallbacks(stripTicker);
        activeStrip = null;

        if (exoPlayer.getExoPlayer() != null) {
            exoPlayer.release();
        }
        if(simpleCache != null)
        {
            simpleCache.release();
            simpleCache = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exoPlayer.getExoPlayer() != null) {
            exoPlayer.release();
        }
        if(simpleCache != null)
        {
            simpleCache.release();
            simpleCache = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stripHandler.removeCallbacks(stripTicker);
        pausePlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activeStrip != null) { stripHandler.removeCallbacks(stripTicker); stripHandler.post(stripTicker); }
        resumePlayer();
    }









    /** Likes need an account; the old dialog, shown once, instead of a silent no-op. */
    private boolean requireLogin(String what) {
        if (TemplateApi.viewer(this) != null) return true;
        new AlertDialog.Builder(this).setTitle(R.string.login_required).setMessage("Please login to " + what + " this template").create().show();
        return false;
    }

    /**
     * Like / unlike: the screen answers instantly, the request carries the wanted state (so a retry or a double tap cannot
     * undo itself), and the server's numbers then replace ours: no local +1 / -1 arithmetic left to drift.
     */
    private void setLike(VideoAdapter.VideoViewHolder holder, TemplateAreaScreen.TemplateData data, boolean want) {
        final boolean before = data.isLiked;
        final int heartBefore = data.getHeartCount();
        data.isLiked = want;
        data.setHeartCount(Math.max(0, heartBefore + (want == before ? 0 : (want ? 1 : -1))));
        updateHeartUI(holder, data);

        TemplateApi.setLike(this, data.getTemplateId(), want, new TemplateApi.ApiCallback<TemplateApi.State>() {
            @Override public void onSuccess(TemplateApi.State state) {
                state.applyTo(data);
                updateHeartUI(holder, data);
                updateBookmarkUI(holder, data);
            }
            @Override public void onError(String message) {
                data.isLiked = before;
                data.setHeartCount(heartBefore);
                updateHeartUI(holder, data);
                LoggingManager.LogToToast(TemplatePreviewActivity.this, message);
            }
        });
    }

    private void setBookmark(VideoAdapter.VideoViewHolder holder, TemplateAreaScreen.TemplateData data, boolean want) {
        final boolean before = data.isBookmarked;
        final int bookmarkBefore = data.getBookmarkCount();
        data.isBookmarked = want;
        data.setBookmarkCount(Math.max(0, bookmarkBefore + (want == before ? 0 : (want ? 1 : -1))));
        updateBookmarkUI(holder, data);

        TemplateApi.setBookmark(this, data.getTemplateId(), want, new TemplateApi.ApiCallback<TemplateApi.State>() {
            @Override public void onSuccess(TemplateApi.State state) {
                state.applyTo(data);
                updateHeartUI(holder, data);
                updateBookmarkUI(holder, data);
            }
            @Override public void onError(String message) {
                data.isBookmarked = before;
                data.setBookmarkCount(bookmarkBefore);
                updateBookmarkUI(holder, data);
                LoggingManager.LogToToast(TemplatePreviewActivity.this, message);
            }
        });
    }

    public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {
        private List<TemplateAreaScreen.TemplateData> templateData;

        public VideoAdapter(List<TemplateAreaScreen.TemplateData> templateData) {
            this.templateData = templateData;
        }

        @NonNull
        @Override
        public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.pager_template_preview_component, parent, false);
            return new VideoViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
            // SurfaceView is prepared here, but ExoPlayer will be attached in Activity

            TemplateAreaScreen.TemplateData data = templateData.get(position);

//            setupMediaPlayer(position);



            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    Bitmap thumbnailBitmap = data.getCacheThumbnailBitmap(holder.itemView.getContext());

                    if(thumbnailBitmap != null)
                    {
                        holder.itemView.post(() -> {
                            holder.thumbnailView.setImageBitmap(thumbnailBitmap);
                        });
                    }
                } catch (Exception e) {
                    LoggingManager.LogExceptionToNoteOverlay(holder.itemView.getContext(), e);
                }
            });




            holder.usernameText.setText("@" + data.getTemplateAuthor());
            holder.replacementClipCount.setText("" + data.getTemplateClipCount());

            holder.heartCount.setText("" + data.getHeartCount());//NumberHelper.abbreviateNumber(Random.Range(1, 80000000))); // data.clipCount
            holder.commentCount.setText("" + Math.max(data.getCommentCount(), data.getComments().size()));//NumberHelper.abbreviateNumber(Random.Range(1, 300000))); // data.clipCount
            holder.bookmarkCount.setText("" + data.getBookmarkCount());//NumberHelper.abbreviateNumber(Random.Range(1, 500000))); // data.clipCount

            // 1. Initial State - Heart
            updateHeartUI(holder, data);

            // 2. Initial State - Bookmark
            updateBookmarkUI(holder, data);


            holder.heartButton.setOnClickListener(v -> {
                if (!requireLogin("like")) return;
                setLike(holder, data, !data.isLiked);
            });

            holder.commentButton.setOnClickListener(v -> {
                commentsAreaScreen.open();
            });

            holder.bookmarkButton.setOnClickListener(v -> {
                if (!requireLogin("bookmark")) return;
                setBookmark(holder, data, !data.isBookmarked);
            });


            holder.previewSurfaceView.setOnClickListener(v -> {
                if(exoPlayer.getExoPlayer() != null) {
                    if (exoPlayer.getExoPlayer().isPlaying())
                        pausePlayer();
                    else
                        resumePlayer();
                }
            });



            // The template's timeline for the strip: drawn from the clip count first, replaced when the real one arrives.
            TemplateTimelineStripView strip = holder.itemView.findViewById(R.id.templateStrip);
            if (strip != null) {
                strip.setInfo(TemplateTimelineInfo.legacy(data, 0));
                if (data.hasTimeline()) {
                    final android.content.Context appContext = holder.itemView.getContext().getApplicationContext();
                    Executors.newSingleThreadExecutor().execute(() -> {
                        try {
                            TemplateTimelineInfo info = TemplateTimelineLoader.load(appContext, data);
                            holder.itemView.post(() -> {
                                if (holder.getAdapterPosition() != position) return; // the page was recycled
                                strip.setInfo(info);
                                holder.replacementClipCount.setText(info.slots.size() + " clips");
                            });
                        } catch (java.io.IOException | RuntimeException e) {
                            // keep the clip-count strip; the template still works as an old one
                            android.util.Log.w("TemplateStrip", "timeline not loaded: " + e.getMessage());
                        }
                    });
                }
            }

            holder.itemView.findViewById(R.id.useTemplateButton).setOnClickListener(v -> {
                Intent intent = new Intent(holder.itemView.getContext(), TemplateExportActivity.class);
                intent.putExtra("TemplateData", data);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return templateData.size();
        }

        public TemplateAreaScreen.TemplateData getTemplateData(int position) {
            return templateData.get(position);
        }

        public class VideoViewHolder extends RecyclerView.ViewHolder {


            ImageView thumbnailView;
            SurfaceView previewSurfaceView;
            TextView usernameText, replacementClipCount, durationClipCount;
            TextView heartCount, commentCount, bookmarkCount;
            ImageButton heartButton, commentButton, bookmarkButton;

            public VideoViewHolder(@NonNull View itemView) {
                super(itemView);
                thumbnailView = itemView.findViewById(R.id.thumbnailView);
                previewSurfaceView = itemView.findViewById(R.id.previewSurfaceView);
                usernameText = itemView.findViewById(R.id.usernameText);
                replacementClipCount = itemView.findViewById(R.id.replacementClipCount);
                durationClipCount = itemView.findViewById(R.id.durationClipCount);
                heartCount = itemView.findViewById(R.id.heartCount);
                commentCount = itemView.findViewById(R.id.commentCount);
                bookmarkCount = itemView.findViewById(R.id.bookmarkCount);

                heartButton = itemView.findViewById(R.id.heartButton);
                commentButton = itemView.findViewById(R.id.commentButton);
                bookmarkButton = itemView.findViewById(R.id.bookmarkButton);


            }
        }
        }

        private void updateHeartUI(VideoAdapter.VideoViewHolder holder, TemplateAreaScreen.TemplateData data) {
            holder.heartCount.setText("" + data.getHeartCount());
            if (data.isLiked) {
                holder.heartButton.setImageResource(R.drawable.baseline_favorite_24);
                holder.heartButton.setColorFilter(holder.itemView.getContext().getColor(R.color.red));
            } else {
                holder.heartButton.setImageResource(R.drawable.baseline_favorite_border_24);
                holder.heartButton.setColorFilter(holder.itemView.getContext().getColor(R.color.white));
            }
        }

        private void updateBookmarkUI(VideoAdapter.VideoViewHolder holder, TemplateAreaScreen.TemplateData data) {
            holder.bookmarkCount.setText("" + data.getBookmarkCount());
            if (data.isBookmarked) {
                holder.bookmarkButton.setImageResource(R.drawable.baseline_bookmark_24);
                holder.bookmarkButton.setColorFilter(holder.itemView.getContext().getColor(R.color.ios_yellow));
            } else {
                holder.bookmarkButton.setImageResource(R.drawable.baseline_bookmark_border_24);
                holder.bookmarkButton.setColorFilter(holder.itemView.getContext().getColor(R.color.white));
            }
        }

}
