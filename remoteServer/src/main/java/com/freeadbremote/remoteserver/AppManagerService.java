package com.freeadbremote.remoteserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class AppManagerService extends Service {

    private static final String TAG = "AppManagerService";
    private static final int DEFAULT_PORT = 3000;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "AppManagerServiceChannel";

    private static HttpAppManagerServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AppManagerService created");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "AppManagerService started via onStartCommand");

        // Start as foreground service immediately to prevent system from killing it
        startForeground(NOTIFICATION_ID, createNotification());

        try {
            // Check if server is already running (from Application or previous Service start)
            HttpAppManagerServer existingServer = AppManagerApplication.getServer();
            if (existingServer != null) {
                server = existingServer;
                Log.i(TAG, "Using existing HTTP AppManagerServer from Application");
            } else if (server == null) {
                // Start new server instance
                server = new HttpAppManagerServer(getApplicationContext(), DEFAULT_PORT);
                server.start();
                Log.i(TAG, "HTTP AppManagerServer started on port " + DEFAULT_PORT + " from Service");
            } else {
                Log.i(TAG, "HTTP AppManagerServer already running in Service");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AppManagerServer from Service", e);
        }

        // Return START_STICKY to keep service running even if killed
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "AppManagerService destroyed");
        
        try {
            if (server != null) {
                try {
                    server.stop();
                    Log.i(TAG, "HTTP AppManagerServer stopped");
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping server", e);
                }
                server = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // This is a started service, not bound
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AppManagerServer Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps AppManagerServer running");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, LauncherActivity.class);
        PendingIntent pendingIntent;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
        } else {
            pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT
            );
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("AppManagerServer")
                .setContentText("Server running on port " + DEFAULT_PORT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    public static HttpAppManagerServer getServer() {
        return server;
    }
}

