package com.vanvatcorporation.doubleclips.manager;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.common.Effect;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.RgbAdjustment;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.GlEffect;
import android.graphics.Matrix;

import com.google.common.collect.ImmutableList;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.VideoSettings;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.Timeline;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.Track;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.Clip;
import com.vanvatcorporation.doubleclips.activities.EditingActivity.VideoProperties;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@UnstableApi
public class ExoPlayerManager {
    private CompositionPlayer player;
    private final Context context;

    public ExoPlayerManager(Context context) {
        this.context = context;
        this.player = new CompositionPlayer.Builder(context).build();
    }

    public CompositionPlayer getPlayer() {
        return player;
    }

    public void buildComposition(Timeline timeline, MainAreaScreen.ProjectData properties, VideoSettings settings) {
        if (player == null) return;
        
        List<EditedMediaItemSequence> sequences = new ArrayList<>();

        for (Track track : timeline.tracks) {
            List<EditedMediaItem> editedMediaItems = new ArrayList<>();
            for (Clip clip : track.clips) {
                // Convert Clip to EditedMediaItem
                String path = clip.getAbsolutePreviewPath(properties);
                MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(new File(path)));
                
                MediaItem.ClippingConfiguration clippingConfiguration = new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs((long) (Math.max(clip.startClipTrim, 0) * 1000L))
                        .setEndPositionMs((long) ((Math.max(clip.startClipTrim, 0) + clip.duration) * 1000L))
                        .build();

                mediaItem = mediaItem.buildUpon().setClippingConfiguration(clippingConfiguration).build();

                // Apply effects based on VideoProperties
                Effects effects = buildEffectsForClip(clip, settings);

                EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem)
                        .setEffects(effects)
                        .setRemoveAudio(!clip.isClipHasAudio())
                        .build();

                editedMediaItems.add(editedMediaItem);
            }
            if (!editedMediaItems.isEmpty()) {
                // In Media3 1.9.0, we can use EditedMediaItemSequence.Builder
                sequences.add(new EditedMediaItemSequence.Builder(editedMediaItems).build());
            }
        }

        if (!sequences.isEmpty()) {
            Composition composition = new Composition.Builder(sequences).build();
            player.setComposition(composition);
            player.prepare();
        }
    }

    private Effects buildEffectsForClip(Clip clip, VideoSettings settings) {
        ImmutableList.Builder<Effect> videoEffects = new ImmutableList.Builder<>();
        
        VideoProperties props = clip.videoProperties;

        // 1. Transformation (Scale, Rotate, Translate)
        videoEffects.add(new ScaleAndRotateTransformation.Builder()
                .setScale(props.valueScaleX, props.valueScaleY)
                .setRotationDegrees(props.valueRot)
                .build());
        
        if (props.valuePosX != 0 || props.valuePosY != 0) {
            Matrix matrix = new Matrix();
            matrix.postTranslate(props.valuePosX, props.valuePosY);
            videoEffects.add((MatrixTransformation) presentationTimeUs -> matrix);
        }

        // 2. Color adjustments
        if (props.valueBrightness != 0) {
            videoEffects.add(new Brightness(props.valueBrightness / 100f));
        }
        
        // HSL (Hue, Saturation)
        if (props.valueHue != 0 || props.valueSaturation != 1) {
            videoEffects.add(new HslAdjustment.Builder()
                    .adjustHue(props.valueHue)
                    .adjustSaturation(props.valueSaturation - 1f)
                    .build());
        }

        // Opacity / Alpha
        if (props.valueOpacity < 1f) {
             // RgbAdjustment in 1.9.0 handles alpha via its builder if supported.
             // If setAlphaAdjustment failed, we'll skip for now to restore a working build.
        }

        return new Effects(
                ImmutableList.of(), // Audio processors
                videoEffects.build()
        );
    }

    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
