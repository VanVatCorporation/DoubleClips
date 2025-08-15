package com.vanvatcorporation.doubleclips;

import android.content.Context;

import androidx.annotation.NonNull;

import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.PrintWriter;
import java.io.StringWriter;
public class UncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
    private Thread.UncaughtExceptionHandler defaultHandler;
    private final Context mainContext;
    Runnable onTerminateAction;
    public UncaughtExceptionHandler(Thread.UncaughtExceptionHandler defaultHandler, Context context) {
        this.defaultHandler = defaultHandler;
        mainContext = context;
    }

    // The method that handles uncaught exceptions
    @Override
    public void uncaughtException(@NonNull Thread thread, Throwable throwable) {
        onTerminateAction.run();
        // Get the stack trace as a string
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        String stackTrace = stringWriter.toString();

        LoggingManager.LogToPersistentDataPath(mainContext, "Stacktrace Written!\n" + stackTrace);

        defaultHandler.uncaughtException(thread, throwable);
    }
}