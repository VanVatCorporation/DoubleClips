package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class IOImageHelper extends IOHelper {
    public static void SaveFileAsPNGImage(Context context, String path, Bitmap bm)
    {
        SaveFileAsPNGImage(context, path, bm, 100);
    }
    public static void SaveFileAsPNGImage(Context context, String path, Bitmap bm, int quality)
    {
        File file = new File(path);
        try {
            FileOutputStream outStream = new FileOutputStream(file);
            bm.compress(Bitmap.CompressFormat.PNG, quality, outStream);
            outStream.flush();
            outStream.close();
        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
    public static Bitmap LoadFileAsPNGImage(Context context, String path)
    {
        return LoadFileAsPNGImage(context, path, 1);
    }
    public static Bitmap LoadFileAsPNGImage(Context context, String path, int sampleSize)
    {
        File file = new File(path);
        try {
            InputStream in = new FileInputStream(file);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            return BitmapFactory.decodeStream(in,null,options);
        } catch (FileNotFoundException e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
        return null;
    }
}
