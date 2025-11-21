package com.freeadbremote.remoteserver;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Simple launcher activity to start the service
 * This allows monkey and other tools to launch the app
 */
public class LauncherActivity extends Activity {

    private static final String TAG = "LauncherActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "LauncherActivity started, starting AppManagerService");

        // Start the service
        Intent serviceIntent = new Intent(this, AppManagerService.class);
        serviceIntent.setAction("com.freeadbremote.remoteserver.START_SERVER");
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // Android 8.0+ requires startForegroundService for foreground services
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Give service a moment to start before finishing
        // This ensures the service is properly started before Activity finishes
        new android.os.Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 500); // 500ms delay
    }
}

