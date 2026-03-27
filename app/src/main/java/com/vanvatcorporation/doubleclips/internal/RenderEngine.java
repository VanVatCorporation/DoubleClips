package com.vanvatcorporation.doubleclips.internal;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class RenderEngine {
    private static final String TAG = "RenderEngine";
    
    // Extremely simplified single-clip pipeline proof of concept
    public static void executeSingleClipRender(String inputPath, String outputPath, int outWidth, int outHeight, int outBitrate, int fps) throws Exception {
        
        Log.i(TAG, "Starting single clip render from " + inputPath + " to " + outputPath);
        
        VideoEncoderCore encoderCore = null;
        WindowSurface windowSurface = null;
        EglCore eglCore = null;
        TextureRender textureRender = null;
        SurfaceTexture decoderSurfaceTexture = null;
        VideoDecoderCore decoderCore = null;
        MediaMuxer muxer = null;
        
        try {
            // Setup Encoder & OpenGL Context
            encoderCore = new VideoEncoderCore(outWidth, outHeight, outBitrate, fps);
            eglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
            windowSurface = new WindowSurface(eglCore, encoderCore.getInputSurface(), true);
            windowSurface.makeCurrent();
            
            // Setup OpenGL texture to receive Decoder frames
            textureRender = new TextureRender();
            textureRender.surfaceCreated();
            
            decoderSurfaceTexture = new SurfaceTexture(textureRender.getTextureId());
            
            // Setup Decoder
            android.view.Surface surface = new android.view.Surface(decoderSurfaceTexture);
            decoderCore = new VideoDecoderCore(inputPath, surface);
            
            // Muxer setup
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrackIndex = -1;
            boolean muxerStarted = false;
            
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean decoderDone = false;
            boolean encoderDone = false;
            
            // Shared lock for wait/notify on frame available
            final Object frameSyncObject = new Object();
            final boolean[] frameAvailable = {false};
            decoderSurfaceTexture.setOnFrameAvailableListener(st -> {
                synchronized (frameSyncObject) {
                    frameAvailable[0] = true;
                    frameSyncObject.notifyAll();
                }
            });
            
            MediaCodec decoder = decoderCore.getDecoder();
            MediaCodec encoder = encoderCore.getEncoder();
            
            Log.i(TAG, "Entering loop");
            while (!encoderDone) {
                
                // 1. Drain Extractor to Decoder Input
                if (!decoderDone) {
                    decoderCore.drainExtractorToDecoder();
                }
                
                // 2. Poll Decoder Output
                if (!decoderDone) {
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
                            windowSurface.setPresentationTime(info.presentationTimeUs * 1000);
                            windowSurface.swapBuffers();
                        }
                        
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(TAG, "Decoder EOS reached");
                            decoderDone = true;
                            encoder.signalEndOfInputStream();
                        }
                    }
                }
                
                // 3. Drain Encoder Output
                int encoderStatus = encoder.dequeueOutputBuffer(info, 10000);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new RuntimeException("format changed twice");
                    }
                    MediaFormat newFormat = encoder.getOutputFormat();
                    videoTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
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
                        if (!muxerStarted) {
                            throw new RuntimeException("muxer hasn't started");
                        }
                        
                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrackIndex, encodedData, info);
                    }
                    
                    encoder.releaseOutputBuffer(encoderStatus, false);
                    
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "Encoder EOS reached");
                        encoderDone = true;
                        break;
                    }
                }
            }
            
            Log.i(TAG, "Rendering complete!");
            
        } finally {
            if (decoderCore != null) decoderCore.release();
            if (encoderCore != null) encoderCore.release();
            if (muxer != null) {
                muxer.stop();
                muxer.release();
            }
            if (windowSurface != null) windowSurface.release();
            if (eglCore != null) eglCore.release();
            if (decoderSurfaceTexture != null) decoderSurfaceTexture.release();
        }
    }
}
