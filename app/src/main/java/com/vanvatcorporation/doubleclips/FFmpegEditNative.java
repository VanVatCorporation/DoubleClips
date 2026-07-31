package com.vanvatcorporation.doubleclips;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.vanvatcorporation.doubleclips.FFmpegEdit.queue;

import android.content.Context;

public class FFmpegEditNative {

    // These function are specifically for Native (This is Android) version. This is part of work for uniting the FFmpegEdit across all platform.


    public static String hardwareAcceleratedName = "mediacodec";


    public static void runAnyCommand(Context context, String cmd, String taskName, String successMessage, String failMessage, boolean includeFullReport,
                                     Runnable onSuccessRunnable, Runnable onFailRunnable,
                                     RunnableImpl onLogRunnable, RunnableImpl onStatisticsRunnable) {
        LoggingManager.LogToPersistentDataPath(context, cmd);



        queue.enqueue(
                new FFmpegEdit.FfmpegRenderQueue.FfmpegRenderQueueInfo(
                        taskName,
                        () -> {

                            FFmpegKit.executeAsync(cmd, session -> {
                                        if (ReturnCode.isSuccess(session.getReturnCode())) {
                                            StringBuilder builder = new StringBuilder();
                                            if (includeFullReport) {
                                                builder.append("Report: ").append("\n")
                                                        .append("Output: ").append(session.getOutput()).append("\n")
                                                        .append("State: ").append(session.getState()).append("\n")
                                                        .append("Return code: ").append(session.getReturnCode()).append("\n");
                                            }
                                            LoggingManager.LogToPersistentDataPath(context, successMessage + builder);
                                            onSuccessRunnable.run();
                                        } else {
                                            StringBuilder builder = new StringBuilder();
                                            if (includeFullReport) {
                                                builder.append("Report: ").append("\n")
                                                        .append("Output: ").append(session.getOutput()).append("\n")
                                                        .append("State: ").append(session.getState()).append("\n")
                                                        .append("Return code: ").append(session.getReturnCode()).append("\n")
                                                        .append("Stacktrace: ").append(session.getFailStackTrace()).append("\n");
                                            }
                                            LoggingManager.LogToPersistentDataPath(context, failMessage + builder);
                                            onFailRunnable.run();
                                        }

                                        // TODO: Add a slightly user friendly delay (Execute next ffmpeg rendering part in 3, 2, 1), dynamically into logText
                                        Executors.newSingleThreadExecutor().execute(() -> {
                                            try {
                                                Thread.sleep(1000);
                                            } catch (InterruptedException ignored) {

                                            }
                                            queue.taskCompleted(); // Move to next task
                                        });

                                    },
                                    onLogRunnable::runWithParam,
                                    onStatisticsRunnable::runWithParam
                            );

                        }
                )

        );

    }


}
