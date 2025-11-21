package com.freeadbremote;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized connection state management
 * Replaces static volatile variables with a proper state management system
 */
public class ConnectionState {
    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    
    private State currentState = State.DISCONNECTED;
    private String host;
    private int port;
    private String errorMessage;
    
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();
    
    public interface StateListener {
        void onStateChanged(State newState, String host, int port, String error);
    }
    
    public ConnectionState() {
        this.host = null;
        this.port = 0;
        this.errorMessage = null;
    }
    
    public synchronized State getState() {
        return currentState;
    }
    
    public synchronized String getHost() {
        return host;
    }
    
    public synchronized int getPort() {
        return port;
    }
    
    public synchronized String getErrorMessage() {
        return errorMessage;
    }
    
    public synchronized boolean isConnected() {
        return currentState == State.CONNECTED;
    }
    
    public synchronized boolean isConnecting() {
        return currentState == State.CONNECTING;
    }
    
    public synchronized void setConnecting(String host, int port) {
        this.currentState = State.CONNECTING;
        this.host = host;
        this.port = port;
        this.errorMessage = null;
        notifyListeners();
    }
    
    public synchronized void setConnected(String host, int port) {
        this.currentState = State.CONNECTED;
        this.host = host;
        this.port = port;
        this.errorMessage = null;
        notifyListeners();
    }
    
    public synchronized void setDisconnected() {
        this.currentState = State.DISCONNECTED;
        this.errorMessage = null;
        notifyListeners();
    }
    
    public synchronized void setError(String errorMessage) {
        this.currentState = State.ERROR;
        this.errorMessage = errorMessage;
        notifyListeners();
    }
    
    public synchronized void setError(String host, int port, String errorMessage) {
        this.currentState = State.ERROR;
        this.host = host;
        this.port = port;
        this.errorMessage = errorMessage;
        notifyListeners();
    }
    
    public void addListener(StateListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }
    
    public void removeListener(StateListener listener) {
        listeners.remove(listener);
    }
    
    private void notifyListeners() {
        State state = this.currentState;
        String h = this.host;
        int p = this.port;
        String err = this.errorMessage;
        
        for (StateListener listener : listeners) {
            try {
                listener.onStateChanged(state, h, p, err);
            } catch (Exception e) {
                // Ignore listener errors
            }
        }
    }
    
    public String getConnectionInfo() {
        if (isConnected() && host != null) {
            return host + ":" + port;
        }
        return "Not connected";
    }
}

