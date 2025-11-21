package com.freeadbremote;

import android.content.Context;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized error handling with retry logic and circuit breaker pattern
 */
public class ErrorHandler {
    private static final String TAG = "ErrorHandler";
    
    private final LogManager logManager;
    private final Context context;
    
    // Circuit breaker state
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private static final int MAX_FAILURES = 5;
    private static final long CIRCUIT_BREAKER_TIMEOUT = 30000; // 30 seconds
    
    public enum ErrorType {
        CONNECTION_ERROR,
        COMMAND_ERROR,
        DEPLOYMENT_ERROR,
        NETWORK_ERROR,
        UNKNOWN_ERROR
    }
    
    public interface ErrorCallback {
        void onError(String message, ErrorType type, boolean shouldRetry);
    }
    
    public ErrorHandler(Context context) {
        this.context = context.getApplicationContext();
        this.logManager = LogManager.getInstance(context);
    }
    
    /**
     * Handle error with automatic classification and retry decision
     */
    public void handleError(String error, ErrorType type, ErrorCallback callback) {
        if (error == null) {
            error = "Unknown error";
        }
        
        logManager.logError("ErrorHandler: " + type + " - " + error);
        
        // Check circuit breaker
        if (isCircuitBreakerOpen()) {
            logManager.logWarn("Circuit breaker is OPEN - blocking request");
            if (callback != null) {
                callback.onError("Service temporarily unavailable. Please try again later.", type, false);
            }
            return;
        }
        
        // Classify error and determine if retry is appropriate
        boolean shouldRetry = shouldRetryError(error, type);
        
        // Update circuit breaker state
        if (!shouldRetry) {
            recordFailure();
        } else {
            resetFailureCount();
        }
        
        if (callback != null) {
            callback.onError(getUserFriendlyMessage(error, type), type, shouldRetry);
        }
    }
    
    /**
     * Determine if error should be retried
     */
    private boolean shouldRetryError(String error, ErrorType type) {
        if (error == null) return false;
        
        String lowerError = error.toLowerCase();
        
        switch (type) {
            case CONNECTION_ERROR:
                // Retry connection errors (timeout, network issues)
                return lowerError.contains("timeout") ||
                       lowerError.contains("connection") ||
                       lowerError.contains("socket") ||
                       lowerError.contains("network");
                       
            case NETWORK_ERROR:
                // Retry network errors
                return true;
                
            case COMMAND_ERROR:
                // Don't retry command errors (syntax errors, permission denied)
                return false;
                
            case DEPLOYMENT_ERROR:
                // Retry deployment errors (installation failures)
                return lowerError.contains("install") ||
                       lowerError.contains("deploy") ||
                       lowerError.contains("timeout");
                       
            default:
                return false;
        }
    }
    
    /**
     * Get user-friendly error message
     */
    private String getUserFriendlyMessage(String error, ErrorType type) {
        if (error == null) return "An error occurred";
        
        String lowerError = error.toLowerCase();
        
        switch (type) {
            case CONNECTION_ERROR:
                if (lowerError.contains("timeout")) {
                    return "Connection timeout. Please check your network and try again.";
                } else if (lowerError.contains("refused")) {
                    return "Connection refused. Please ensure ADB is enabled on the device.";
                } else if (lowerError.contains("not connected")) {
                    return "Not connected to device. Please connect first.";
                }
                return "Connection error: " + error;
                
            case COMMAND_ERROR:
                if (lowerError.contains("permission")) {
                    return "Permission denied. The device may require root access.";
                } else if (lowerError.contains("not found")) {
                    return "Command not found. The device may not support this operation.";
                }
                return "Command failed: " + error;
                
            case DEPLOYMENT_ERROR:
                if (lowerError.contains("install")) {
                    return "Installation failed. Please check device storage and permissions.";
                }
                return "Deployment error: " + error;
                
            case NETWORK_ERROR:
                return "Network error. Please check your connection and try again.";
                
            default:
                return error;
        }
    }
    
    /**
     * Check if circuit breaker is open
     */
    private boolean isCircuitBreakerOpen() {
        int failures = failureCount.get();
        long lastFailure = lastFailureTime.get();
        long now = System.currentTimeMillis();
        
        if (failures >= MAX_FAILURES) {
            // Check if timeout has passed
            if (now - lastFailure < CIRCUIT_BREAKER_TIMEOUT) {
                return true; // Circuit breaker is OPEN
            } else {
                // Timeout passed, reset
                resetFailureCount();
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * Record a failure
     */
    private void recordFailure() {
        failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
    }
    
    /**
     * Reset failure count (on success)
     */
    public void resetFailureCount() {
        failureCount.set(0);
        lastFailureTime.set(0);
    }
    
    /**
     * Get current failure count
     */
    public int getFailureCount() {
        return failureCount.get();
    }
}

