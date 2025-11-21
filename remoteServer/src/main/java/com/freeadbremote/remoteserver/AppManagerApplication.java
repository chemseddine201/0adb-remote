package com.freeadbremote.remoteserver;

import android.app.Application;
import android.util.Log;

public class AppManagerApplication extends Application {

    private static final String TAG = "AppManagerApplication";
    private static final int DEFAULT_PORT = 3000;

    private static HttpAppManagerServer server;

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            if (server == null) {
                server = new HttpAppManagerServer(getApplicationContext(), DEFAULT_PORT);
                server.start();
                Log.i(TAG, "HTTP AppManagerServer started on port " + DEFAULT_PORT);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AppManagerServer", e);
        }
    }

    public static HttpAppManagerServer getServer() {
        return server;
    }
}
