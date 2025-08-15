package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public class FileHelper {

    public static void copyFile(Context context, File src, File dst) {
        try
        {
            InputStream in = new FileInputStream(src);
            try {
                OutputStream out = new FileOutputStream(dst);
                try {
                    // Transfer bytes from in to out
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
}
