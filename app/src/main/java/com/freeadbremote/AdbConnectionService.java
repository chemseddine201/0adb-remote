package com.freeadbremote.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.freeadbremote.AdbConnectionManager;
import com.freeadbremote.AdbServerManager;
import com.freeadbremote.ControlActivity;
import com.freeadbremote.R;

public class AdbConnectionService extends Service {
    private static final String TAG = "AdbConnectionService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "AdbConnectionServiceChannel";
    
    // Connection persistence
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "AdbConnectionPrefs";
    private static final String KEY_HOST = "last_host";
    private static final String KEY_PORT = "last_port";
    
    private AdbConnectionManager connectionManager;
    private boolean isConnected = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AdbConnectionService onCreate");
        
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        connectionManager = AdbConnectionManager.getInstance(this);
        
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand called");
        
        if (intent == null) {
            Log.w(TAG, "onStartCommand called with null intent - attempting to restore connection");
            restorePreviousConnection();
            return START_STICKY;
        }

        String action = intent.getStringExtra("action");
        String host = intent.getStringExtra("host");
        int port = intent.getIntExtra("port", 5555);

        Log.i(TAG, "Action: " + action + ", Host: " + host + ", Port: " + port);

        if ("start".equals(action)) {
            // Save connection details for restoration
            saveConnectionDetails(host, port);
            startConnection(host, port);
        } else if ("stop".equals(action)) {
            stopConnection();
            stopSelf();
        } else {
            Log.w(TAG, "Unknown action: " + action);
        }

        // Return STICKY to ensure service restarts if killed
        return START_STICKY;
    }

    private void saveConnectionDetails(String host, int port) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_HOST, host);
        editor.putInt(KEY_PORT, port);
        editor.apply();
        Log.d(TAG, "Saved connection details: " + host + ":" + port);
    }

    private void restorePreviousConnection() {
        String host = prefs.getString(KEY_HOST, null);
        int port = prefs.getInt(KEY_PORT, 0);
        
        if (host != null && port != 0) {
            Log.i(TAG, "Restoring previous connection: " + host + ":" + port);
            startConnection(host, port);
        } else {
            Log.w(TAG, "No previous connection to restore");
            // Keep service alive but don't attempt connection
            startForeground(NOTIFICATION_ID, createNotification("ADB Remote - Service Ready"));
        }
    }

    private void startConnection(String host, int port) {
        Log.i(TAG, "Starting connection to " + host + ":" + port);
        
        startForeground(NOTIFICATION_ID, createNotification("ADB Remote - Connected to " + host));

        new Thread(() -> {
            try {
                connectionManager.startConnection(host, port, new AdbServerManager.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        Log.i(TAG, "Connection callback: onConnected");
                        isConnected = true;
                        updateNotification("ADB Remote - Connected to " + host);
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Connection callback: onError - " + (e != null ? e.getMessage() : "Unknown error"));
                        isConnected = false;
                        updateNotification("ADB Remote - Connection Error");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Connection failed: " + e.getMessage());
                isConnected = false;
                updateNotification("ADB Remote - Connection Failed");
            }
        }).start();
    }

    private void stopConnection() {
        Log.i(TAG, "Stopping connection");
        
        if (connectionManager != null) {
            connectionManager.disconnect();
        }
        
        isConnected = false;
        stopForeground(true);
        
        // Clear saved connection details
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(KEY_HOST);
        editor.remove(KEY_PORT);
        editor.apply();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "onTaskRemoved - app removed from recent tasks");
        // Don't stop service when app is removed from recent tasks
        // This keeps the ADB connection alive
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "AdbConnectionService onDestroy");
        stopConnection();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "ADB Connection Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, ControlActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ADB Remote")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_remote_control)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(text));
        }
    }
}