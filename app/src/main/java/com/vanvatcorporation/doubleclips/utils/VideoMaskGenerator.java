package com.vanvatcorporation.doubleclips.utils;

import android.content.Context;
import android.graphics.Bitmap;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility to generate an alpha mask video from a source video using ML Kit Selfie Segmentation.
 */
public class VideoMaskGenerator {

    public interface Callback {
        void onProgress(int current, int total);
        void onSuccess(String maskPath);
        void onFailure(Exception e);
    }

    /**
     * Generates an alpha mask video (grayscale) for the given clip.
     * The mask video will be saved at the clip's cutout path with a .mp4 extension.
     */
    public static void generateMask(Context context, EditingActivity.Clip clip, MainAreaScreen.ProjectData data, Callback callback) {
        String videoPath = clip.getAbsolutePath(data);
        String cutoutPath = clip.getCutoutPath(data.getProjectPath()) + ".mp4";
        
        // Temporary directory for frames
        String tempDir = IOHelper.CombinePath(data.getProjectPath(), "temp_masks", clip.getClipName().replace(".", "_"));
        IOHelper.createEmptyDirectories(tempDir);
        IOHelper.deleteFilesInDir(tempDir); // Clean up if previous run failed

        // 1. Extract Frames
        // Using -vsync 0 to keep all frames as-is
        String extractCmd = String.format("-i \"%s\" -vsync 0 \"%s/frame_%%05d.png\"", 
                videoPath, tempDir);

        LoggingManager.LogToPersistentDataPath(context, "Extracting frames: " + extractCmd);
        
        FFmpegKit.executeAsync(extractCmd, session -> {
            if (ReturnCode.isSuccess(session.getReturnCode())) {
                processFrames(context, clip, tempDir, cutoutPath, callback);
            } else {
                callback.onFailure(new Exception("Frame extraction failed: " + session.getOutput()));
            }
        });
    }

    private static void processFrames(Context context, EditingActivity.Clip clip, String tempDir, String cutoutPath, Callback callback) {
        File dir = new File(tempDir);
        File[] frames = dir.listFiles((d, name) -> name.endsWith(".png"));
        if (frames == null || frames.length == 0) {
            callback.onFailure(new Exception("No frames extracted"));
            return;
        }

        // Sort frames by name to ensure order
        Arrays.sort(frames, Comparator.comparing(File::getName));

        AtomicInteger processedCount = new AtomicInteger(0);
        int totalFrames = frames.length;

        for (File frameFile : frames) {
            Bitmap bitmap = IOHelper.loadBitmap(frameFile.getAbsolutePath());
            if (bitmap == null) {
                int current = processedCount.incrementAndGet();
                if (current == totalFrames) {
                    encodeMaskVideo(context, tempDir, cutoutPath, callback);
                }
                continue;
            }

            BackgroundRemover.generateAlphaMask(bitmap, new BackgroundRemover.Callback() {
                @Override
                public void onSuccess(Bitmap mask) {
                    IOHelper.saveBitmap(context, mask, frameFile.getAbsolutePath());
                    mask.recycle();
                    bitmap.recycle();
                    
                    int current = processedCount.incrementAndGet();
                    callback.onProgress(current, totalFrames);
                    if (current == totalFrames) {
                        encodeMaskVideo(context, tempDir, cutoutPath, callback);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    callback.onFailure(e);
                }
            });
        }
    }

    private static void encodeMaskVideo(Context context, String tempDir, String cutoutPath, Callback callback) {
        // 3. Re-encode frames into a grayscale mp4
        // Assuming 30fps for now, ideally should match source
        String encodeCmd = String.format("-framerate 30 -i \"%s/frame_%%05d.png\" -c:v libopenh264 -pix_fmt yuv420p -y \"%s\"",
                tempDir, cutoutPath);

        LoggingManager.LogToPersistentDataPath(context, "Encoding mask video: " + encodeCmd);

        FFmpegKit.executeAsync(encodeCmd, session -> {
            if (ReturnCode.isSuccess(session.getReturnCode())) {
                // Cleanup temp frames
                IOHelper.deleteDir(tempDir);
                callback.onSuccess(cutoutPath);
            } else {
                callback.onFailure(new Exception("Mask video encoding failed: " + session.getOutput()));
            }
        });
    }
}
