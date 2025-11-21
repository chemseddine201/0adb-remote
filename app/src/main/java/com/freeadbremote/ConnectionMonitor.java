package com.freeadbremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connection Monitor - Monitors ADB connection health and automatically reconnects
 * Uses low-resource heartbeat mechanism with configurable intervals
 */
public class ConnectionMonitor {
    private static final String TAG = "ConnectionMonitor";
    
    // Default heartbeat interval (30 seconds)
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000;
    
    // Minimum heartbeat interval (10 seconds) to prevent battery drain
    private static final long MIN_HEARTBEAT_INTERVAL_MS = 10_000;
    
    // Maximum heartbeat interval (120 seconds) for low-power mode
    private static final long MAX_HEARTBEAT_INTERVAL_MS = 120_000;
    
    private final Context context;
    private final AdbConnectionManager connectionManager;
    private final LogManager logManager;
    private final SettingsManager settingsManager;
    private final Handler mainHandler;
    
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);
    
    private Runnable heartbeatRunnable;
    private long heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL_MS;
    
    public ConnectionMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.connectionManager = AdbConnectionManager.getInstance(context);
        this.logManager = LogManager.getInstance(context);
        this.settingsManager = new SettingsManager(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // Load heartbeat interval from settings
        this.heartbeatInterval = Math.max(MIN_HEARTBEAT_INTERVAL_MS, 
            Math.min(MAX_HEARTBEAT_INTERVAL_MS, 
                settingsManager.getHeartbeatInterval()));
    }
    
    /**
     * Start monitoring connection health
     */
    public void startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            logManager.logInfo(TAG + ": Starting connection monitoring with interval: " + heartbeatInterval + "ms");
            scheduleHeartbeat();
        } else {
            logManager.logDebug(TAG + ": Monitoring already started");
        }
    }
    
    /**
     * Stop monitoring connection health
     */
    public void stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            logManager.logInfo(TAG + ": Stopping connection monitoring");
            if (heartbeatRunnable != null) {
                mainHandler.removeCallbacks(heartbeatRunnable);
                heartbeatRunnable = null;
            }
        }
    }
    
    /**
     * Schedule next heartbeat check
     */
    private void scheduleHeartbeat() {
        if (!isMonitoring.get()) {
            return;
        }
        
        heartbeatRunnable = () -> {
            if (!isMonitoring.get()) {
                return;
            }
            
            performHeartbeat();
            scheduleHeartbeat(); // Schedule next heartbeat
        };
        
        mainHandler.postDelayed(heartbeatRunnable, heartbeatInterval);
    }
    
    /**
     * Perform heartbeat check - verify connection is alive
     */
    private void performHeartbeat() {
        if (!isMonitoring.get()) {
            return;
        }
        
        // Check if we should be connected
        String expectedHost = AdbConnectionManager.connectedHost;
        int expectedPort = AdbConnectionManager.connectedPort;
        
        if (expectedHost == null || expectedPort == 0) {
            logManager.logDebug(TAG + ": Heartbeat: No expected connection, skipping check");
            return;
        }
        
        // Check current connection state
        boolean isConnected = AdbConnectionManager.isConnected;
        
        if (!isConnected && !isReconnecting.get()) {
            logManager.logWarn(TAG + ": Heartbeat: Connection lost, attempting reconnect");
            reconnect(expectedHost, expectedPort);
        } else if (isConnected) {
            // Verify connection is actually working with a simple command
            verifyConnection();
        }
    }
    
    /**
     * Verify connection is actually working
     */
    private void verifyConnection() {
        // Use a lightweight command to verify connection
        connectionManager.executeCommand("echo 'heartbeat'", 
            new AdbServerManager.CommandCallback() {
                @Override
                public void onOutput(String out) {
                    // Connection is working
                    logManager.logDebug(TAG + ": Heartbeat: Connection verified");
                }
                
                @Override
                public void onError(String error) {
                    logManager.logWarn(TAG + ": Heartbeat: Connection verification failed: " + error);
                    // Connection might be dead, attempt reconnect
                    if (AdbConnectionManager.connectedHost != null && 
                        AdbConnectionManager.connectedPort != 0) {
                        reconnect(AdbConnectionManager.connectedHost, 
                                 AdbConnectionManager.connectedPort);
                    }
                }
                
                @Override
                public void onComplete(int exitCode) {
                    // Heartbeat check complete
                }
            });
    }
    
    /**
     * Reconnect to the last known host/port
     */
    private void reconnect(String host, int port) {
        if (isReconnecting.compareAndSet(false, true)) {
            logManager.logInfo(TAG + ": Reconnecting to " + host + ":" + port);
            
            // Use background thread to avoid blocking
            new Thread(() -> {
                try {
                    connectionManager.startConnection(host, port, 
                        new AdbServerManager.ConnectionCallback() {
                            @Override
                            public void onConnected() {
                                logManager.logInfo(TAG + ": Reconnection successful");
                                isReconnecting.set(false);
                            }
                            
                            @Override
                            public void onError(Exception e) {
                                logManager.logWarn(TAG + ": Reconnection failed: " + 
                                    (e != null ? e.getMessage() : "Unknown error"));
                                isReconnecting.set(false);
                                // Will retry on next heartbeat
                            }
                        });
                } catch (Exception e) {
                    logManager.logError(TAG + ": Reconnection exception: " + e.getMessage(), e);
                    isReconnecting.set(false);
                }
            }).start();
        } else {
            logManager.logDebug(TAG + ": Reconnection already in progress");
        }
    }
    
    /**
     * Force immediate connection check and reconnect if needed
     * Called when app returns to foreground
     */
    public void checkAndReconnect() {
        if (!isMonitoring.get()) {
            return;
        }
        
        logManager.logInfo(TAG + ": Force checking connection (app resumed)");
        
        String expectedHost = AdbConnectionManager.connectedHost;
        int expectedPort = AdbConnectionManager.connectedPort;
        
        if (expectedHost != null && expectedPort != 0) {
            boolean isConnected = AdbConnectionManager.isConnected;
            
            if (!isConnected && !isReconnecting.get()) {
                logManager.logInfo(TAG + ": App resumed but not connected, reconnecting immediately");
                reconnect(expectedHost, expectedPort);
            } else if (isConnected) {
                // Verify connection is working
                verifyConnection();
            }
        }
    }
    
    /**
     * Update heartbeat interval
     */
    public void setHeartbeatInterval(long intervalMs) {
        this.heartbeatInterval = Math.max(MIN_HEARTBEAT_INTERVAL_MS, 
            Math.min(MAX_HEARTBEAT_INTERVAL_MS, intervalMs));
        
        // Restart monitoring with new interval
        if (isMonitoring.get()) {
            stopMonitoring();
            startMonitoring();
        }
        
        logManager.logInfo("Heartbeat interval updated to: " + this.heartbeatInterval + "ms");
    }
    
    /**
     * Get current heartbeat interval
     */
    public long getHeartbeatInterval() {
        return heartbeatInterval;
    }
    
    /**
     * Check if monitoring is active
     */
    public boolean isMonitoring() {
        return isMonitoring.get();
    }
}

