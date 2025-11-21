package com.freeadbremote;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Retry manager for operations that may fail transiently
 */
public class RetryManager {
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_INITIAL_DELAY_MS = 1000;
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    
    private final LogManager logManager;
    private final ExecutorService executor;
    
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }
    
    public interface RetryCallback<T> {
        void onSuccess(T result);
        void onFailure(String error, int attemptCount);
    }
    
    public RetryManager(LogManager logManager) {
        this.logManager = logManager;
        this.executor = Executors.newCachedThreadPool();
    }
    
    /**
     * Execute operation with retry logic
     */
    public <T> void executeWithRetry(RetryableOperation<T> operation, RetryCallback<T> callback) {
        executeWithRetry(operation, callback, DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_DELAY_MS);
    }
    
    /**
     * Execute operation with custom retry parameters
     */
    public <T> void executeWithRetry(RetryableOperation<T> operation, RetryCallback<T> callback,
                                    int maxRetries, long initialDelayMs) {
        executor.execute(() -> {
            AtomicInteger attemptCount = new AtomicInteger(0);
            long delay = initialDelayMs;
            
            while (attemptCount.get() < maxRetries) {
                attemptCount.incrementAndGet();
                
                try {
                    logManager.logInfo("Retry attempt " + attemptCount.get() + "/" + maxRetries);
                    T result = operation.execute();
                    
                    // Success
                    logManager.logInfo("Operation succeeded on attempt " + attemptCount.get());
                    if (callback != null) {
                        callback.onSuccess(result);
                    }
                    return;
                    
                } catch (Exception e) {
                    String error = e.getMessage();
                    logManager.logWarn("Attempt " + attemptCount.get() + " failed: " + error);
                    
                    // Check if we should retry
                    if (attemptCount.get() >= maxRetries) {
                        // Max retries reached
                        logManager.logError("Max retries reached. Operation failed.", e);
                        if (callback != null) {
                            callback.onFailure(error != null ? error : "Operation failed after " + maxRetries + " attempts", 
                                             attemptCount.get());
                        }
                        return;
                    }
                    
                    // Wait before retry with exponential backoff
                    try {
                        logManager.logInfo("Waiting " + delay + "ms before retry...");
                        Thread.sleep(delay);
                        delay = (long) (delay * DEFAULT_BACKOFF_MULTIPLIER);
                    } catch (InterruptedException ie) {
                        logManager.logWarn("Retry interrupted");
                        if (callback != null) {
                            callback.onFailure("Operation interrupted", attemptCount.get());
                        }
                        return;
                    }
                }
            }
        });
    }
    
    /**
     * Execute operation with retry and custom retry condition
     */
    public <T> void executeWithRetry(RetryableOperation<T> operation, RetryCallback<T> callback,
                                    RetryCondition retryCondition) {
        executor.execute(() -> {
            AtomicInteger attemptCount = new AtomicInteger(0);
            long delay = DEFAULT_INITIAL_DELAY_MS;
            
            while (true) {
                attemptCount.incrementAndGet();
                
                try {
                    logManager.logInfo("Retry attempt " + attemptCount.get());
                    T result = operation.execute();
                    
                    // Success
                    logManager.logInfo("Operation succeeded on attempt " + attemptCount.get());
                    if (callback != null) {
                        callback.onSuccess(result);
                    }
                    return;
                    
                } catch (Exception e) {
                    String error = e.getMessage();
                    logManager.logWarn("Attempt " + attemptCount.get() + " failed: " + error);
                    
                    // Check retry condition
                    if (!retryCondition.shouldRetry(error, attemptCount.get())) {
                        logManager.logError("Retry condition not met. Operation failed.", e);
                        if (callback != null) {
                            callback.onFailure(error != null ? error : "Operation failed", attemptCount.get());
                        }
                        return;
                    }
                    
                    // Wait before retry
                    try {
                        logManager.logInfo("Waiting " + delay + "ms before retry...");
                        Thread.sleep(delay);
                        delay = (long) (delay * DEFAULT_BACKOFF_MULTIPLIER);
                    } catch (InterruptedException ie) {
                        logManager.logWarn("Retry interrupted");
                        if (callback != null) {
                            callback.onFailure("Operation interrupted", attemptCount.get());
                        }
                        return;
                    }
                }
            }
        });
    }
    
    public interface RetryCondition {
        boolean shouldRetry(String error, int attemptCount);
    }
    
    public void shutdown() {
        executor.shutdown();
    }
}

