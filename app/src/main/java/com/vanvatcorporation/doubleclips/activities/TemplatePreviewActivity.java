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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

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


    OkHttpClient httpsClient = new OkHttpClient();




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


        dataList = Arrays.asList((TemplateAreaScreen.TemplateData[]) Objects.requireNonNull(createrBundle.getSerializable("TemplateDataList")));


        mediaLoadingIcon = findViewById(R.id.mediaLoadingIcon);
        mediaPausedIcon = findViewById(R.id.mediaPausedIcon);
        mediaPlaybackErrorIcon = findViewById(R.id.mediaPlaybackErrorIcon);

        commentLayout = findViewById(R.id.commentLayout);


        mediaViewPager = findViewById(R.id.mediaViewPager);

        adapter = new VideoAdapter(dataList);
        mediaViewPager.setAdapter(adapter);

        mediaViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                setupMediaPlayer(position);
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

        setupSpecificEdit();
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
        // Simulate dynamic loading
//        dataList.add("https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny.mp4");
//        dataList.add("https://storage.googleapis.com/exoplayer-test-media-0/ElephantsDream.mp4");
        adapter.notifyDataSetChanged();
    }

    void setupMediaPlayer(int position) {


        try {
            mediaLoadingIcon.setVisibility(View.VISIBLE);
            View view = Objects.requireNonNull(((RecyclerView) mediaViewPager.getChildAt(0)).findViewHolderForAdapterPosition(position)).itemView;
            SurfaceView surfaceView = view.findViewById(R.id.previewSurfaceView);
            ImageView thumbnailView = view.findViewById(R.id.thumbnailView);
            TextView durationClipCount = view.findViewById(R.id.durationClipCount);

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

                // TODO: Research a way to register postSeen() on video completion/half completion.

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
        pausePlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumePlayer();
    }









    public void postToggleLike(String templateId, RunnableImpl runnable)
    {
        if(AuthRepository.getInstance(this).getCurrentUser() == null) {
            new AlertDialog.Builder(this).setTitle("Login required").setMessage("Please login to like this template").create().show();
            return;
        }
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("username", AuthRepository.getInstance(this).getCurrentUser().getUsername());
            jsonObject.put("templateId", templateId);
            postJson("https://app.vanvatcorp.com/doubleclips/api/toggle-like", jsonObject.toString(), runnable);
        } catch (JSONException e) {
            LoggingManager.LogExceptionToNoteOverlay(TemplatePreviewActivity.this, e);
        }
    }
    public void postToggleBookmark(String templateId, RunnableImpl runnable)
    {
        if(AuthRepository.getInstance(this).getCurrentUser() == null) {
            new AlertDialog.Builder(this).setTitle("Login required").setMessage("Please login to bookmark this template").create().show();
            return;
        }
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("username", AuthRepository.getInstance(this).getCurrentUser().getUsername());
            jsonObject.put("templateId", templateId);
            postJson("https://app.vanvatcorp.com/doubleclips/api/toggle-bookmark", jsonObject.toString(), runnable);
        } catch (JSONException e) {
            LoggingManager.LogExceptionToNoteOverlay(TemplatePreviewActivity.this, e);
        }
    }
    public void postSeenTemplate(String templateId, RunnableImpl runnable)
    {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("username", "viet2007ht");
            jsonObject.put("templateId", templateId);
            postJson("https://app.vanvatcorp.com/doubleclips/api/seen-template", jsonObject.toString(), runnable);
        } catch (JSONException e) {
            LoggingManager.LogExceptionToNoteOverlay(TemplatePreviewActivity.this, e);
        }
    }

    private void postJson(String url, String json, RunnableImpl runnable) {
        // Create request body
        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));

        // Build request
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        // Execute asynchronously (important for Android)
        httpsClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                LoggingManager.LogExceptionToNoteOverlay(TemplatePreviewActivity.this, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        LoggingManager.LogToToast(TemplatePreviewActivity.this, "Error: " + response.code());
                    } else {
                        // Read response body
                        String responseData = response.body().string();
                        //LoggingManager.LogToToast(TemplatePreviewActivity.this, responseData);
                        if(runnable != null)
                        {
                            runnable.runWithParam(responseData);
                        }
                    }
                } finally {
                    response.close();
                }
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
            holder.commentCount.setText("" + data.getComments().size());//NumberHelper.abbreviateNumber(Random.Range(1, 300000))); // data.clipCount
            holder.bookmarkCount.setText("" + data.getBookmarkCount());//NumberHelper.abbreviateNumber(Random.Range(1, 500000))); // data.clipCount

            holder.heartButton.setOnClickListener(v -> {

                postToggleLike(data.getTemplateId(), new RunnableImpl() {

                    @Override
                    public <T> void runWithParam(T param) {
                        try {
                            boolean isLike = new JSONObject((String) param).getBoolean("isLiked");

                            data.setHeartCount(data.getHeartCount() + (isLike ? 1 : -1));
                            holder.heartCount.setText("" + data.getHeartCount());
                            holder.heartButton.setImageResource(isLike ? R.drawable.baseline_favorite_24 : R.drawable.baseline_favorite_border_24);
                        } catch (JSONException e) {
                            LoggingManager.LogExceptionToNoteOverlay(TemplatePreviewActivity.this, e);
                        }
                    }
                });
            });
            holder.commentButton.setOnClickListener(v -> {
                commentsAreaScreen.open();
//                data.getComments().add(new TemplateAreaScreen.TemplateData.TemplateComment());
//                holder.commentCount.setText("" + data.getComments().size());

            });
            holder.bookmarkButton.setOnClickListener(v -> {

                postToggleBookmark(data.getTemplateId(), new RunnableImpl() {

                    @Override
                    public <T> void runWithParam(T param) {
                        try {
                            boolean isBookmark = new JSONObject((String) param).getBoolean("isBookmarked");

                            data.setBookmarkCount(data.getBookmarkCount() + (isBookmark ? 1 : -1));
                            holder.bookmarkCount.setText("" + data.getBookmarkCount());
                            holder.bookmarkButton.setImageResource(isBookmark ? R.drawable.baseline_bookmark_24 : R.drawable.baseline_bookmark_border_24);
                        } catch (JSONException e) {
                            LoggingManager.LogExceptionToNoteOverlay(TemplatePreviewActivity.this, e);
                        }
                    }
                });
            });


            holder.previewSurfaceView.setOnClickListener(v -> {
                if(exoPlayer.getExoPlayer() != null) {
                    if (exoPlayer.getExoPlayer().isPlaying())
                        pausePlayer();
                    else
                        resumePlayer();
                }
            });



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

}
