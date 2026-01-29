package com.vanvatcorporation.doubleclips.activities;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
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
import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.DefaultDatabaseProvider;
import androidx.media3.database.ExoDatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.LoopingMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen;
import com.vanvatcorporation.doubleclips.externalUtils.Random;
import com.vanvatcorporation.doubleclips.helper.AlgorithmHelper;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.helper.NumberHelper;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;

@UnstableApi
public class TemplatePreviewActivity extends AppCompatActivityImpl {
    TemplateAreaScreen.TemplateData data;

    private SurfaceView previewSurfaceView;
    private ExoPlayer exoPlayer;
    TextView usernameText, replacementClipCount, durationClipCount;
    TextView heartCount, commentCount, bookmarkCount;
    ProgressBar mediaLoadingIcon;
    ImageView mediaPausedIcon, mediaPlaybackErrorIcon;


    private static SimpleCache simpleCache;
    public static SimpleCache getSimpleCache() {
        return simpleCache;
    }


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


        data = (TemplateAreaScreen.TemplateData) createrBundle.getSerializable("TemplateData");

        previewSurfaceView = findViewById(R.id.previewSurfaceView);
        usernameText = findViewById(R.id.usernameText);
        replacementClipCount = findViewById(R.id.replacementClipCount);
        durationClipCount = findViewById(R.id.durationClipCount);
        heartCount = findViewById(R.id.heartCount);
        commentCount = findViewById(R.id.commentCount);
        bookmarkCount = findViewById(R.id.bookmarkCount);

        mediaLoadingIcon = findViewById(R.id.mediaLoadingIcon);
        mediaPausedIcon = findViewById(R.id.mediaPausedIcon);
        mediaPlaybackErrorIcon = findViewById(R.id.mediaPlaybackErrorIcon);

        usernameText.setText("@" + data.getTemplateAuthor());
        replacementClipCount.setText("" + data.getTemplateClipCount());

        heartCount.setText("" + data.getHeartCount());//NumberHelper.abbreviateNumber(Random.Range(1, 80000000))); // data.clipCount
        commentCount.setText("0");// + data.getComments().length);//NumberHelper.abbreviateNumber(Random.Range(1, 300000))); // data.clipCount
        bookmarkCount.setText("" + data.getBookmarkCount());//NumberHelper.abbreviateNumber(Random.Range(1, 500000))); // data.clipCount


        previewSurfaceView.setOnClickListener(v -> {
            if(exoPlayer != null) {
                if (exoPlayer.isPlaying())
                    pausePlayer();
                else
                    resumePlayer();
            }
        });


        setupMediaPlayer(data.getTemplateVideoLink());

        findViewById(R.id.useTemplateButton).setOnClickListener(v -> {
            Intent intent = new Intent(this, TemplateExportActivity.class);
            intent.putExtra("TemplateData", data);
            startActivity(intent);
        });
        findViewById(R.id.backButton).setOnClickListener(v -> {
            finish();
        });

    }


    void adjustMaximumAspectRatio(int width, int height)
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
    void setupMediaPlayer(String httpsPath) {
        mediaLoadingIcon.setVisibility(View.VISIBLE);

        // Initialize ExoPlayer
        exoPlayer = new ExoPlayer.Builder(this).build();
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.setVideoSurface(previewSurfaceView.getHolder().getSurface());

        // Prepare media item
//        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(httpsPath));
//        exoPlayer.setMediaItem(mediaItem);

        CacheDataSource.Factory cacheDataSourceFactory =
                new CacheDataSource.Factory()
                        .setCache(getSimpleCache())
                        .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory())
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        MediaSource mediaSource = new ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(httpsPath)));

        exoPlayer.setMediaSource(mediaSource);


        exoPlayer.setVolume(1);




        // Add listener for errors
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                LoggingManager.LogToToast(TemplatePreviewActivity.this, error.getMessage());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    // Equivalent to MediaPlayer's onPrepared
                    // Player is ready to start playback
                    durationClipCount.setText(DateHelper.convertTimestampToMMSSFormat(exoPlayer.getDuration()));
                    data.setTemplateDuration(exoPlayer.getDuration());

                    mediaLoadingIcon.setVisibility(View.GONE);
                    mediaPlaybackErrorIcon.setVisibility(View.GONE);
                }
                if(state == Player.STATE_BUFFERING)
                {
                    mediaLoadingIcon.setVisibility(View.VISIBLE);
                    mediaPlaybackErrorIcon.setVisibility(View.GONE);
                }
                if(state == Player.STATE_IDLE) {
                    mediaPlaybackErrorIcon.setVisibility(View.VISIBLE);
                    mediaLoadingIcon.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                Player.Listener.super.onVideoSizeChanged(videoSize);
                adjustMaximumAspectRatio(videoSize.width, videoSize.height);
            }



        });

        exoPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                true
        );


        // Prepare and play
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);

    }

    public void resumePlayer() {
        try {
            if (exoPlayer != null) {
                exoPlayer.play();
                mediaPausedIcon.setVisibility(View.GONE);

                //mediaLoadingIcon.setVisibility(View.GONE);
            }
        } catch (IllegalStateException e) {
            LoggingManager.LogExceptionToNoteOverlay(this, e);
        }
    }
    public void pausePlayer() {
        try {
            if (exoPlayer != null) {
                exoPlayer.pause();
                mediaPausedIcon.setVisibility(View.VISIBLE);
            }
        } catch (IllegalStateException e) {
            LoggingManager.LogExceptionToNoteOverlay(this, e);
        }
    }


    @Override
    public void finish() {
        super.finish();

        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
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
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
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
}
