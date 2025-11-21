package com.freeadbremote;

import android.os.Handler;
import android.os.Looper;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Server discovery utility
 * Helps find and connect to the AppManagerServer on the device
 */
public class ServerDiscovery {
    private static final int DEFAULT_PORT = 8080;
    private static final int CONNECTION_TIMEOUT = 2000; // 2 seconds
    
    private final LogManager logManager;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    public interface DiscoveryCallback {
        void onServerFound(String host, int port);
        void onServerNotFound();
    }
    
    public ServerDiscovery(LogManager logManager) {
        this.logManager = logManager;
        this.executor = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Discover server by trying common host addresses
     */
    public void discoverServer(DiscoveryCallback callback) {
        executor.execute(() -> {
            // Try localhost first (if running on same device)
            if (tryConnection("localhost", DEFAULT_PORT)) {
                logManager.logInfo("Server found at localhost:" + DEFAULT_PORT);
                mainHandler.post(() -> callback.onServerFound("localhost", DEFAULT_PORT));
                return;
            }
            
            // Try common local network addresses
            String[] commonHosts = {
                "192.168.1.100",
                "192.168.1.101",
                "192.168.0.100",
                "192.168.0.101",
                "10.0.0.2",
                "10.0.0.100"
            };
            
            // Also try to get device IP from ADB connection
            String adbHost = AdbConnectionManager.connectedHost;
            if (adbHost != null && !adbHost.isEmpty() && !adbHost.equals("localhost")) {
                if (tryConnection(adbHost, DEFAULT_PORT)) {
                    logManager.logInfo("Server found at " + adbHost + ":" + DEFAULT_PORT);
                    mainHandler.post(() -> callback.onServerFound(adbHost, DEFAULT_PORT));
                    return;
                }
            }
            
            // Try common hosts
            for (String host : commonHosts) {
                if (tryConnection(host, DEFAULT_PORT)) {
                    logManager.logInfo("Server found at " + host + ":" + DEFAULT_PORT);
                    mainHandler.post(() -> callback.onServerFound(host, DEFAULT_PORT));
                    return;
                }
            }
            
            logManager.logWarn("Server not found on any common address");
            mainHandler.post(callback::onServerNotFound);
        });
    }
    
    /**
     * Try to connect to server at given host and port
     */
    public void tryConnection(String host, int port, DiscoveryCallback callback) {
        executor.execute(() -> {
            if (tryConnection(host, port)) {
                logManager.logInfo("Server found at " + host + ":" + port);
                mainHandler.post(() -> callback.onServerFound(host, port));
            } else {
                logManager.logWarn("Server not found at " + host + ":" + port);
                mainHandler.post(callback::onServerNotFound);
            }
        });
    }
    
    /**
     * Test if server is reachable at given host and port
     */
    private boolean tryConnection(String host, int port) {
        try {
            Socket socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), CONNECTION_TIMEOUT);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get device IP from ADB connection
     */
    public static String getDeviceIP() {
        String adbHost = AdbConnectionManager.connectedHost;
        if (adbHost != null && !adbHost.isEmpty() && !adbHost.equals("localhost")) {
            return adbHost;
        }
        return null;
    }
    
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

