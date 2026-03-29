package com.vanvatcorporation.doubleclips.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.doubleclips.ext.rajawali.RajawaliSceneRenderer;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

public class Scene3dExportGenerator {

    public interface Callback {
        void onProgress(int current, int total);
        void onSuccess(String imageSequencePathDir);
        void onFailure(Exception e);
    }

    public static void generatePreRenderedScene(Context context, RelativeLayout hiddenContainer,
                                                EditingActivity.Clip sceneClip, 
                                                EditingActivity.Clip textureClip, 
                                                MainAreaScreen.ProjectData data,
                                                int fps, int width, int height,
                                                Callback callback) {
        
        String tempDir = IOHelper.CombinePath(data.getProjectPath(), "temp_scenes", sceneClip.getClipName().replace(".", "_"));
        IOHelper.createEmptyDirectories(tempDir);
        IOHelper.deleteFilesInDir(tempDir); 

        String textureFramesDir = null;

        // 1. Extract texture frames if texture clip is available
        if (textureClip != null && sceneClip.textureClipName != null && !sceneClip.textureClipName.isEmpty()) {
            textureFramesDir = IOHelper.CombinePath(data.getProjectPath(), "temp_textures", textureClip.getClipName().replace(".", "_"));
            IOHelper.createEmptyDirectories(textureFramesDir);
            IOHelper.deleteFilesInDir(textureFramesDir);
            
            String videoPath = textureClip.getAbsolutePath(data);
            String extractCmd = String.format("-i \"%s\" -vsync 0 -r %d \"%s/frame_%%05d.png\"", 
                    videoPath, fps, textureFramesDir);

            String finalTextureFramesDir = textureFramesDir;
            FFmpegKit.executeAsync(extractCmd, session -> {
                if (ReturnCode.isSuccess(session.getReturnCode())) {
                    renderRajawaliFrames(context, hiddenContainer, sceneClip, tempDir, finalTextureFramesDir, fps, width, height, callback);
                } else {
                    callback.onFailure(new Exception("Texture extraction failed: " + session.getOutput()));
                }
            });
        } else {
            renderRajawaliFrames(context, hiddenContainer, sceneClip, tempDir, null, fps, width, height, callback);
        }
    }

    private static void renderRajawaliFrames(Context context, RelativeLayout hiddenContainer,
                                             EditingActivity.Clip sceneClip, String targetDir, 
                                             String textureFramesDir, int fps, int width, int height, 
                                             Callback callback) {
        
        // Ensure this runs on main thread for SurfaceView creation
        hiddenContainer.post(() -> {
            try {
                org.rajawali3d.view.SurfaceView surfaceView = new org.rajawali3d.view.SurfaceView(context);
                surfaceView.setZOrderOnTop(true);
                surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); 
                surfaceView.getHolder().setFormat(PixelFormat.TRANSPARENT);
                surfaceView.setFrameRate(fps);

                float duration = sceneClip.duration;
                int totalFrames = (int) (duration * fps);

                RajawaliSceneRenderer renderer = new RajawaliSceneRenderer(context, sceneClip.sceneConfig);
                surfaceView.setSurfaceRenderer(renderer);
                
                // Add to view invisibly
                surfaceView.setAlpha(0.01f);
                hiddenContainer.addView(surfaceView, new FrameLayout.LayoutParams(width, height));

                AtomicInteger currentFrame = new AtomicInteger(0);

                renderer.setExportCallback(width, height, new RajawaliSceneRenderer.ExportCallback() {
                    @Override
                    public void onFrameRendered(Bitmap bitmap) {
                        int frameInd = currentFrame.getAndIncrement();
                        
                        // Save bitmap
                        String framePath = String.format("%s/frame_%05d.png", targetDir, frameInd);
                        IOHelper.saveBitmap(context, bitmap, framePath);
                        bitmap.recycle();

                        callback.onProgress(frameInd, totalFrames);

                        if (frameInd >= totalFrames) {
                            hiddenContainer.post(() -> hiddenContainer.removeView(surfaceView));
                            callback.onSuccess(targetDir);
                        } else {
                            // Update next texture
                            if (textureFramesDir != null) {
                                String texturePath = String.format("%s/frame_%05d.png", textureFramesDir, frameInd + 1); // ffmpeg is 1-indexed
                                if (IOHelper.isFileExist(texturePath)) {
                                    Bitmap texBitmap = IOHelper.loadBitmap(texturePath);
                                    if(texBitmap != null) {
                                        renderer.updateTextureBitmap(texBitmap);
                                    }
                                }
                            }
                            // Advance time for scene renderer mathematically if possible
                            renderer.seekTo((float)frameInd / fps);

                            // Request next render
                            surfaceView.requestRender();
                        }
                    }
                });

                // Start loop
                surfaceView.requestRender();

            } catch (Exception e) {
                callback.onFailure(e);
            }
        });
    }

}
