package com.freeadbremote;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.freeadbremote.service.AdbConnectionService;
import java.io.File;

public class MainActivity extends AppCompatActivity {
    private EditText hostEditText;
    private EditText portEditText;
    private Button connectButton;
    private Button disconnectButton;
    private Button goToControlButton;
    private TextView statusTextView;
    
    private AdbConnectionManager connectionManager;
    private LogManager logManager;
    private boolean isConnected = false;
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private Handler connectionCheckHandler;
    private Runnable connectionCheckRunnable;
    private boolean isWaitingForConnection = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize logging first
        logManager = LogManager.getInstance(this);
        logManager.logInfo("MainActivity onCreate");
        
        try {
            // Install crash handler
            CrashHandler.install(this);
            logManager.logInfo("Crash handler installed");
            
            // Request necessary permissions
            requestPermissions();
            
            connectionManager = AdbConnectionManager.getInstance(this);
            
            initViews();
            setupListeners();
            updateUI();
            
            // Show log file location
            String logPath = logManager.getLogFilePath();
            logManager.logInfo("Log file location: " + logPath);
            Toast.makeText(this, "Logs: " + new File(logPath).getName(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            logManager.logError("Failed to initialize MainActivity", e);
            Toast.makeText(this, "Initialization error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private void requestPermissions() {
        logManager.logInfo("Checking permissions...");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                logManager.logInfo("Requesting POST_NOTIFICATIONS permission");
                ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 
                    PERMISSION_REQUEST_CODE);
            }
        }
        
        // Check CHANGE_NETWORK_STATE and CHANGE_WIFI_STATE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_NETWORK_STATE) 
                != PackageManager.PERMISSION_GRANTED) {
            logManager.logWarn("CHANGE_NETWORK_STATE permission not granted");
        } else {
            logManager.logInfo("CHANGE_NETWORK_STATE permission granted");
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) 
                != PackageManager.PERMISSION_GRANTED) {
            logManager.logWarn("CHANGE_WIFI_STATE permission not granted");
        } else {
            logManager.logInfo("CHANGE_WIFI_STATE permission granted");
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        logManager.logInfo("Permission request result: " + requestCode);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    logManager.logInfo("Permission granted: " + permissions[i]);
                } else {
                    logManager.logWarn("Permission denied: " + permissions[i]);
                }
            }
        }
    }

    private void initViews() {
        hostEditText = findViewById(R.id.hostEditText);
        portEditText = findViewById(R.id.portEditText);
        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        goToControlButton = findViewById(R.id.goToControlButton);
        statusTextView = findViewById(R.id.statusTextView);
        
        // Set default values
        hostEditText.setText("192.168.1.128");
        portEditText.setText("5555");
    }

    private void setupListeners() {
        connectButton.setOnClickListener(v -> {
            String host = hostEditText.getText().toString().trim();
            String portStr = portEditText.getText().toString().trim();
            
            if (host.isEmpty() || portStr.isEmpty()) {
                Toast.makeText(this, "Please enter host and port", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                int port = Integer.parseInt(portStr);
                connect(host, port);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
            }
        });
        
        disconnectButton.setOnClickListener(v -> disconnect());
        
        goToControlButton.setOnClickListener(v -> {
            if (AdbConnectionManager.isConnected) {
                String host = AdbConnectionManager.connectedHost;
                int port = AdbConnectionManager.connectedPort;
                if (host != null) {
                    Intent intent = new Intent(this, ControlActivity.class);
                    intent.putExtra("host", host);
                    intent.putExtra("port", port);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void connect(String host, int port) {
        logManager.logInfo("Connect button clicked: " + host + ":" + port);
        
        if (isConnected) {
            logManager.logWarn("Already connected, ignoring connect request");
            Toast.makeText(this, "Already connected", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            statusTextView.setText("Initializing connection...");
            logManager.logInfo("Starting connection process");
            
            // Start foreground service
            Intent serviceIntent = new Intent(this, AdbConnectionService.class);
            serviceIntent.putExtra("action", "start");
            serviceIntent.putExtra("host", host);
            serviceIntent.putExtra("port", port);
            
            logManager.logDebug("Starting foreground service");
            
            // Check if we have required permissions for foreground service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // For Android 14+ (API 34), we need to ensure permissions are granted
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ requires explicit permission check
                    boolean hasPermission = ContextCompat.checkSelfPermission(this, 
                        Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE) == PackageManager.PERMISSION_GRANTED;
                    
                    if (!hasPermission) {
                        logManager.logError("Missing FOREGROUND_SERVICE_CONNECTED_DEVICE permission", null);
                        Toast.makeText(this, "Missing required permission for foreground service", Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                
                try {
                    startForegroundService(serviceIntent);
                    logManager.logInfo("Foreground service started successfully");
                } catch (SecurityException e) {
                    logManager.logError("SecurityException starting foreground service", e);
                    Toast.makeText(this, "Permission error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    statusTextView.setText("Error: Permission denied");
                    return;
                } catch (Exception e) {
                    logManager.logError("Exception starting foreground service", e);
                    Toast.makeText(this, "Error starting service: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    statusTextView.setText("Error: " + e.getMessage());
                    return;
                }
            } else {
                startService(serviceIntent);
            }
            
            // Check trust status
            AdbServerManager serverManager = connectionManager.getServerManager();
            if (serverManager != null && serverManager.isTrustEstablished()) {
                statusTextView.setText("Trust already established. Connecting...");
                logManager.logInfo("Trust already established");
            } else {
                statusTextView.setText("Establishing trust (first connection)...");
                logManager.logInfo("First connection - establishing trust");
            }
            
            isConnected = true;
            updateUI();
            logManager.logInfo("Connection initiated successfully");
            
            // Start monitoring connection status to auto-navigate when connected
            isWaitingForConnection = true;
            startConnectionMonitoring(host, port);
            
        } catch (Exception e) {
            logManager.logError("Failed to start connection", e);
            statusTextView.setText("Error: " + e.getMessage());
            Toast.makeText(this, "Connection error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private void startConnectionMonitoring(String host, int port) {
        if (connectionCheckHandler == null) {
            connectionCheckHandler = new Handler(getMainLooper());
        }
        
        // Stop any existing monitoring
        stopConnectionMonitoring();
        
        connectionCheckRunnable = new Runnable() {
            private int checkCount = 0;
            private static final int MAX_CHECKS = 60; // 30 seconds (500ms * 60)
            
            @Override
            public void run() {
                if (!isWaitingForConnection) {
                    return;
                }
                
                checkCount++;
                
                // Check if connection is established - check both static variable and instance method
                boolean staticConnected = AdbConnectionManager.isConnected;
                boolean instanceConnected = connectionManager != null && connectionManager.isConnected();
                boolean hostMatches = AdbConnectionManager.connectedHost != null && 
                                     AdbConnectionManager.connectedHost.equals(host);
                
                logManager.logDebug("Connection check #" + checkCount + ": static=" + staticConnected + 
                    ", instance=" + instanceConnected + ", hostMatches=" + hostMatches + 
                    ", host=" + AdbConnectionManager.connectedHost);
                
                if ((staticConnected || instanceConnected) && hostMatches) {
                    logManager.logInfo("Connection established! Navigating to ControlActivity");
                    isWaitingForConnection = false;
                    stopConnectionMonitoring();
                    
                    // Update UI
                    isConnected = true;
                    updateUI();
                    statusTextView.setText("Connected to " + host + ":" + port);
                    
                    // Small delay to ensure connection is fully ready
                    connectionCheckHandler.postDelayed(() -> {
                        // Navigate to ControlActivity
                        Intent intent = new Intent(MainActivity.this, ControlActivity.class);
                        intent.putExtra("host", host);
                        intent.putExtra("port", port);
                        startActivity(intent);
                    }, 500);
                    
                    return;
                }
                
                // Stop monitoring after max checks (30 seconds)
                if (checkCount >= MAX_CHECKS) {
                    logManager.logWarn("Connection monitoring timeout - connection may have failed");
                    isWaitingForConnection = false;
                    stopConnectionMonitoring();
                    statusTextView.setText("Connection timeout - please check device");
                    return;
                }
                
                // Continue monitoring
                connectionCheckHandler.postDelayed(this, 500); // Check every 500ms
            }
        };
        
        // Start monitoring after a short delay
        connectionCheckHandler.postDelayed(connectionCheckRunnable, 1000);
    }
    
    private void stopConnectionMonitoring() {
        if (connectionCheckHandler != null && connectionCheckRunnable != null) {
            connectionCheckHandler.removeCallbacks(connectionCheckRunnable);
            connectionCheckRunnable = null;
        }
    }

    private void disconnect() {
        if (!isConnected) {
            return;
        }
        
        Intent serviceIntent = new Intent(this, AdbConnectionService.class);
        serviceIntent.putExtra("action", "stop");
        stopService(serviceIntent);
        
        if (connectionManager != null) {
            connectionManager.disconnect();
        }
        
        isConnected = false;
        statusTextView.setText("Disconnected");
        updateUI();
    }

    private void updateUI() {
        connectButton.setEnabled(!isConnected);
        disconnectButton.setEnabled(isConnected);
        hostEditText.setEnabled(!isConnected);
        portEditText.setEnabled(!isConnected);
        
        // Enable "Go to Control" button only when connected
        if (goToControlButton != null) {
            // Check actual connection status from AdbConnectionManager
            boolean actuallyConnected = AdbConnectionManager.isConnected && 
                                      AdbConnectionManager.connectedHost != null;
            goToControlButton.setEnabled(actuallyConnected);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logManager.logInfo("MainActivity onDestroy");
        stopConnectionMonitoring();
        if (connectionCheckHandler != null) {
            connectionCheckHandler.removeCallbacksAndMessages(null);
            connectionCheckHandler = null; // Prevent memory leak
        }
        connectionCheckRunnable = null; // Prevent memory leak
        // Don't disconnect here - connection is managed by Service
        if (logManager != null) {
            logManager.close();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        logManager.logInfo("MainActivity onResume");
        
        // Check if already connected - update UI accordingly
        boolean actuallyConnected = AdbConnectionManager.isConnected && 
                                   AdbConnectionManager.connectedHost != null;
        
        if (actuallyConnected) {
            // Update UI to show connected state
            isConnected = true;
            statusTextView.setText("Connected to " + AdbConnectionManager.connectedHost + ":" + AdbConnectionManager.connectedPort);
        } else {
            // Update UI to show disconnected state
            isConnected = false;
            if (statusTextView.getText().toString().contains("Connected")) {
                statusTextView.setText("Disconnected");
            }
        }
        
        // Update UI (this will enable/disable the Go to Control button)
        updateUI();
    }
    
    @Override
    public void onBackPressed() {
        // If connected, navigate to ControlActivity instead of exiting
        if (AdbConnectionManager.isConnected && connectionManager != null) {
            String host = AdbConnectionManager.connectedHost;
            int port = AdbConnectionManager.connectedPort;
            if (host != null) {
                Intent intent = new Intent(this, ControlActivity.class);
                intent.putExtra("host", host);
                intent.putExtra("port", port);
                startActivity(intent);
                return;
            }
        }
        // Otherwise, use default back behavior
        super.onBackPressed();
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        logManager.logInfo("MainActivity onPause");
    }
}

