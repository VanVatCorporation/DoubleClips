package com.vanvatcorporation.doubleclips.manager;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.externalUtils.Random;
import com.vanvatcorporation.doubleclips.helper.IOHelper;

import java.io.PrintWriter;
import java.io.StringWriter;

public class LoggingManager {
    public static void LogToNoteOverlay(Context context, String message) {
        try {
//            if(context == null) return;
//            Intent intent = new Intent(context, NoteOverlayBroadcaster.class);
//            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//            intent.putExtra("Content", message);
//            PendingIntent senderIntent = PendingIntentManager.getB(context, intent, true);
//            senderIntent.send();
            Log.e("Note Overlay", message);
            LoggingManager.LogToPersistentDataPath(context, message);
        } catch (Exception e) {
            Toast.makeText(context, getStackTraceFromException(e), Toast.LENGTH_SHORT).show();
        }
    }

    public static void LogNotificationToNoteOverlay(Context context, String content, Parcelable parcelable) {
        try {
//            if(context == null) return;
//            Intent intent = new Intent(context, NoteOverlayBroadcaster.class);
//            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//            intent.putExtra("Content", content);
//            intent.putExtra("PendingIntent", parcelable);
//            PendingIntent senderIntent = PendingIntentManager.getB(context, intent, true);
//            senderIntent.send();
        } catch (Exception e) {
            Toast.makeText(context, getStackTraceFromException(e), Toast.LENGTH_SHORT).show();
        }
    }

    public static void LogExceptionToNoteOverlay(Context context, Exception ex) {
        try {
//            if(context == null) return;
//            Intent intent = new Intent(context, NoteOverlayBroadcaster.class);
//            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//            intent.putExtra("Content", getStackTraceFromException(ex));
//            PendingIntent senderIntent = PendingIntentManager.getB(context, intent, true);
//            senderIntent.send();

            if(Looper.myLooper() == null)
                Looper.prepare();
            Toast.makeText(context, getStackTraceFromException(ex), Toast.LENGTH_SHORT).show();
            Log.e(context.getPackageName(), getStackTraceFromException(ex));

            LoggingManager.LogToPersistentDataPath(context, getStackTraceFromException(ex));
        } catch (Exception e) {
            if(Looper.myLooper() == null)
                Looper.prepare();
            Toast.makeText(context, getStackTraceFromException(e), Toast.LENGTH_SHORT).show();
        }
    }

    public static void LogToToast(Context context, String message) {

        if(Looper.myLooper() == null)
            Looper.prepare();
        try {
            if(context == null) return;
            if(Looper.myLooper() == null)
                Looper.prepare();
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if(Looper.myLooper() == null)
                Looper.prepare();
            Toast.makeText(context, getStackTraceFromException(e), Toast.LENGTH_SHORT).show();
        }
    }
    public static void LogToExternalDisk(Context context, String fileName, String message) {
        try {
            if(context == null) return;
            IOHelper.appendToFileTrunc(context, IOHelper.CombinePath(IOHelper.getPersistentDataPath(context), Constants.DEFAULT_LOGGING_DIRECTORY, fileName), message, Constants.DEFAULT_DEBUG_LOGGING_SIZE);
        } catch (Exception e) {
            Toast.makeText(context, getStackTraceFromException(e), Toast.LENGTH_SHORT).show();
        }
    }
    public static void LogToPersistentDataPath(Context context, String message) {
        LogToExternalDisk(context, "debug.txt", message);
    }
    public static void LogToNotification(Context context, String title, String message) {
        try {
//            if(context == null) return;
//            Notificator.sendNotification(context, Random.Range(0, 9000), "LoggingManager - " + title, message);
        } catch (Exception e) {
            Toast.makeText(context, getStackTraceFromException(e), Toast.LENGTH_SHORT).show();
        }
    }


    public static String getStackTraceFromException(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}
