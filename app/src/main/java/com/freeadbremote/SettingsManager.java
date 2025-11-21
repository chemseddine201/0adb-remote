package com.freeadbremote;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Centralized settings management
 */
public class SettingsManager {
    private static final String PREFS_NAME = "FreeAdbRemoteSettings";
    
    // Connection settings
    private static final String KEY_DEFAULT_HOST = "default_host";
    private static final String KEY_DEFAULT_PORT = "default_port";
    private static final String KEY_AUTO_RECONNECT = "auto_reconnect";
    private static final String KEY_CONNECTION_TIMEOUT = "connection_timeout";
    
    // Retry settings
    private static final String KEY_MAX_RETRIES = "max_retries";
    private static final String KEY_RETRY_DELAY = "retry_delay";
    
    // UI settings
    private static final String KEY_HAPTIC_FEEDBACK = "haptic_feedback";
    private static final String KEY_SOUND_FEEDBACK = "sound_feedback";
    private static final String KEY_DARK_MODE = "dark_mode";
    
    // Performance settings
    private static final String KEY_CACHE_ENABLED = "cache_enabled";
    private static final String KEY_CACHE_SIZE = "cache_size";
    
    // Default values
    private static final String DEFAULT_HOST = "192.168.1.128";
    private static final int DEFAULT_PORT = 5555;
    private static final boolean DEFAULT_AUTO_RECONNECT = true;
    private static final int DEFAULT_CONNECTION_TIMEOUT = 10000; // 10 seconds
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY = 1000; // 1 second
    private static final boolean DEFAULT_HAPTIC_FEEDBACK = true;
    private static final boolean DEFAULT_SOUND_FEEDBACK = true;
    private static final boolean DEFAULT_DARK_MODE = false;
    private static final boolean DEFAULT_CACHE_ENABLED = true;
    private static final int DEFAULT_CACHE_SIZE = 50; // MB
    
    private final SharedPreferences prefs;
    private final LogManager logManager;
    
    public SettingsManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.logManager = LogManager.getInstance(context);
    }
    
    // Connection settings
    public String getDefaultHost() {
        return prefs.getString(KEY_DEFAULT_HOST, DEFAULT_HOST);
    }
    
    public void setDefaultHost(String host) {
        prefs.edit().putString(KEY_DEFAULT_HOST, host).apply();
        logManager.logInfo("Default host set to: " + host);
    }
    
    public int getDefaultPort() {
        return prefs.getInt(KEY_DEFAULT_PORT, DEFAULT_PORT);
    }
    
    public void setDefaultPort(int port) {
        prefs.edit().putInt(KEY_DEFAULT_PORT, port).apply();
        logManager.logInfo("Default port set to: " + port);
    }
    
    public boolean isAutoReconnectEnabled() {
        return prefs.getBoolean(KEY_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT);
    }
    
    public void setAutoReconnectEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply();
        logManager.logInfo("Auto-reconnect set to: " + enabled);
    }
    
    public int getConnectionTimeout() {
        return prefs.getInt(KEY_CONNECTION_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT);
    }
    
    public void setConnectionTimeout(int timeoutMs) {
        prefs.edit().putInt(KEY_CONNECTION_TIMEOUT, timeoutMs).apply();
        logManager.logInfo("Connection timeout set to: " + timeoutMs + "ms");
    }
    
    // Retry settings
    public int getMaxRetries() {
        return prefs.getInt(KEY_MAX_RETRIES, DEFAULT_MAX_RETRIES);
    }
    
    public void setMaxRetries(int maxRetries) {
        prefs.edit().putInt(KEY_MAX_RETRIES, maxRetries).apply();
        logManager.logInfo("Max retries set to: " + maxRetries);
    }
    
    public long getRetryDelay() {
        return prefs.getLong(KEY_RETRY_DELAY, DEFAULT_RETRY_DELAY);
    }
    
    public void setRetryDelay(long delayMs) {
        prefs.edit().putLong(KEY_RETRY_DELAY, delayMs).apply();
        logManager.logInfo("Retry delay set to: " + delayMs + "ms");
    }
    
    // UI settings
    public boolean isHapticFeedbackEnabled() {
        return prefs.getBoolean(KEY_HAPTIC_FEEDBACK, DEFAULT_HAPTIC_FEEDBACK);
    }
    
    public void setHapticFeedbackEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, enabled).apply();
    }
    
    public boolean isSoundFeedbackEnabled() {
        return prefs.getBoolean(KEY_SOUND_FEEDBACK, DEFAULT_SOUND_FEEDBACK);
    }
    
    public void setSoundFeedbackEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SOUND_FEEDBACK, enabled).apply();
    }
    
    public boolean isDarkModeEnabled() {
        return prefs.getBoolean(KEY_DARK_MODE, DEFAULT_DARK_MODE);
    }
    
    public void setDarkModeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply();
    }
    
    // Performance settings
    public boolean isCacheEnabled() {
        return prefs.getBoolean(KEY_CACHE_ENABLED, DEFAULT_CACHE_ENABLED);
    }
    
    public void setCacheEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CACHE_ENABLED, enabled).apply();
    }
    
    public int getCacheSize() {
        return prefs.getInt(KEY_CACHE_SIZE, DEFAULT_CACHE_SIZE);
    }
    
    public void setCacheSize(int sizeMB) {
        prefs.edit().putInt(KEY_CACHE_SIZE, sizeMB).apply();
    }
    
    /**
     * Reset all settings to defaults
     */
    public void resetToDefaults() {
        prefs.edit().clear().apply();
        logManager.logInfo("Settings reset to defaults");
    }
}

