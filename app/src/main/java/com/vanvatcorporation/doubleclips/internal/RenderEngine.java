package com.vanvatcorporation.doubleclips.internal;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.GLES20;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;

public class RenderEngine {
    private static final String TAG = "RenderEngine";

    // Extremely simplified single-clip pipeline proof of concept
    public static void executeSingleClipRender(EditingActivity.Clip clip, String inputPath, String outputPath, int outWidth, int outHeight, int outBitrate, int fps) throws Exception {

        Log.i(TAG, "Starting single clip render from " + inputPath + " to " + outputPath);

        VideoEncoderCore encoderCore = null;
        WindowSurface windowSurface = null;
        EglCore eglCore = null;
        TextureRender textureRender = null;
        SurfaceTexture decoderSurfaceTexture = null;
        VideoDecoderCore decoderCore = null;
        AudioDecoderCore audioDecoderCore = null;
        AudioEncoderCore audioEncoderCore = null;
        MediaMuxer muxer = null;

        try {
            // Setup Encoder & OpenGL Context
            encoderCore = new VideoEncoderCore(outWidth, outHeight, outBitrate, fps);
            eglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
            windowSurface = new WindowSurface(eglCore, encoderCore.getInputSurface(), true);
            windowSurface.makeCurrent();


            boolean isImage = clip.type == EditingActivity.ClipType.IMAGE;
            Texture2DRender texture2DRender = null;
            int imageTextureId = 0;

            if (isImage) {
                texture2DRender = new Texture2DRender();
                texture2DRender.surfaceCreated();
                imageTextureId = ImageTextureLoader.loadTexture(inputPath);
            } else {
                textureRender = new TextureRender();
                textureRender.surfaceCreated();
                decoderSurfaceTexture = new SurfaceTexture(textureRender.getTextureId());
                android.view.Surface surface = new android.view.Surface(decoderSurfaceTexture);
                decoderCore = new VideoDecoderCore(inputPath, surface);
            }

            boolean hasAudio = false;
            if (!isImage && clip.isClipHasAudio() && !clip.isMute()) {
                audioDecoderCore = new AudioDecoderCore(inputPath);
                if (audioDecoderCore.hasAudioTrack()) {
                    hasAudio = true;
                    // Usually 44100Hz 2 Channels, you can read from decoderCore or standardized
                    audioEncoderCore = new AudioEncoderCore(44100, 2, 128000);
                }
            }

            // Create a small white texture for "fade from white"
            int whiteTextureId = 0;
            if (clip.inAnimation != null && "unfold".equals(clip.inAnimation.type)) {
                if (texture2DRender == null) {
                    texture2DRender = new Texture2DRender();
                    texture2DRender.surfaceCreated();
                }
                int[] whiteTextures = new int[1];
                GLES20.glGenTextures(1, whiteTextures, 0);
                whiteTextureId = whiteTextures[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTextureId);
                java.nio.ByteBuffer whiteBuffer = java.nio.ByteBuffer.allocateDirect(4);
                whiteBuffer.put(new byte[]{(byte)255, (byte)255, (byte)255, (byte)255}).position(0);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, whiteBuffer);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            }

            // Muxer setup
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrackIndex = -1;
            int audioTrackIndex = -1;
            boolean muxerStarted = false;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
            boolean videoDecoderDone = isImage; // If image, there's no decoder
            boolean videoEncoderDone = false;
            boolean audioDecoderDone = !hasAudio;
            boolean audioEncoderDone = !hasAudio;

            // Shared lock for wait/notify on frame available
            final Object frameSyncObject = new Object();
            final boolean[] frameAvailable = {false};
            if (!isImage) {
                decoderSurfaceTexture.setOnFrameAvailableListener(st -> {
                    synchronized (frameSyncObject) {
                        frameAvailable[0] = true;
                        frameSyncObject.notifyAll();
                    }
                });
            }

            MediaCodec decoder = isImage ? null : decoderCore.getDecoder();
            MediaCodec encoder = encoderCore.getEncoder();

            MediaCodec audioDecoder = hasAudio ? audioDecoderCore.getDecoder() : null;
            MediaCodec audioEncoder = hasAudio ? audioEncoderCore.getEncoder() : null;

            Log.i(TAG, "Entering loop");
            long imageDurationUs = (long) (clip.duration * 1000000);
            long imageFrameIntervalUs = 1000000 / fps;
            long currentImagePtsUs = 0;
            boolean imageEosSignaled = false;

            boolean videoFormatAdded = false;
            boolean audioFormatAdded = !hasAudio;

            while (!videoEncoderDone || !audioEncoderDone) {

                if (!videoEncoderDone) {
                    if (isImage) {
                        if (!imageEosSignaled) {
                            windowSurface.makeCurrent();
                            texture2DRender.drawFrame(imageTextureId, 1.0f, null);

                            // Apply "Unfold" animation for images
                            if (clip.inAnimation != null && "unfold".equals(clip.inAnimation.type)) {
                                float dur = clip.inAnimation.duration;
                                float ptsSeconds = currentImagePtsUs / 1000000f;
                                if (ptsSeconds < dur) {
                                    float progress = ptsSeconds / dur;

                                    float[] mvp = new float[16];
                                    android.opengl.Matrix.setIdentityM(mvp, 0);
                                    android.opengl.Matrix.translateM(mvp, 0, -1, 0, 0);
                                    android.opengl.Matrix.rotateM(mvp, 0, -90 * (1 - progress), 0, 1, 0);
                                    android.opengl.Matrix.translateM(mvp, 0, 1, 0, 0);

                                    // Redraw with animation
                                    GLES20.glClearColor(0, 0, 0, 1);
                                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                                    texture2DRender.drawFrame(imageTextureId, 1.0f, mvp);

                                    // Fade from white
                                    texture2DRender.drawFrame(whiteTextureId, 1.0f - progress, null);
                                }
                            }

                            windowSurface.setPresentationTime(currentImagePtsUs * 1000);
                            windowSurface.swapBuffers();

                            currentImagePtsUs += imageFrameIntervalUs;
                            if (currentImagePtsUs > imageDurationUs) {
                                encoder.signalEndOfInputStream();
                                imageEosSignaled = true;
                            }
                        }
                    } else {
                        // 1. Drain Extractor to Decoder Input
                        if (!videoDecoderDone) {
                            decoderCore.drainExtractorToDecoder();
                        }

                        // 2. Poll Decoder Output
                        if (!videoDecoderDone) {
                            int decoderStatus = decoder.dequeueOutputBuffer(info, 10000);
                            if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                // no output available yet
                            } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                // ignored
                            } else if (decoderStatus < 0) {
                                // ignore
                            } else {
                                boolean render = info.size != 0;
                                decoder.releaseOutputBuffer(decoderStatus, render);
                                if (render) {
                                    // Wait for the surface texture to be updated
                                    synchronized (frameSyncObject) {
                                        while (!frameAvailable[0]) {
                                            try {
                                                frameSyncObject.wait(500);
                                            } catch (InterruptedException ie) {
                                                throw new RuntimeException(ie);
                                            }
                                        }
                                        frameAvailable[0] = false;
                                    }

                                    // Make context current and draw
                                    windowSurface.makeCurrent();
                                    decoderSurfaceTexture.updateTexImage();

                                    textureRender.drawFrame(decoderSurfaceTexture, 1.0f, null); // alpha 1.0

                                    // Apply "Unfold" animation
                                    if (clip.inAnimation != null && "unfold".equals(clip.inAnimation.type)) {
                                        float dur = clip.inAnimation.duration;
                                        float ptsSeconds = info.presentationTimeUs / 1000000f;
                                        if (ptsSeconds < dur) {
                                            float progress = ptsSeconds / dur;

                                            float[] mvp = new float[16];
                                            android.opengl.Matrix.setIdentityM(mvp, 0);
                                            // Rotate around left edge
                                            android.opengl.Matrix.translateM(mvp, 0, -1, 0, 0);
                                            android.opengl.Matrix.rotateM(mvp, 0, -90 * (1 - progress), 0, 1, 0);
                                            android.opengl.Matrix.translateM(mvp, 0, 1, 0, 0);

                                            // Redraw with animation
                                            GLES20.glClearColor(0, 0, 0, 1);
                                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                                            textureRender.drawFrame(decoderSurfaceTexture, 1.0f, mvp);

                                            // Fade from white
                                            texture2DRender.drawFrame(whiteTextureId, 1.0f - progress, null);
                                        }
                                    }

                                    windowSurface.setPresentationTime(info.presentationTimeUs * 1000);
                                    windowSurface.swapBuffers();
                                }

                                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    Log.i(TAG, "Video Decoder EOS reached");
                                    videoDecoderDone = true;
                                    encoder.signalEndOfInputStream();
                                }
                            }
                        }
                    }

                    // 3. Drain Encoder Output
                    if (!videoFormatAdded || muxerStarted) {
                        int encoderStatus = encoder.dequeueOutputBuffer(info, 10000);
                        if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // no output available
                        } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat newFormat = encoder.getOutputFormat();
                            videoTrackIndex = muxer.addTrack(newFormat);
                            videoFormatAdded = true;
                            if (videoFormatAdded && audioFormatAdded) {
                                muxer.start();
                                muxerStarted = true;
                            }
                        } else if (encoderStatus < 0) {
                            // ignore
                        } else {
                            ByteBuffer encodedData = encoder.getOutputBuffer(encoderStatus);
                            if (encodedData == null) {
                                throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                            }

                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                info.size = 0;
                            }

                            if (info.size != 0) {
                                encodedData.position(info.offset);
                                encodedData.limit(info.offset + info.size);
                                muxer.writeSampleData(videoTrackIndex, encodedData, info);
                            }

                            encoder.releaseOutputBuffer(encoderStatus, false);

                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                Log.i(TAG, "Video Encoder EOS reached");
                                videoEncoderDone = true;
                            }
                        }
                    }
                }

                // Audio loop portion
                if (!audioEncoderDone && hasAudio) {
                    // Drain Extractor to Audio Decoder
                    if (!audioDecoderDone) {
                        audioDecoderCore.drainExtractorToDecoder();
                    }

                    // Decode audio -> Encode audio
                    if (!audioDecoderDone) {
                        int decoderStatus = audioDecoder.dequeueOutputBuffer(audioInfo, 10000);
                        if (decoderStatus >= 0) {
                            ByteBuffer pcmBuffer = audioDecoder.getOutputBuffer(decoderStatus);
                            if (audioInfo.size > 0 && pcmBuffer != null) {
                                // Encode it immediately
                                int inIndex = audioEncoder.dequeueInputBuffer(10000);
                                if (inIndex >= 0) {
                                    ByteBuffer encInputBuffer = audioEncoder.getInputBuffer(inIndex);
                                    encInputBuffer.clear();
                                    pcmBuffer.position(audioInfo.offset);
                                    pcmBuffer.limit(audioInfo.offset + audioInfo.size);
                                    encInputBuffer.put(pcmBuffer);
                                    audioEncoder.queueInputBuffer(inIndex, 0, audioInfo.size, audioInfo.presentationTimeUs, 0);
                                }
                            }
                            audioDecoder.releaseOutputBuffer(decoderStatus, false);

                            if ((audioInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                audioDecoderDone = true;
                                int inIndex = audioEncoder.dequeueInputBuffer(10000);
                                audioEncoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            }
                        }
                    }

                    // Drain Audio Encoder -> Muxer
                    if (!audioFormatAdded || muxerStarted) {
                        int encoderStatus = audioEncoder.dequeueOutputBuffer(audioInfo, 10000);
                        if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            audioTrackIndex = muxer.addTrack(audioEncoder.getOutputFormat());
                            audioFormatAdded = true;
                            if (videoFormatAdded && audioFormatAdded) {
                                muxer.start();
                                muxerStarted = true;
                            }
                        } else if (encoderStatus >= 0) {
                            ByteBuffer encodedData = audioEncoder.getOutputBuffer(encoderStatus);
                            if ((audioInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                audioInfo.size = 0;
                            }

                            if (audioInfo.size != 0) {
                                encodedData.position(audioInfo.offset);
                                encodedData.limit(audioInfo.offset + audioInfo.size);
                                muxer.writeSampleData(audioTrackIndex, encodedData, audioInfo);
                            }

                            audioEncoder.releaseOutputBuffer(encoderStatus, false);
                            if ((audioInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                audioEncoderDone = true;
                            }
                        }
                    }
                }
            }

            Log.i(TAG, "Rendering complete!");
            // Release resources
            if (whiteTextureId != 0) {
                GLES20.glDeleteTextures(1, new int[]{whiteTextureId}, 0);
            }
            if (texture2DRender != null && isImage) {
                // ... already handled if exists ...
            }
            if (decoder != null) {decoderCore.release();}
            if (encoderCore != null) encoderCore.release();
            if (audioDecoderCore != null) audioDecoderCore.release();
            if (audioEncoderCore != null) audioEncoderCore.release();
            if (muxer != null) {
                muxer.stop();
                muxer.release();
            }
            if (windowSurface != null) windowSurface.release();
            if (eglCore != null) eglCore.release();
            if (decoderSurfaceTexture != null) decoderSurfaceTexture.release();
        }
        catch (Exception e)
        {

        }
    }
}
