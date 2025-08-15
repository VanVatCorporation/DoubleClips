package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;

import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.BufferedInputStream;
import java.io.InputStream;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetworkHelper {
    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    public static void networkRunnable(Context context, Runnable runnable)
    {

        if(Looper.myLooper() == null)
            Looper.prepare();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            try {
                runnable.run();
            } catch (Exception e) {
                LoggingManager.LogExceptionToNoteOverlay(context, e);
            }
        });
    }
}
