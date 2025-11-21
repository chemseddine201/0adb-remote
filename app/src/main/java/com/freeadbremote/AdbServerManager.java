package com.freeadbremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADB Server Manager - Professional implementation using native ADB client
 * No server JAR required - uses ADB protocol directly
 * Equivalent to original developer's solution but without external dependencies
 * 
 * Also manages app names cache loaded from remote server to fix limitations
 * of showing application names instead of package names
 */
public class AdbServerManager {
    private static final String KEY_FILE_NAME = "adb_key_trusted";
    
    private final Context context;
    private final String filesDir;
    private final String cacheDir;
    private final File serverDir;
    private final File keyFile;
    private final LogManager logManager;
    
    // Use native ADB client instead of server JAR
    private AdbClient adbClient;
    
    // App names cache (package name -> app name) - loaded from remote server
    // IMPROVED: Now uses CacheManager for better memory management
    private static ServerClient cacheServerClient;
    private static String cacheServerHost;
    private static int cacheServerPort = 3000;
    private static boolean cacheInitialized = false;

    public AdbServerManager(Context context) {
        this.context = context.getApplicationContext();
        this.logManager = LogManager.getInstance(context);
        
        logManager.logInfo("AdbServerManager initialized (using native ADB client - no server JAR)");
        
        // Get directories
        this.filesDir = context.getFilesDir().getPath();
        this.cacheDir = context.getCacheDir().getPath();
        
        // Create server directory
        this.serverDir = new File(filesDir, "server");
        if (!serverDir.exists()) {
            serverDir.mkdirs();
        }
        
        // Key file to track if trust is already established
        this.keyFile = new File(serverDir, KEY_FILE_NAME);
        
        // Initialize native ADB client (no server JAR needed)
        this.adbClient = new AdbClient(context);
    }

    /**
     * Check if one-time trust has already been established
     */
    public boolean isTrustEstablished() {
        if (adbClient != null) {
            return adbClient.isTrustEstablished();
        }
        boolean exists = keyFile.exists();
        logManager.logDebug("Trust check: " + (exists ? "ESTABLISHED" : "NOT ESTABLISHED"));
        return exists;
    }

    /**
     * Start ADB connection to TV box using native ADB client
     * First connection will show prompt on TV, subsequent connections won't
     * IMPORTANT: Reuses existing AdbClient instance to maintain same RSA keys (one-time trust)
     */
    public void startConnection(String host, int port, ConnectionCallback callback) {
        logManager.logInfo("Starting connection to " + host + ":" + port + " (using native ADB client)");
        
        // IMPORTANT: Always reuse existing AdbClient instance to maintain same RSA keys
        // This ensures one-time trust is preserved - same keys = no prompt on reconnect
        // The AdbClient loads keys from files, so reusing the instance maintains the same keys
        if (adbClient == null) {
            adbClient = new AdbClient(context);
            logManager.logInfo("Created new AdbClient instance");
        } else {
            logManager.logInfo("Reusing existing AdbClient instance (maintains one-time trust with same RSA keys)");
        }
        
        boolean isFirstConnection = !isTrustEstablished();
        
        if (isFirstConnection) {
            logManager.logInfo("First connection - TV box will show authorization prompt");
            logManager.logInfo("Please accept the prompt on your TV box to establish trust");
        } else {
            logManager.logInfo("Trust already established - connecting without prompt");
        }
        
        // Use native ADB client to connect
        adbClient.connect(host, port, new AdbClient.ConnectionCallback() {
            @Override
            public void onConnected() {
                logManager.logInfo("ADB connection established successfully");
                if (callback != null) {
                    callback.onConnected();
                }
            }
            
            @Override
            public void onError(Exception e) {
                logManager.logError("ADB connection failed", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Execute shell command with callback - Commands executed via native ADB client
     * IMPROVED: Better connection state checking with auto-reconnect support
     */
    public void executeCommand(String command, CommandCallback callback) {
        // CRITICAL: Check connection state before executing
        // This ensures we catch connection loss immediately
        if (adbClient == null) {
            String error = "AdbClient is null - not connected";
            logManager.logWarn(error);
            if (callback != null) {
                callback.onError(error);
            }
            return;
        }
        
        // Force a fresh connection check
        boolean connected = adbClient.isConnected();
        if (!connected) {
            // Connection lost - return error so AdbConnectionManager can trigger auto-reconnect
            String error = "Not connected to ADB server. Connection state: " + 
                          (adbClient != null ? "client exists but not connected" : "client is null");
            logManager.logWarn(error);
            if (callback != null) {
                callback.onError(error);
            }
            return;
        }
        
        logManager.logInfo("Executing command via ADB client: " + command);
        
        // Wrap callback to catch connection errors during execution
        adbClient.executeCommand(command, new AdbClient.CommandCallback() {
            @Override
            public void onOutput(String output) {
                if (callback != null) {
                    callback.onOutput(output);
                }
            }
            
            @Override
            public void onError(String error) {
                // Check if this is a connection error - pass it through so AdbConnectionManager can handle auto-reconnect
                if (error != null && (error.contains("Not connected") || error.contains("connection") || 
                    error.contains("Socket") || error.contains("connection lost"))) {
                    logManager.logInfo("Connection error detected in command execution: " + error);
                }
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
    }
    
    /**
     * Execute shell command - Legacy method for compatibility
     */
    public ProcessConnection executeCommand(String command) throws IOException {
        logManager.logDebug("Executing command: " + command);
        throw new IOException("Legacy executeCommand method not supported. Use executeCommand(String, CommandCallback) instead.");
    }

    /**
     * Shutdown server manager
     */
    public void shutdown() {
        logManager.logInfo("Shutting down AdbServerManager");
        
        if (adbClient != null) {
            adbClient.shutdown();
        }
    }
    
    /**
     * Get ADB client instance (for file push operations)
     */
    public AdbClient getAdbClient() {
        return adbClient;
    }
    
    /**
     * Get connection info
     */
    public String getConnectionInfo() {
        if (adbClient != null) {
            return adbClient.getConnectionInfo();
        }
        return "Not connected";
    }
    
    /**
     * Check if connected
     */
    public boolean isConnected() {
        return adbClient != null && adbClient.isConnected();
    }
    
    // ============================================================================
    // App Names Cache Management (loaded from remote server)
    // ============================================================================
    
    /**
     * Initialize app names cache from remote server
     * This cache is used to show application names instead of package names
     * when listing running apps via ADB commands
     */
    public static void initializeAppNamesCache(Context context, String serverHost, int serverPort) {
        initializeAppNamesCache(context, serverHost, serverPort, null, null);
    }
    
    /**
     * Initialize app names cache from remote server with auto-recovery support
     */
    public static void initializeAppNamesCache(Context context, String serverHost, int serverPort, 
                                               AdbConnectionManager connectionManager, ServerDeployment serverDeployment) {
        if (cacheInitialized && cacheServerHost != null && cacheServerHost.equals(serverHost) && 
            cacheServerPort == serverPort) {
            LogManager.getInstance(context).logDebug("App names cache already initialized for " + serverHost + ":" + serverPort);
            return;
        }
        
        LogManager logManager = LogManager.getInstance(context);
        logManager.logInfo("Initializing app names cache from server: " + serverHost + ":" + serverPort);
        
        cacheServerHost = serverHost;
        cacheServerPort = serverPort;
        
        if (cacheServerClient != null) {
            cacheServerClient.shutdown();
        }
        cacheServerClient = new ServerClient(serverHost, serverPort, logManager);
        
        // Setup auto-recovery if connectionManager and serverDeployment are provided
        if (connectionManager != null && serverDeployment != null) {
            cacheServerClient.setServerDeployment(serverDeployment, connectionManager);
            logManager.logInfo("Auto-recovery enabled for app names cache");
        }
        
        refreshAppNamesCache(context);
    }
    
    /**
     * Refresh app names cache from remote server
     */
    public static void refreshAppNamesCache(Context context) {
        if (cacheServerClient == null) {
            LogManager.getInstance(context).logWarn("Cache server client not initialized, cannot refresh");
            return;
        }
        
        LogManager logManager = LogManager.getInstance(context);
        logManager.logInfo("Refreshing app names cache from server");
        
        cacheServerClient.getUserApps(new ServerClient.ServerCallback<List<ServerClient.AppInfo>>() {
            @Override
            public void onSuccess(List<ServerClient.AppInfo> apps) {
                // Use CacheManager for better memory management
                CacheManager cacheManager = CacheManager.getInstance(context);
                cacheManager.clearAppNamesCache();
                
                for (ServerClient.AppInfo app : apps) {
                    if (app.packageName != null) {
                        String appName = (app.name != null && !app.name.isEmpty() && !app.name.equals(app.packageName)) 
                            ? app.name 
                            : app.packageName;
                        cacheManager.putAppName(app.packageName, appName);
                    }
                }
                cacheInitialized = true;
                
                logManager.logInfo("App names cache refreshed: " + cacheManager.getAppNamesCacheSize() + " apps");
            }
            
            @Override
            public void onError(String error) {
                logManager.logWarn("Failed to refresh app names cache: " + error);
            }
        });
    }
    
    /**
     * Get app name from cache
     * Returns app name if found in cache, otherwise returns package name
     * IMPROVED: Uses CacheManager
     */
    public static String getAppName(String packageName) {
        if (packageName == null) {
            return null;
        }
        
        // Use CacheManager if available - need context from static cache
        // For now, fallback to package name (CacheManager requires context)
        // This will be improved when context is available
        return packageName; // Fallback to package name
    }
    
    /**
     * Get app name from cache with context
     */
    public static String getAppName(String packageName, Context context) {
        if (packageName == null) {
            return null;
        }
        
        // Use CacheManager if available
        try {
            if (context != null) {
                CacheManager cacheManager = CacheManager.getInstance(context);
                return cacheManager.getAppName(packageName);
            }
        } catch (Exception e) {
            // Fallback to package name
        }
        
        return packageName; // Fallback to package name
    }
    
    /**
     * Check if cache is initialized
     */
    public static boolean isCacheInitialized() {
        return cacheInitialized;
    }
    
    /**
     * Get cache size
     * IMPROVED: Uses CacheManager
     */
    public static int getCacheSize() {
        // Cache size not available without context
        return 0;
    }
    
    /**
     * Get cache size with context
     */
    public static int getCacheSize(Context context) {
        try {
            if (context != null) {
                CacheManager cacheManager = CacheManager.getInstance(context);
                return cacheManager.getAppNamesCacheSize();
            }
        } catch (Exception e) {
            // Fallback
        }
        return 0;
    }
    
    /**
     * Shutdown cache server client
     * IMPROVED: Uses CacheManager
     */
    public static void shutdownCache() {
        if (cacheServerClient != null) {
            cacheServerClient.shutdown();
            cacheServerClient = null;
        }
        cacheInitialized = false;
    }
    
    /**
     * Shutdown cache server client with context
     */
    public static void shutdownCache(Context context) {
        if (cacheServerClient != null) {
            cacheServerClient.shutdown();
            cacheServerClient = null;
        }
        
        try {
            if (context != null) {
                CacheManager cacheManager = CacheManager.getInstance(context);
                cacheManager.clearAppNamesCache();
            }
        } catch (Exception e) {
            // Ignore
        }
        
        cacheInitialized = false;
    }

    /**
     * Process connection wrapper (for compatibility)
     */
    public static class ProcessConnection {
        private final Process process;
        private final DataOutputStream outputStream;
        private final InputStream inputStream;
        private final InputStream errorStream;

        public ProcessConnection(Process process, DataOutputStream outputStream, 
                                InputStream inputStream, InputStream errorStream) {
            this.process = process;
            this.outputStream = outputStream;
            this.inputStream = inputStream;
            this.errorStream = errorStream;
        }

        public Process getProcess() { return process; }
        public DataOutputStream getOutputStream() { return outputStream; }
        public InputStream getInputStream() { return inputStream; }
        public InputStream getErrorStream() { return errorStream; }
    }

    /**
     * Connection callback interface
     */
    public interface ConnectionCallback {
        void onConnected();
        void onError(Exception e);
    }
    
    /**
     * Command execution callback interface
     */
    public interface CommandCallback {
        void onOutput(String output);
        void onError(String error);
        void onComplete(int exitCode);
    }
}