package com.freeadbremote;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized loading state management for UI
 */
public class LoadingStateManager {
    public enum LoadingState {
        IDLE,
        LOADING,
        SUCCESS,
        ERROR
    }
    
    private LoadingState currentState = LoadingState.IDLE;
    private String message;
    private float progress = 0f; // 0.0 to 1.0
    
    private final CopyOnWriteArrayList<LoadingStateListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    public interface LoadingStateListener {
        void onLoadingStateChanged(LoadingState state, String message, float progress);
    }
    
    public LoadingStateManager() {
    }
    
    public synchronized LoadingState getState() {
        return currentState;
    }
    
    public synchronized String getMessage() {
        return message;
    }
    
    public synchronized float getProgress() {
        return progress;
    }
    
    public synchronized boolean isLoading() {
        return currentState == LoadingState.LOADING;
    }
    
    public synchronized void setLoading(String message) {
        setLoading(message, 0f);
    }
    
    public synchronized void setLoading(String message, float progress) {
        this.currentState = LoadingState.LOADING;
        this.message = message;
        this.progress = Math.max(0f, Math.min(1f, progress));
        notifyListeners();
    }
    
    public synchronized void setSuccess(String message) {
        this.currentState = LoadingState.SUCCESS;
        this.message = message;
        this.progress = 1f;
        notifyListeners();
        
        // Auto-reset to IDLE after 2 seconds
        mainHandler.postDelayed(() -> {
            synchronized (LoadingStateManager.this) {
                if (currentState == LoadingState.SUCCESS) {
                    setIdle();
                }
            }
        }, 2000);
    }
    
    public synchronized void setError(String message) {
        this.currentState = LoadingState.ERROR;
        this.message = message;
        notifyListeners();
    }
    
    public synchronized void setIdle() {
        this.currentState = LoadingState.IDLE;
        this.message = null;
        this.progress = 0f;
        notifyListeners();
    }
    
    public synchronized void updateProgress(float progress) {
        if (currentState == LoadingState.LOADING) {
            this.progress = Math.max(0f, Math.min(1f, progress));
            notifyListeners();
        }
    }
    
    public void addListener(LoadingStateListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }
    
    public void removeListener(LoadingStateListener listener) {
        listeners.remove(listener);
    }
    
    private void notifyListeners() {
        LoadingState state = this.currentState;
        String msg = this.message;
        float prog = this.progress;
        
        // Notify on main thread
        mainHandler.post(() -> {
            for (LoadingStateListener listener : listeners) {
                try {
                    listener.onLoadingStateChanged(state, msg, prog);
                } catch (Exception e) {
                    // Ignore listener errors
                }
            }
        });
    }
}

