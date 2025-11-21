package com.freeadbremote;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP client for communicating with AppManagerServer on the device
 * Provides alternative to ADB commands for app management
 */
public class ServerClient {
    private static final Gson gson = new GsonBuilder().create();
    private static final int DEFAULT_PORT = 3000; // Updated to match server port
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 10000;
    
    private final String serverHost;
    private final int serverPort;
    private final LogManager logManager;
    private final ExecutorService executor;
    private ServerDeployment serverDeployment;
    private AdbConnectionManager connectionManager;
    private RetryManager retryManager;
    private ErrorHandler errorHandler;
    
    public interface ServerCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }
    
    public ServerClient(String host, int port, LogManager logManager) {
        this.serverHost = host;
        this.serverPort = port;
        this.logManager = logManager;
        this.executor = Executors.newCachedThreadPool();
        this.retryManager = new RetryManager(logManager);
        // ErrorHandler will be set via setErrorHandlers if Context is available
    }
    
    /**
     * Set ErrorHandler and RetryManager (for better error handling)
     */
    public void setErrorHandlers(ErrorHandler errorHandler, RetryManager retryManager) {
        this.errorHandler = errorHandler;
        if (retryManager != null) {
            this.retryManager = retryManager;
        }
    }
    
    public ServerClient(String host, LogManager logManager) {
        this(host, DEFAULT_PORT, logManager);
    }
    
    /**
     * Set ServerDeployment instance for auto-recovery
     */
    public void setServerDeployment(ServerDeployment deployment, AdbConnectionManager connectionManager) {
        this.serverDeployment = deployment;
        this.connectionManager = connectionManager;
    }
    
    /**
     * Check if server is available with auto-recovery
     */
    public void checkHealth(ServerCallback<Map<String, Object>> callback) {
        executor.execute(() -> {
            try {
                String response = makeRequest("GET", "/api/health", null);
                Type type = new TypeToken<Map<String, Object>>(){}.getType();
                Map<String, Object> result = gson.fromJson(response, type);
                new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                logManager.logError("Health check failed: " + e.getMessage(), e);
                // Auto-recovery: check if app is installed and start/redeploy
                handleRequestFailure("health check", () -> {
                    // Retry after recovery
                    try {
                        Thread.sleep(2000); // Wait for server to start
                        String response = makeRequest("GET", "/api/health", null);
                        Type type = new TypeToken<Map<String, Object>>(){}.getType();
                        Map<String, Object> result = gson.fromJson(response, type);
                        new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(result));
                    } catch (Exception retryEx) {
                        logManager.logError("Health check retry failed: " + retryEx.getMessage(), retryEx);
                        new Handler(Looper.getMainLooper()).post(() -> callback.onError(retryEx.getMessage()));
                    }
                }, () -> {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onError(e.getMessage()));
                });
            }
        });
    }
    
    /**
     * Get list of user-installed apps with auto-recovery
     */
    public void getUserApps(ServerCallback<List<AppInfo>> callback) {
        executor.execute(() -> {
            try {
                String response = makeRequest("GET", "/api/apps/user", null);
                Map<String, Object> json = gson.fromJson(response, Map.class);
                List<Map<String, Object>> appsData = (List<Map<String, Object>>) json.get("apps");
                
                List<AppInfo> apps = new ArrayList<>();
                if (appsData != null) {
                    for (Map<String, Object> app : appsData) {
                        apps.add(parseAppInfo(app));
                    }
                }
                
                new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(apps));
            } catch (Exception e) {
                logManager.logError("Failed to get user apps: " + e.getMessage(), e);
                // Auto-recovery: check if app is installed and start/redeploy
                handleRequestFailure("get user apps", () -> {
                    // Retry after recovery
                    try {
                        Thread.sleep(2000); // Wait for server to start
                        String response = makeRequest("GET", "/api/apps/user", null);
                        Map<String, Object> json = gson.fromJson(response, Map.class);
                        List<Map<String, Object>> appsData = (List<Map<String, Object>>) json.get("apps");
                        
                        List<AppInfo> apps = new ArrayList<>();
                        if (appsData != null) {
                            for (Map<String, Object> app : appsData) {
                                apps.add(parseAppInfo(app));
                            }
                        }
                        
                        new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(apps));
                    } catch (Exception retryEx) {
                        logManager.logError("Get user apps retry failed: " + retryEx.getMessage(), retryEx);
                        new Handler(Looper.getMainLooper()).post(() -> callback.onError(retryEx.getMessage()));
                    }
                }, () -> {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onError(e.getMessage()));
                });
            }
        });
    }
    
    /**
     * Get list of all installed apps (including system) with auto-recovery
     */
    public void getAllApps(ServerCallback<List<AppInfo>> callback) {
        executor.execute(() -> {
            try {
                String response = makeRequest("GET", "/api/apps/all", null);
                Map<String, Object> json = gson.fromJson(response, Map.class);
                List<Map<String, Object>> appsData = (List<Map<String, Object>>) json.get("apps");
                
                List<AppInfo> apps = new ArrayList<>();
                if (appsData != null) {
                    for (Map<String, Object> app : appsData) {
                        apps.add(parseAppInfo(app));
                    }
                }
                
                new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(apps));
            } catch (Exception e) {
                logManager.logError("Failed to get all apps: " + e.getMessage(), e);
                // Auto-recovery: check if app is installed and start/redeploy
                handleRequestFailure("get all apps", () -> {
                    // Retry after recovery
                    try {
                        Thread.sleep(2000); // Wait for server to start
                        String response = makeRequest("GET", "/api/apps/all", null);
                        Map<String, Object> json = gson.fromJson(response, Map.class);
                        List<Map<String, Object>> appsData = (List<Map<String, Object>>) json.get("apps");
                        
                        List<AppInfo> apps = new ArrayList<>();
                        if (appsData != null) {
                            for (Map<String, Object> app : appsData) {
                                apps.add(parseAppInfo(app));
                            }
                        }
                        
                        new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(apps));
                    } catch (Exception retryEx) {
                        logManager.logError("Get all apps retry failed: " + retryEx.getMessage(), retryEx);
                        new Handler(Looper.getMainLooper()).post(() -> callback.onError(retryEx.getMessage()));
                    }
                }, () -> {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onError(e.getMessage()));
                });
            }
        });
    }
    
    /**
     * Handle HTTP request failure with auto-recovery
     * Checks if app is installed, starts it if installed, or redeploys if not
     */
    private void handleRequestFailure(String operation, Runnable onRecoverySuccess, Runnable onRecoveryFailure) {
        if (serverDeployment == null || connectionManager == null) {
            logManager.logWarn("ServerDeployment not set - cannot auto-recover from " + operation + " failure");
            onRecoveryFailure.run();
            return;
        }
        
        logManager.logInfo("HTTP request failed for " + operation + " - attempting auto-recovery...");
        
        // Check if app is installed
        serverDeployment.checkIfInstalled(new ServerDeployment.DeploymentCallback() {
            @Override
            public void onSuccess(String packageName) {
                logManager.logInfo("App is installed - attempting to start it...");
                // App is installed, try to start it
                serverDeployment.startInstalledApk(new ServerDeployment.DeploymentCallback() {
                    @Override
                    public void onSuccess(String pkg) {
                        logManager.logInfo("App started successfully - retrying " + operation);
                        onRecoverySuccess.run();
                    }
                    
                    @Override
                    public void onError(String error) {
                        logManager.logError("Failed to start installed app: " + error);
                        // Try restart instead
                        serverDeployment.restartInstalledApk(new ServerDeployment.DeploymentCallback() {
                            @Override
                            public void onSuccess(String pkg) {
                                logManager.logInfo("App restarted successfully - retrying " + operation);
                                onRecoverySuccess.run();
                            }
                            
                            @Override
                            public void onError(String restartError) {
                                logManager.logError("Failed to restart app: " + restartError);
                                onRecoveryFailure.run();
                            }
                        });
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                logManager.logInfo("App is NOT installed - deploying...");
                // App is not installed, deploy it
                serverDeployment.deployAndStartServer(new ServerDeployment.DeploymentCallback() {
                    @Override
                    public void onSuccess(String pkg) {
                        logManager.logInfo("App deployed and started successfully - retrying " + operation);
                        onRecoverySuccess.run();
                    }
                    
                    @Override
                    public void onError(String deployError) {
                        logManager.logError("Failed to deploy app: " + deployError);
                        onRecoveryFailure.run();
                    }
                });
            }
        });
    }
    
    
    private String makeRequest(String method, String path, String body) throws Exception {
        String urlString = "http://" + serverHost + ":" + serverPort + path;
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Content-Type", "application/json");
            
            if (body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    return response.toString();
                }
            } else {
                throw new Exception("HTTP " + responseCode + ": " + conn.getResponseMessage());
            }
        } finally {
            conn.disconnect();
        }
    }
    
    public void shutdown() {
        executor.shutdown();
    }
    
    /**
     * Parse AppInfo from server response
     */
    private AppInfo parseAppInfo(Map<String, Object> app) {
        AppInfo info = new AppInfo();
        info.packageName = (String) app.get("package");
        info.name = (String) app.get("name");
        if (info.name == null || info.name.isEmpty()) {
            info.name = info.packageName; // Fallback to package name
        }
        
        Object versionName = app.get("versionName");
        if (versionName != null) {
            info.versionName = versionName.toString();
        }
        
        Object versionCode = app.get("versionCode");
        if (versionCode != null) {
            if (versionCode instanceof Number) {
                info.versionCode = ((Number) versionCode).longValue();
            } else {
                try {
                    info.versionCode = Long.parseLong(versionCode.toString());
                } catch (NumberFormatException e) {
                    info.versionCode = 0;
                }
            }
        }
        
        Object isSystem = app.get("isSystem");
        if (isSystem instanceof Boolean) {
            info.isSystem = (Boolean) isSystem;
        }
        
        Object icon = app.get("icon");
        if (icon != null) {
            info.iconBase64 = icon.toString();
        }
        
        return info;
    }
    
    // Data classes
    public static class AppInfo {
        public String packageName;
        public String name;
        public String versionName;
        public long versionCode;
        public boolean isSystem;
        public String iconBase64;
        
        public AppInfo() {
        }
        
        public AppInfo(String packageName, String name, String versionName, long versionCode) {
            this.packageName = packageName;
            this.name = name;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }
    }
}


