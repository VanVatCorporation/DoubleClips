package com.vanvatcorporation.doubleclips.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.SegmentationMask;

import java.nio.ByteBuffer;

public class BackgroundRemover {

    public interface Callback {
        void onSuccess(Bitmap result);
        void onFailure(Exception e);
    }

    /**
     * Removes the background from a bitmap using ML Kit Selfie Segmentation.
     * note: This is an asynchronous operation.
     */
    public static void removeBackground(Bitmap source, Callback callback) {
        SelfieSegmenterOptions options =
                new SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .build();

        Segmenter segmenter = Segmentation.getClient(options);
        InputImage image = InputImage.fromBitmap(source, 0);

        segmenter.process(image)
                .addOnSuccessListener(segmentationMask -> {
                    Bitmap result = applyMask(source, segmentationMask);
                    callback.onSuccess(result);
                    segmenter.close();
                })
                .addOnFailureListener(e -> {
                    callback.onFailure(e);
                    segmenter.close();
                });
    }

    /**
     * Applies the segmentation mask to the source bitmap.
     * Returns a new bitmap with transparency where the background was detected.
     */
    private static Bitmap applyMask(Bitmap source, SegmentationMask mask) {
        int width = mask.getWidth();
        int height = mask.getHeight();
        ByteBuffer buffer = mask.getBuffer();

        // Ensure the buffer is at the beginning
        buffer.rewind();

        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            float opacity = buffer.getFloat();
            // In Selfie Segmentation, close to 1.0 means person, close to 0.0 means background.
            int alpha = (int) (opacity * 255);
            int color = pixels[i];
            pixels[i] = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }

    /**
     * Generates a grayscale alpha mask bitmap from a source bitmap.
     * White = Subject, Black = Background.
     */
    public static void generateAlphaMask(Bitmap source, Callback callback) {
        SelfieSegmenterOptions options =
                new SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .build();

        Segmenter segmenter = Segmentation.getClient(options);
        InputImage image = InputImage.fromBitmap(source, 0);

        segmenter.process(image)
                .addOnSuccessListener(segmentationMask -> {
                    Bitmap maskBitmap = createGrayscaleMask(segmentationMask);
                    callback.onSuccess(maskBitmap);
                    segmenter.close();
                })
                .addOnFailureListener(e -> {
                    callback.onFailure(e);
                    segmenter.close();
                });
    }

    private static Bitmap createGrayscaleMask(SegmentationMask mask) {
        int width = mask.getWidth();
        int height = mask.getHeight();
        ByteBuffer buffer = mask.getBuffer();
        buffer.rewind();

        Bitmap maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];

        for (int i = 0; i < pixels.length; i++) {
            float opacity = buffer.getFloat();
            int gray = (int) (opacity * 255);
            pixels[i] = Color.rgb(gray, gray, gray);
        }

        maskBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return maskBitmap;
    }
}
