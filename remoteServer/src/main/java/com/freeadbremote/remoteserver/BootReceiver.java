package com.freeadbremote.remoteserver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final int DEFAULT_PORT = 3000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Log.i(TAG, "BOOT_COMPLETED received, starting AppManagerServer");
            try {
                HttpAppManagerServer server =
                        new HttpAppManagerServer(context.getApplicationContext(), DEFAULT_PORT);
                server.start();
                Log.i(TAG, "HTTP AppManagerServer started on port " + DEFAULT_PORT + " from BootReceiver");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start server from BootReceiver", e);
            }
        }
    }
}
