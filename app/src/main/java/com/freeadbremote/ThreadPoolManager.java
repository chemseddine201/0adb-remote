package com.freeadbremote;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized thread pool management
 * IMPROVED: Better thread management with named threads and proper sizing
 */
public class ThreadPoolManager {
    private static ThreadPoolManager instance;
    
    // Main executor for general tasks
    private final ExecutorService mainExecutor;
    
    // Network executor for network operations
    private final ExecutorService networkExecutor;
    
    // IO executor for file operations
    private final ExecutorService ioExecutor;
    
    // Background executor for background tasks
    private final ExecutorService backgroundExecutor;
    
    private final LogManager logManager;
    
    // Thread pool sizes
    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int NETWORK_POOL_SIZE = 3;
    private static final int IO_POOL_SIZE = 2;
    private static final long KEEP_ALIVE_TIME = 60L;
    
    private ThreadPoolManager(Context context) {
        this.logManager = LogManager.getInstance(context);
        
        // Main executor - for general tasks
        this.mainExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamedThreadFactory("MainPool")
        );
        
        // Network executor - for network operations
        this.networkExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            NETWORK_POOL_SIZE,
            KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamedThreadFactory("NetworkPool")
        );
        
        // IO executor - for file operations
        this.ioExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            IO_POOL_SIZE,
            KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamedThreadFactory("IOPool")
        );
        
        // Background executor - for background tasks (cached thread pool)
        this.backgroundExecutor = Executors.newCachedThreadPool(
            new NamedThreadFactory("BackgroundPool")
        );
        
        logManager.logInfo("ThreadPoolManager initialized");
    }
    
    public static synchronized ThreadPoolManager getInstance(Context context) {
        if (instance == null) {
            instance = new ThreadPoolManager(context);
        }
        return instance;
    }
    
    /**
     * Execute task on main executor
     */
    public void executeMain(Runnable task) {
        if (task != null) {
            mainExecutor.execute(task);
        }
    }
    
    /**
     * Execute network task
     */
    public void executeNetwork(Runnable task) {
        if (task != null) {
            networkExecutor.execute(task);
        }
    }
    
    /**
     * Execute IO task
     */
    public void executeIO(Runnable task) {
        if (task != null) {
            ioExecutor.execute(task);
        }
    }
    
    /**
     * Execute background task
     */
    public void executeBackground(Runnable task) {
        if (task != null) {
            backgroundExecutor.execute(task);
        }
    }
    
    /**
     * Get main executor
     */
    public ExecutorService getMainExecutor() {
        return mainExecutor;
    }
    
    /**
     * Get network executor
     */
    public ExecutorService getNetworkExecutor() {
        return networkExecutor;
    }
    
    /**
     * Get IO executor
     */
    public ExecutorService getIOExecutor() {
        return ioExecutor;
    }
    
    /**
     * Get background executor
     */
    public ExecutorService getBackgroundExecutor() {
        return backgroundExecutor;
    }
    
    /**
     * Shutdown all executors
     */
    public void shutdown() {
        logManager.logInfo("Shutting down ThreadPoolManager");
        
        shutdownExecutor(mainExecutor, "MainExecutor");
        shutdownExecutor(networkExecutor, "NetworkExecutor");
        shutdownExecutor(ioExecutor, "IOExecutor");
        shutdownExecutor(backgroundExecutor, "BackgroundExecutor");
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logManager.logWarn(name + " did not terminate, forcing shutdown");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logManager.logError("Error shutting down " + name, e);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Named thread factory for better debugging
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;
        
        NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }
        
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, namePrefix + "-Thread-" + threadNumber.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }
}

