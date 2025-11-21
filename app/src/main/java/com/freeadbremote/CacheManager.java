package com.freeadbremote;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.LruCache;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized cache management with LRU eviction and memory limits
 * IMPROVED: Better memory management and cache size control
 */
public class CacheManager {
    private static CacheManager instance;
    
    // App names cache (package name -> app name)
    private final Map<String, String> appNamesCache;
    
    // Icon cache with LRU eviction
    private LruCache<String, Bitmap> iconCache;
    
    // Connection state cache
    private final Map<String, Object> connectionCache;
    
    // Settings
    private final SettingsManager settingsManager;
    private final LogManager logManager;
    
    // Cache size limits
    private static final int DEFAULT_ICON_CACHE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_APP_NAMES_CACHE_SIZE = 1000;
    
    private CacheManager(Context context) {
        this.settingsManager = new SettingsManager(context);
        this.logManager = LogManager.getInstance(context);
        
        // Thread-safe caches
        this.appNamesCache = new ConcurrentHashMap<>();
        this.connectionCache = new ConcurrentHashMap<>();
        
        // Initialize icon cache with size based on settings
        int cacheSizeMB = settingsManager.getCacheSize();
        int iconCacheSize = cacheSizeMB > 0 ? (cacheSizeMB * 1024 * 1024) : DEFAULT_ICON_CACHE_SIZE;
        
        this.iconCache = new LruCache<String, Bitmap>(iconCacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // Return size in bytes
                return bitmap.getByteCount();
            }
            
            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                if (evicted && oldValue != null) {
                    logManager.logDebug("Icon cache evicted: " + key);
                }
            }
        };
        
        logManager.logInfo("CacheManager initialized - Icon cache size: " + (iconCacheSize / 1024 / 1024) + "MB");
    }
    
    public static synchronized CacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new CacheManager(context);
        }
        return instance;
    }
    
    // ============================================================================
    // App Names Cache
    // ============================================================================
    
    /**
     * Put app name in cache
     */
    public void putAppName(String packageName, String appName) {
        if (!settingsManager.isCacheEnabled()) {
            return;
        }
        
        if (packageName == null || appName == null) {
            return;
        }
        
        // Enforce max size
        if (appNamesCache.size() >= MAX_APP_NAMES_CACHE_SIZE) {
            // Remove oldest entry (simple FIFO)
            String firstKey = appNamesCache.keySet().iterator().next();
            appNamesCache.remove(firstKey);
            logManager.logDebug("App names cache full, removed: " + firstKey);
        }
        
        appNamesCache.put(packageName, appName);
    }
    
    /**
     * Get app name from cache
     */
    public String getAppName(String packageName) {
        if (!settingsManager.isCacheEnabled()) {
            return packageName;
        }
        
        if (packageName == null) {
            return null;
        }
        
        String appName = appNamesCache.get(packageName);
        return appName != null ? appName : packageName;
    }
    
    /**
     * Clear app names cache
     */
    public void clearAppNamesCache() {
        appNamesCache.clear();
        logManager.logInfo("App names cache cleared");
    }
    
    /**
     * Get app names cache size
     */
    public int getAppNamesCacheSize() {
        return appNamesCache.size();
    }
    
    // ============================================================================
    // Icon Cache
    // ============================================================================
    
    /**
     * Put icon in cache
     */
    public void putIcon(String packageName, Bitmap icon) {
        if (!settingsManager.isCacheEnabled()) {
            return;
        }
        
        if (packageName == null || icon == null) {
            return;
        }
        
        iconCache.put(packageName, icon);
    }
    
    /**
     * Get icon from cache
     */
    public Bitmap getIcon(String packageName) {
        if (!settingsManager.isCacheEnabled()) {
            return null;
        }
        
        if (packageName == null) {
            return null;
        }
        
        return iconCache.get(packageName);
    }
    
    /**
     * Clear icon cache
     */
    public void clearIconCache() {
        iconCache.evictAll();
        logManager.logInfo("Icon cache cleared");
    }
    
    /**
     * Get icon cache size in bytes
     */
    public int getIconCacheSize() {
        return iconCache.size();
    }
    
    /**
     * Get icon cache max size in bytes
     */
    public int getIconCacheMaxSize() {
        return iconCache.maxSize();
    }
    
    // ============================================================================
    // Connection Cache
    // ============================================================================
    
    /**
     * Put connection data in cache
     */
    public void putConnectionData(String key, Object value) {
        if (!settingsManager.isCacheEnabled()) {
            return;
        }
        
        if (key == null) {
            return;
        }
        
        connectionCache.put(key, value);
    }
    
    /**
     * Get connection data from cache
     */
    public Object getConnectionData(String key) {
        if (!settingsManager.isCacheEnabled()) {
            return null;
        }
        
        if (key == null) {
            return null;
        }
        
        return connectionCache.get(key);
    }
    
    /**
     * Clear connection cache
     */
    public void clearConnectionCache() {
        connectionCache.clear();
        logManager.logInfo("Connection cache cleared");
    }
    
    // ============================================================================
    // Cache Management
    // ============================================================================
    
    /**
     * Clear all caches
     */
    public void clearAllCaches() {
        clearAppNamesCache();
        clearIconCache();
        clearConnectionCache();
        logManager.logInfo("All caches cleared");
    }
    
    /**
     * Update cache size based on settings
     */
    public void updateCacheSize() {
        int cacheSizeMB = settingsManager.getCacheSize();
        int iconCacheSize = cacheSizeMB > 0 ? (cacheSizeMB * 1024 * 1024) : DEFAULT_ICON_CACHE_SIZE;
        
        // Create new cache with new size
        LruCache<String, Bitmap> newIconCache = new LruCache<String, Bitmap>(iconCacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount();
            }
        };
        
        // Copy existing entries if they fit
        Map<String, Bitmap> snapshot = iconCache.snapshot();
        for (Map.Entry<String, Bitmap> entry : snapshot.entrySet()) {
            if (newIconCache.size() + entry.getValue().getByteCount() <= iconCacheSize) {
                newIconCache.put(entry.getKey(), entry.getValue());
            }
        }
        
        iconCache = newIconCache;
        logManager.logInfo("Icon cache size updated to: " + (iconCacheSize / 1024 / 1024) + "MB");
    }
    
    /**
     * Get memory usage statistics
     */
    public String getCacheStats() {
        long iconCacheSize = iconCache.size();
        int appNamesSize = appNamesCache.size();
        int connectionSize = connectionCache.size();
        
        return String.format("Cache Stats - Icons: %d KB, App Names: %d, Connections: %d",
            iconCacheSize / 1024, appNamesSize, connectionSize);
    }
}

