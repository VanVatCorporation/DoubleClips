package com.vanvatcorporation.doubleclips;

import android.content.Context;
import android.view.SurfaceView;
import android.widget.ImageView;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

public class ExoPlayerExtended {
    private ExoPlayer exoPlayer;

    private SurfaceView surfaceView;
    private ImageView thumbnailView;

    public ExoPlayerExtended(Context context) {
        init(context);
    }

    private void init(Context context) {

        exoPlayer = new ExoPlayer.Builder(context).build();

    }


    public ExoPlayer getExoPlayer() {
        return exoPlayer;
    }

    public SurfaceView getVideoSurfaceView() {
        return surfaceView;
    }

    public void setVideoSurfaceView(SurfaceView surfaceView) {
        this.surfaceView = surfaceView;
        exoPlayer.setVideoSurfaceView(surfaceView);
    }

    public ImageView getThumbnailView() {
        return thumbnailView;
    }

    public void setThumbnailView(ImageView thumbnailView) {
        this.thumbnailView = thumbnailView;
    }

    public void release() {
        exoPlayer.release();
        exoPlayer = null;
    }
}
