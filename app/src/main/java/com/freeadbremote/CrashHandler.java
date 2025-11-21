package com.freeadbremote;

import android.content.Context;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Global exception handler to catch uncaught exceptions and log them
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "CrashHandler";
    private final Thread.UncaughtExceptionHandler defaultHandler;
    private final LogManager logManager;
    private final Context context;

    public CrashHandler(Context context) {
        this.context = context.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        this.logManager = LogManager.getInstance(context);
    }

    public static void install(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        logManager.logError("UNCAUGHT EXCEPTION - App will crash", throwable);
        
        // Log thread information
        logManager.logError("Thread: " + thread.getName() + " (ID: " + thread.getId() + ")", null);
        
        // Get full stack trace
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        logManager.logError("Stack trace:\n" + sw.toString(), null);
        
        // Log system information
        logManager.logInfo("Android Version: " + android.os.Build.VERSION.RELEASE);
        logManager.logInfo("Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        logManager.logInfo("SDK: " + android.os.Build.VERSION.SDK_INT);
        
        // Close log file
        logManager.close();
        
        // Call default handler to show crash dialog
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        }
    }
}