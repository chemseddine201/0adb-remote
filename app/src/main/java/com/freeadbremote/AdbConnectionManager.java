package com.freeadbremote;

import android.content.Context;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton connection manager to share ADB connection between Service and Activity
 * This ensures commands work properly by using the same connection instance
 * 
 * IMPROVED: Now uses ConnectionState, ErrorHandler, and RetryManager for better architecture
 */
public class AdbConnectionManager {
    private static final AtomicReference<AdbConnectionManager> instance = new AtomicReference<>();
    
    // Static volatile variable to share connection state across the entire application
    // (Maintained for backward compatibility)
    public static volatile boolean isConnected = false;
    public static volatile boolean isConnecting = false;
    public static volatile String connectedHost = null;
    public static volatile int connectedPort = 0;
    
    // New centralized state management
    private final ConnectionState connectionState;
    private final ErrorHandler errorHandler;
    private final RetryManager retryManager;
    private final SettingsManager settingsManager;
    
    private AdbServerManager serverManager;
    private Context context;
    private AtomicBoolean isReconnecting = new AtomicBoolean(false);
    
    private AdbConnectionManager(Context context) {
        this.context = context.getApplicationContext();
        this.serverManager = new AdbServerManager(this.context);
        
        // Initialize new managers
        this.connectionState = new ConnectionState();
        this.errorHandler = new ErrorHandler(context);
        this.retryManager = new RetryManager(LogManager.getInstance(context));
        this.settingsManager = new SettingsManager(context);
        
        // Initialize static variables (for backward compatibility)
        isConnected = false;
        isConnecting = false;
        connectedHost = null;
        connectedPort = 0;
        
        // Sync ConnectionState with static variables
        syncStaticVariables();
    }
    
    /**
     * Sync static variables with ConnectionState
     */
    private void syncStaticVariables() {
        connectionState.addListener((state, host, port, error) -> {
            // Update static variables for backward compatibility
            isConnected = (state == ConnectionState.State.CONNECTED);
            isConnecting = (state == ConnectionState.State.CONNECTING);
            connectedHost = host;
            connectedPort = port;
        });
    }
    
    /**
     * Get ConnectionState instance
     */
    public ConnectionState getConnectionState() {
        return connectionState;
    }
    
    /**
     * Get ErrorHandler instance
     */
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }
    
    /**
     * Get RetryManager instance
     */
    public RetryManager getRetryManager() {
        return retryManager;
    }
    
    /**
     * Get SettingsManager instance
     */
    public SettingsManager getSettingsManager() {
        return settingsManager;
    }
    
    /**
     * Get singleton instance
     */
    public static AdbConnectionManager getInstance(Context context) {
        AdbConnectionManager manager = instance.get();
        if (manager == null) {
            manager = new AdbConnectionManager(context);
            if (instance.compareAndSet(null, manager)) {
                return manager;
            } else {
                return instance.get();
            }
        }
        return manager;
    }
    
    /**
     * Get the server manager (creates if needed)
     */
    public AdbServerManager getServerManager() {
        if (serverManager == null) {
            serverManager = new AdbServerManager(context);
        }
        return serverManager;
    }
    
    /**
     * Start connection
     * Reuses existing AdbClient instance to maintain one-time trust (same RSA keys)
     * IMPROVED: Uses ConnectionState and ErrorHandler
     */
    public void startConnection(String host, int port, AdbServerManager.ConnectionCallback callback) {
        LogManager logManager = LogManager.getInstance(context);
        
        // Update ConnectionState
        connectionState.setConnecting(host, port);
        
        // Update static variables (for backward compatibility)
        isConnecting = true;
        isConnected = false;
        connectedHost = host;
        connectedPort = port;
        
        // IMPORTANT: Reuse existing server manager and AdbClient to maintain same RSA keys
        // This ensures one-time trust is preserved - same keys = no prompt on reconnect
        // Only create new instance if it doesn't exist
        if (serverManager == null) {
            serverManager = new AdbServerManager(context);
            logManager.logInfo("Created new AdbServerManager instance");
        } else {
            // Reuse existing instance - this preserves the same AdbClient with same keys
            logManager.logInfo("Reusing existing AdbServerManager instance (maintains one-time trust)");
        }
        
        // Start connection using existing (or new) server manager
        // The AdbClient inside will reuse the same RSA keys from files
        serverManager.startConnection(host, port, new AdbServerManager.ConnectionCallback() {
            @Override
            public void onConnected() {
                // Update ConnectionState
                connectionState.setConnected(host, port);
                errorHandler.resetFailureCount(); // Reset on success
                
                // Update static variables (for backward compatibility)
                isConnected = true;
                isConnecting = false;
                connectedHost = host;
                connectedPort = port;
                
                if (callback != null) {
                    callback.onConnected();
                }
            }
            
            @Override
            public void onError(Exception e) {
                String errorMsg = e != null ? e.getMessage() : "Unknown error";
                
                // Update ConnectionState
                connectionState.setError(host, port, errorMsg);
                
                // Handle error with ErrorHandler
                errorHandler.handleError(errorMsg, ErrorHandler.ErrorType.CONNECTION_ERROR, 
                    (message, type, shouldRetry) -> {
                        logManager.logError("Connection error: " + message);
                    });
                
                // Update static variables (for backward compatibility)
                isConnected = false;
                isConnecting = false;
                
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }
    
    /**
     * Execute command using shared connection
     * Auto-reconnects if connection is lost - IMPROVED VERSION
     */
    public void executeCommand(String command, AdbServerManager.CommandCallback callback) {
        // CRITICAL: Force fresh connection state check every time
        // This ensures we immediately detect connection loss
        boolean actuallyConnected = false;
        if (serverManager != null) {
            actuallyConnected = serverManager.isConnected();
            // Synchronize flag with actual state
            isConnected = actuallyConnected;
        } else {
            isConnected = false;
        }
        
        // CRITICAL: If we don't have connection info but should, try to get it from serverManager
        if ((connectedHost == null || connectedPort == 0) && serverManager != null) {
            String info = serverManager.getConnectionInfo();
            if (info != null && info.contains(":")) {
                String[] parts = info.split(":");
                if (parts.length == 2) {
                    try {
                        connectedHost = parts[0];
                        connectedPort = Integer.parseInt(parts[1]);
                        LogManager.getInstance(context).logInfo("Retrieved connection info from serverManager: " + connectedHost + ":" + connectedPort);
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            }
        }
        
        if (serverManager != null && actuallyConnected) {
            // Connection appears good - execute command directly
            // But wrap callback to catch connection errors that occur during execution
            serverManager.executeCommand(command, new AdbServerManager.CommandCallback() {
                @Override
                public void onOutput(String output) {
                    if (callback != null) {
                        callback.onOutput(output);
                    }
                }
                
                @Override
                public void onError(String error) {
                    // CRITICAL: Check if this is a connection error - if so, trigger auto-reconnect
                    if (error != null && (error.contains("Not connected") || error.contains("connection") || 
                        error.contains("Socket") || error.contains("connection lost") || 
                        error.contains("Shell stream not ready") || error.contains("Socket closed"))) {
                        LogManager.getInstance(context).logInfo("Connection error detected during command execution: " + error);
                        // Update connection state
                        isConnected = false;
                        // Trigger auto-reconnect if we have connection info
                        if (connectedHost != null && connectedPort > 0) {
                            LogManager.getInstance(context).logInfo("Triggering auto-reconnect due to connection error");
                            triggerAutoReconnect(command, callback);
                            return; // Don't call original callback yet - wait for reconnect
                        }
                    }
                    // Not a connection error or can't reconnect - pass through
                    if (callback != null) {
                        callback.onError(error);
                    }
                }
                
                @Override
                public void onComplete(int exitCode) {
                    if (callback != null) {
                        callback.onComplete(exitCode);
                    }
                }
            });
        } else {
            // Connection is lost - try to auto-reconnect if we have connection info
            LogManager.getInstance(context).logInfo("Connection check: serverManager=" + (serverManager != null ? "exists" : "null") + 
                    ", actuallyConnected=" + actuallyConnected + ", connectedHost=" + connectedHost + ", connectedPort=" + connectedPort);
            
            if (connectedHost != null && connectedPort > 0) {
                // Trigger auto-reconnect
                triggerAutoReconnect(command, callback);
            } else {
                // No connection info - can't auto-reconnect
                // Update flag if connection is actually lost
                if (serverManager != null && !serverManager.isConnected()) {
                    isConnected = false;
                }
                String error = "Not connected - please connect first. Connection status: " + 
                              (serverManager != null ? "manager exists" : "no manager") + 
                              ", actuallyConnected: " + actuallyConnected;
                LogManager.getInstance(context).logWarn(error);
                if (callback != null) {
                    callback.onError(error);
                }
            }
        }
    }
    
    /**
     * Trigger auto-reconnect and execute command after reconnection
     */
    private void triggerAutoReconnect(String command, AdbServerManager.CommandCallback callback) {
        LogManager.getInstance(context).logInfo("Connection lost detected - triggering auto-reconnect to " + connectedHost + ":" + connectedPort);
        
        // Try to reconnect automatically (improved logic)
        if (isReconnecting.compareAndSet(false, true)) {
            // Start reconnection in background
            new Thread(() -> {
                        try {
                            LogManager.getInstance(context).logInfo("Starting auto-reconnect process...");
                            
                            // Ensure server manager exists (reuse if available to maintain keys)
                            if (serverManager == null) {
                                serverManager = new AdbServerManager(context);
                                LogManager.getInstance(context).logInfo("Created new AdbServerManager for reconnect");
                            } else {
                                LogManager.getInstance(context).logInfo("Reusing existing AdbServerManager (maintains one-time trust)");
                            }
                            
                            // Create a callback to track reconnection
                            final Object reconnectLock = new Object();
                            final boolean[] reconnectDone = {false};
                            final boolean[] reconnectSuccess = {false};
                            
                            // Attempt reconnection
                            String reconnectHost = connectedHost;
                            int reconnectPort = connectedPort;
                            startConnection(reconnectHost, reconnectPort, new AdbServerManager.ConnectionCallback() {
                                @Override
                                public void onConnected() {
                                    synchronized (reconnectLock) {
                                        reconnectSuccess[0] = true;
                                        reconnectDone[0] = true;
                                        isReconnecting.set(false);
                                        reconnectLock.notifyAll();
                                    }
                                    LogManager.getInstance(context).logInfo("Auto-reconnect successful - connection established");
                                }
                                
                                @Override
                                public void onError(Exception e) {
                                    synchronized (reconnectLock) {
                                        reconnectSuccess[0] = false;
                                        reconnectDone[0] = true;
                                        isReconnecting.set(false);
                                        reconnectLock.notifyAll();
                                    }
                                    LogManager.getInstance(context).logError("Auto-reconnect failed", e);
                                }
                            });
                            
                            // Wait for reconnection to complete (max 10 seconds)
                            synchronized (reconnectLock) {
                                int waitCount = 0;
                                while (!reconnectDone[0] && waitCount < 100) {
                                    reconnectLock.wait(100);
                                    waitCount++;
                                }
                            }
                            
                            // Check if reconnection was successful
                            // Wait a bit more to ensure connection is fully established
                            Thread.sleep(500);
                            
                            // Force a fresh connection check
                            boolean reconnected = isConnected();
                            if (reconnectSuccess[0] && reconnected) {
                                LogManager.getInstance(context).logInfo("Auto-reconnect completed successfully - executing command");
                                // Execute the command after successful reconnection
                                if (serverManager != null && serverManager.isConnected()) {
                                    serverManager.executeCommand(command, callback);
                                } else {
                                    String error = "Reconnected but server manager is not ready";
                                    LogManager.getInstance(context).logWarn(error);
                                    if (callback != null) {
                                        callback.onError(error);
                                    }
                                }
                            } else {
                                // Reconnection failed or timed out
                                String error = "Auto-reconnect failed or timed out. reconnectSuccess=" + reconnectSuccess[0] + 
                                            ", isConnected=" + reconnected;
                                LogManager.getInstance(context).logWarn(error);
                                if (callback != null) {
                                    callback.onError(error);
                                }
                            }
                        } catch (Exception e) {
                            LogManager.getInstance(context).logError("Error during auto-reconnect", e);
                            isReconnecting.set(false);
                            if (callback != null) {
                                callback.onError("Auto-reconnect error: " + e.getMessage());
                            }
                        }
                    }).start();
        } else {
            // Already reconnecting - wait for it to complete and then execute
            LogManager.getInstance(context).logInfo("Already reconnecting - waiting for completion...");
            new Thread(() -> {
                try {
                    // Wait up to 10 seconds for reconnection to complete
                    int waitCount = 0;
                    while (!isConnected() && waitCount < 100 && isReconnecting.get()) {
                        Thread.sleep(100);
                        waitCount++;
                    }
                    
                    if (isConnected() && serverManager != null) {
                        // Reconnected - execute command
                        LogManager.getInstance(context).logInfo("Reconnection completed - executing command");
                        serverManager.executeCommand(command, callback);
                    } else {
                        // Still not connected after waiting
                        String error = "Connection lost - reconnection still in progress or failed";
                        LogManager.getInstance(context).logWarn(error);
                        if (callback != null) {
                            callback.onError(error);
                        }
                    }
                } catch (Exception e) {
                    LogManager.getInstance(context).logError("Error waiting for reconnect", e);
                    if (callback != null) {
                        callback.onError("Error waiting for reconnect: " + e.getMessage());
                    }
                }
            }).start();
        }
    }
    
    /**
     * Check if connected - always check actual connection state, not just flag
     * IMPROVED: More robust connection state checking with optimistic approach
     */
    public boolean isConnected() {
        // First check static flag - if it's true, connection is likely active
        if (isConnected && serverManager != null) {
            // Double-check with server manager, but be optimistic
            boolean serverConnected = serverManager.isConnected();
            if (serverConnected) {
                return true;
            }
            // If static says connected but server doesn't, still return true if we have connection info
            // This handles cases where connection is establishing or temporarily checking
            if (connectedHost != null && connectedPort > 0) {
                return true;
            }
        }
        
        // If static flag is false, check server manager
        if (serverManager == null) {
            isConnected = false;
            return false;
        }
        
        // Check actual connection state from server manager
        boolean actuallyConnected = serverManager.isConnected();
        
        // Update static variable to match actual state
        // This ensures consistency between flag and actual state
        isConnected = actuallyConnected;
        if (!actuallyConnected) {
            isConnecting = false;
        }
        
        return actuallyConnected;
    }
    
    /**
     * Disconnect
     * IMPROVED: Updates ConnectionState
     */
    public void disconnect() {
        // Update ConnectionState
        connectionState.setDisconnected();
        
        // Update static variables (for backward compatibility)
        isConnected = false;
        isConnecting = false;
        connectedHost = null;
        connectedPort = 0;
        
        if (serverManager != null) {
            serverManager.shutdown();
            serverManager = null;
        }
        // Don't reset singleton here - let it be reused for reconnection
        // The serverManager will be recreated on next startConnection()
    }
    
    /**
     * Set connection info (for auto-reconnect when connection is lost)
     * This ensures we can auto-reconnect even if connection info wasn't set during startConnection
     */
    public void setConnectionInfo(String host, int port) {
        if (host != null && port > 0) {
            this.connectedHost = host;
            this.connectedPort = port;
            LogManager.getInstance(context).logInfo("Connection info stored for auto-reconnect: " + host + ":" + port);
        }
    }
    
    /**
     * Get connection info
     */
    public String getConnectionInfo() {
        if (isConnected() && connectedHost != null) {
            return connectedHost + ":" + connectedPort;
        }
        return "Not connected";
    }
    
    /**
     * Reset instance (for testing or full reset)
     */
    public static void reset() {
        AdbConnectionManager manager = instance.get();
        if (manager != null) {
            manager.disconnect();
        }
        instance.set(null);
    }
}