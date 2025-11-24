package com.freeadbremote;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages pinned/favorite applications
 * Stores pinned app package names in SharedPreferences
 */
public class PinnedAppsManager {
    private static final String PREFS_NAME = "PinnedApps";
    private static final String KEY_PINNED_APPS = "pinned_apps_set";
    
    private final SharedPreferences prefs;
    private final LogManager logManager;
    
    public PinnedAppsManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.logManager = LogManager.getInstance(context);
    }
    
    /**
     * Check if an app is pinned
     */
    public boolean isPinned(String packageName) {
        if (packageName == null) return false;
        Set<String> pinnedApps = getPinnedApps();
        return pinnedApps.contains(packageName);
    }
    
    /**
     * Pin an app
     */
    public void pinApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            logManager.logWarn("PinnedAppsManager: Cannot pin app with null or empty package name");
            return;
        }
        
        Set<String> pinnedApps = new HashSet<>(getPinnedApps());
        if (pinnedApps.add(packageName)) {
            savePinnedApps(pinnedApps);
            logManager.logInfo("PinnedAppsManager: Pinned app: " + packageName);
        }
    }
    
    /**
     * Unpin an app
     */
    public void unpinApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            logManager.logWarn("PinnedAppsManager: Cannot unpin app with null or empty package name");
            return;
        }
        
        Set<String> pinnedApps = new HashSet<>(getPinnedApps());
        if (pinnedApps.remove(packageName)) {
            savePinnedApps(pinnedApps);
            logManager.logInfo("PinnedAppsManager: Unpinned app: " + packageName);
        }
    }
    
    /**
     * Toggle pin status of an app
     */
    public void togglePin(String packageName) {
        if (isPinned(packageName)) {
            unpinApp(packageName);
        } else {
            pinApp(packageName);
        }
    }
    
    /**
     * Get all pinned app package names
     */
    public Set<String> getPinnedApps() {
        return prefs.getStringSet(KEY_PINNED_APPS, new HashSet<>());
    }
    
    /**
     * Clear all pinned apps
     */
    public void clearAllPinnedApps() {
        prefs.edit().remove(KEY_PINNED_APPS).apply();
        logManager.logInfo("PinnedAppsManager: Cleared all pinned apps");
    }
    
    /**
     * Get count of pinned apps
     */
    public int getPinnedCount() {
        return getPinnedApps().size();
    }
    
    /**
     * Save pinned apps set to SharedPreferences
     */
    private void savePinnedApps(Set<String> pinnedApps) {
        prefs.edit().putStringSet(KEY_PINNED_APPS, pinnedApps).apply();
    }
}

