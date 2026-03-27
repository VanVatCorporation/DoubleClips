package com.vanvatcorporation.doubleclips;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl;
import com.vanvatcorporation.doubleclips.internal.RenderEngine;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class InternalEdit {

    private static final String TAG = "InternalEdit";

    // Maintain a similar queue system to FFmpegEdit to easily drop-in replace
    public static FFmpegEdit.FfmpegRenderQueue queue = new FFmpegEdit.FfmpegRenderQueue();
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public static void runRender(Context context, FFmpegEdit.RenderSettings renderSettings, String taskName, String successMessage, String failMessage,
                                 Runnable onSuccessRunnable, Runnable onFailRunnable,
                                 RunnableImpl onLogRunnable, RunnableImpl onStatisticsRunnable) {
                                     
        LoggingManager.LogToPersistentDataPath(context, "Start Render Engine: " + taskName);

        queue.enqueue(
            new FFmpegEdit.FfmpegRenderQueue.FfmpegRenderQueueInfo(taskName, () -> {
                executorService.execute(() -> {
                    try {
                        // 1. Setup Render Engine parameters based on renderSettings
                        String outputPath = IOHelper.CombinePath(
                            renderSettings.data.getProjectPath(), 
                            (renderSettings.isFinal ? "" : (renderSettings.renderingIndex + "_")) + Constants.DEFAULT_EXPORT_CLIP_FILENAME
                        );

                        if (renderSettings.isTemplateCommand) {
                            outputPath = Constants.DEFAULT_TEMPLATE_CLIP_EXPORT_MARK;
                        }
                        
                        String inputPath = "";
                        EditingActivity.Clip firstClip = null;
                        if (renderSettings.timeline != null && renderSettings.timeline.tracks.size() > 0) {
                            if (renderSettings.timeline.tracks.get(0).clips.size() > 0) {
                                firstClip = renderSettings.timeline.tracks.get(0).clips.get(0);
                                inputPath = firstClip.getAbsolutePath(renderSettings.data);
                            }
                        }

                        if (!inputPath.isEmpty() && firstClip != null) {
                            int width = Integer.parseInt(renderSettings.settings.getRenderVideoWidth(renderSettings.isTemplateCommand));
                            int height = Integer.parseInt(renderSettings.settings.getRenderVideoHeight(renderSettings.isTemplateCommand));
                            int fps = renderSettings.settings.getFrameRate();
                            int bitrate = 5000000; // Hardcoded 5Mbps for POC
                            
                            RenderEngine.executeSingleClipRender(firstClip, inputPath, outputPath, width, height, bitrate, fps);
                        } else {
                            // Fake progress logic for now while setting up the core EGL classes if no clip is found
                            long fakeDuration = 3000;
                            long startTime = System.currentTimeMillis();
                            while (System.currentTimeMillis() - startTime < fakeDuration) {
                                Thread.sleep(100);
                                int progress = (int) (((System.currentTimeMillis() - startTime) / (float) fakeDuration) * 100);
                                onLogRunnable.runWithParam("Rendering... " + progress + "%\n");
                                onStatisticsRunnable.runWithParam(progress);
                            }
                        }

                        // Success callback
                        new Handler(Looper.getMainLooper()).post(() -> {
                            LoggingManager.LogToPersistentDataPath(context, successMessage);
                            onSuccessRunnable.run();
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "Render Engine failed", e);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            LoggingManager.LogToPersistentDataPath(context, failMessage + "\n" + e.getMessage());
                            onFailRunnable.run();
                        });
                    } finally {
                        queue.taskCompleted(); // Move to next task
                    }
                });
            })
        );
    }
}
