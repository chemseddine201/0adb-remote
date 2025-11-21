package com.freeadbremote;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Apps Manager Activity
 * Shows all installed applications with running status
 * Sorted: running apps first, then stopped apps
 */
public class AppsManagerActivity extends AppCompatActivity {
    
    // UI Components
    private TextView connectionStatusTextView;
    private MaterialButton backButton;
    private MaterialButton refreshButton;
    private RecyclerView appsRecyclerView;
    private ProgressBar loadingProgressBar;
    private TextView emptyStateTextView;
    private TextView appCountTextView;
    
    // Core components
    private AdbConnectionManager connectionManager;
    private LogManager logManager;
    private ServerClient serverClient;
    private AppsAdapter adapter;
    private Handler mainHandler;
    
    // Connection info
    private String connectedHost;
    private int connectedPort;
    private String serverHost;
    private int serverPort = 3000;
    
    // Loading state
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    
    // Running apps cache
    private Set<String> runningPackages = new HashSet<>();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps_manager);
        
        // Initialize components
        logManager = LogManager.getInstance(this);
        connectionManager = AdbConnectionManager.getInstance(this);
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Get connection info
        Intent intent = getIntent();
        connectedHost = intent.getStringExtra("host");
        if (connectedHost == null) connectedHost = AdbConnectionManager.connectedHost;
        connectedPort = intent.getIntExtra("port", 5555);
        if (connectedPort == 0) connectedPort = AdbConnectionManager.connectedPort;
        
        // Server info for app listing
        serverHost = intent.getStringExtra("serverHost");
        if (serverHost == null) {
            serverHost = connectedHost != null ? connectedHost : "localhost";
        }
        serverPort = intent.getIntExtra("serverPort", 3000);
        
        // Initialize server client
        serverClient = new ServerClient(serverHost, serverPort, logManager);
        
        // Setup auto-recovery: link ServerClient with ServerDeployment
        ServerDeployment serverDeployment = new ServerDeployment(this, connectionManager, logManager);
        serverClient.setServerDeployment(serverDeployment, connectionManager);
        
        // Initialize app names cache with auto-recovery support
        AdbServerManager.initializeAppNamesCache(this, serverHost, serverPort, connectionManager, serverDeployment);
        
        // Setup UI
        initViews();
        setupListeners();
        
        // Load apps
        checkConnectionAndLoad();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
            mainHandler = null; // Prevent memory leak
        }
        if (serverClient != null) {
            serverClient.shutdown();
        }
        // Clear running packages cache
        runningPackages.clear();
    }
    
    private void initViews() {
        connectionStatusTextView = findViewById(R.id.connectionStatusTextView);
        backButton = findViewById(R.id.backButton);
        refreshButton = findViewById(R.id.refreshButton);
        appsRecyclerView = findViewById(R.id.appsRecyclerView);
        loadingProgressBar = findViewById(R.id.loadingProgressBar);
        emptyStateTextView = findViewById(R.id.emptyStateTextView);
        appCountTextView = findViewById(R.id.appCountTextView);
        
        connectionStatusTextView.setText("Apps Manager");
        
        adapter = new AppsAdapter(this::onAppClicked);
        appsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        appsRecyclerView.setAdapter(adapter);
    }
    
    private void setupListeners() {
        backButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            finish();
        });
        
        refreshButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            // Disable button during refresh to prevent multiple clicks
            refreshButton.setEnabled(false);
            // Clear cache and reload apps synchronously
            refreshAndLoadApps();
        });
    }
    
    private void checkConnectionAndLoad() {
        if (!connectionManager.isConnected()) {
            if (connectedHost != null && connectedPort > 0) {
                reconnectAndLoad();
            } else {
                showError("Not connected to device");
            }
        } else {
            loadApps();
        }
    }
    
    private void reconnectAndLoad() {
        if (!isLoading.compareAndSet(false, true)) {
            logManager.logWarn("Reconnect already in progress, ignoring");
            return;
        }
        
        showLoading();
        connectionManager.startConnection(connectedHost, connectedPort, 
            new AdbServerManager.ConnectionCallback() {
                @Override
                public void onConnected() {
                    mainHandler.post(() -> {
                        Toast.makeText(AppsManagerActivity.this, 
                            "Reconnected", Toast.LENGTH_SHORT).show();
                        isLoading.set(false);
                        loadApps();
                    });
                }
                
                @Override
                public void onError(Exception e) {
                    mainHandler.post(() -> {
                        isLoading.set(false);
                        hideLoading();
                        showError("Connection failed: " + e.getMessage());
                    });
                }
            });
    }
    
    private void refreshAndLoadApps() {
        if (!isLoading.compareAndSet(false, true)) {
            logManager.logWarn("refreshAndLoadApps: Already loading, ignoring duplicate request");
            Toast.makeText(this, "Already refreshing...", Toast.LENGTH_SHORT).show();
            refreshButton.setEnabled(true);
            return;
        }
        
        logManager.logInfo("refreshAndLoadApps: Refreshing and loading all apps");
        showLoading();
        
        // Clear running packages cache first
        runningPackages.clear();
        logManager.logDebug("refreshAndLoadApps: Cleared runningPackages, size: " + runningPackages.size());
        
        // Step 1: Get running apps using ps -A command
        getRunningPackages(() -> {
            logManager.logInfo("refreshAndLoadApps: getRunningPackages completed, found " + runningPackages.size() + " running packages");
            
            // Log some running packages for debugging
            if (!runningPackages.isEmpty()) {
                int count = 0;
                for (String pkg : runningPackages) {
                    if (count < 5) {
                        logManager.logDebug("Running package: " + pkg);
                        count++;
                    }
                }
            }
            
            // Step 2: Clear app names cache
            try {
                CacheManager cacheManager = CacheManager.getInstance(AppsManagerActivity.this);
                cacheManager.clearAppNamesCache();
                logManager.logDebug("refreshAndLoadApps: Cleared app names cache");
            } catch (Exception e) {
                logManager.logWarn("Failed to clear cache: " + e.getMessage());
            }
            
            // Step 3: Get all installed apps from server
            logManager.logInfo("refreshAndLoadApps: Requesting user apps from server");
            serverClient.getUserApps(new ServerClient.ServerCallback<List<ServerClient.AppInfo>>() {
                @Override
                public void onSuccess(List<ServerClient.AppInfo> apps) {
                    logManager.logInfo("refreshAndLoadApps: Received " + apps.size() + " apps from server");
                    mainHandler.post(() -> {
                        isLoading.set(false);
                        mergeAndSortApps(apps);
                        refreshButton.setEnabled(true);
                        logManager.logInfo("refreshAndLoadApps: Completed successfully");
                    });
                }
                
                @Override
                public void onError(String error) {
                    logManager.logError("refreshAndLoadApps: Failed to get apps: " + error);
                    mainHandler.post(() -> {
                        isLoading.set(false);
                        hideLoading();
                        refreshButton.setEnabled(true);
                        showError("Failed to load apps: " + error);
                        emptyStateTextView.setVisibility(View.VISIBLE);
                        emptyStateTextView.setText("Failed to load apps: " + error);
                    });
                }
            });
        });
    }
    
    private void loadApps() {
        if (!isLoading.compareAndSet(false, true)) {
            logManager.logWarn("loadApps: Already loading, ignoring duplicate request");
            Toast.makeText(this, "Already refreshing...", Toast.LENGTH_SHORT).show();
            return;
        }
        
        logManager.logInfo("loadApps: Loading all apps");
        showLoading();
        
        // Step 1: Get running apps using ps -A command
        getRunningPackages(() -> {
            // Step 2: Get all installed apps from server
            serverClient.getUserApps(new ServerClient.ServerCallback<List<ServerClient.AppInfo>>() {
                @Override
                public void onSuccess(List<ServerClient.AppInfo> apps) {
                    mainHandler.post(() -> {
                        isLoading.set(false);
                        mergeAndSortApps(apps);
                    });
                }
                
                @Override
                public void onError(String error) {
                    mainHandler.post(() -> {
                        isLoading.set(false);
                        hideLoading();
                        showError("Failed to load apps: " + error);
                        emptyStateTextView.setVisibility(View.VISIBLE);
                        emptyStateTextView.setText("Failed to load apps: " + error);
                    });
                }
            });
        });
    }
    
    private void mergeAndSortApps(List<ServerClient.AppInfo> apps) {
        hideLoading();
        
        logManager.logInfo("mergeAndSortApps: Processing " + apps.size() + " apps, runningPackages size: " + runningPackages.size());
        
        // Convert to AppItem and mark running status
        List<AppItem> appItems = new ArrayList<>();
        int runningCount = 0;
        for (ServerClient.AppInfo app : apps) {
            AppItem item = new AppItem();
            item.packageName = app.packageName;
            item.name = app.name != null ? app.name : app.packageName;
            item.isRunning = runningPackages.contains(app.packageName);
            if (item.isRunning) {
                runningCount++;
                logManager.logDebug("mergeAndSortApps: Marked as running: " + app.packageName);
            }
            appItems.add(item);
        }
        
        logManager.logInfo("mergeAndSortApps: Found " + runningCount + " running apps out of " + appItems.size() + " total apps");
        
        // Sort: running apps first, then stopped apps
        Collections.sort(appItems, (a, b) -> {
            if (a.isRunning && !b.isRunning) return -1;
            if (!a.isRunning && b.isRunning) return 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        
        // Enhance with cached names using CacheManager
        try {
            CacheManager cacheManager = CacheManager.getInstance(this);
            for (AppItem item : appItems) {
                String cachedName = cacheManager.getAppName(item.packageName);
                if (cachedName != null && !cachedName.equals(item.packageName) && !cachedName.equals(item.name)) {
                    item.name = cachedName;
                }
            }
        } catch (Exception e) {
            // Fallback to AdbServerManager
            for (AppItem item : appItems) {
                String cachedName = AdbServerManager.getAppName(item.packageName, this);
                if (cachedName != null && !cachedName.equals(item.packageName) && !cachedName.equals(item.name)) {
                    item.name = cachedName;
                }
            }
        }
        
        if (appItems.isEmpty()) {
            emptyStateTextView.setVisibility(View.VISIBLE);
            emptyStateTextView.setText("No apps found");
            appsRecyclerView.setVisibility(View.GONE);
            appCountTextView.setText("Total: 0 apps");
        } else {
            emptyStateTextView.setVisibility(View.GONE);
            appsRecyclerView.setVisibility(View.VISIBLE);
            adapter.setApps(appItems);
            // Count running apps (runningCount was already calculated above)
            int finalRunningCount = 0;
            for (AppItem item : appItems) {
                if (item.isRunning) finalRunningCount++;
            }
            appCountTextView.setText("Total: " + appItems.size() + " apps (" + finalRunningCount + " running)");
        }
    }
    
    private void onAppClicked(AppItem app) {
        String[] actions;
        if (app.isRunning) {
            actions = new String[]{"Open App", "Force Stop", "Clear Data", "Uninstall"};
        } else {
            actions = new String[]{"Open App", "Clear Data", "Uninstall"};
        }
        
        new AlertDialog.Builder(this)
            .setTitle(app.name)
            .setItems(actions, (dialog, which) -> {
                switch (which) {
                    case 0: openApp(app); break;
                    case 1: 
                        if (app.isRunning) {
                            forceStopApp(app);
                        } else {
                            clearAppData(app);
                        }
                        break;
                    case 2:
                        if (app.isRunning) {
                            clearAppData(app);
                        } else {
                            uninstallApp(app);
                        }
                        break;
                    case 3:
                        if (app.isRunning) {
                            uninstallApp(app);
                        }
                        break;
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void openApp(AppItem app) {
        Toast.makeText(this, "Opening app...", Toast.LENGTH_SHORT).show();
        connectionManager.executeCommand("monkey -p " + app.packageName + " -c android.intent.category.LAUNCHER 1",
            new AdbServerManager.CommandCallback() {
                @Override
                public void onOutput(String out) {}
                
                @Override
                public void onError(String error) {
                    mainHandler.post(() -> showError("Failed to open app: " + error));
                }
                
                @Override
                public void onComplete(int exitCode) {
                    mainHandler.post(() -> {
                        if (exitCode == 0) {
                            Toast.makeText(AppsManagerActivity.this, "App opened", Toast.LENGTH_SHORT).show();
                            mainHandler.postDelayed(AppsManagerActivity.this::loadApps, 1500);
                        } else {
                            showError("Failed to open app");
                        }
                    });
                }
            });
    }
    
    private void forceStopApp(AppItem app) {
        new AlertDialog.Builder(this)
            .setTitle("Force Stop")
            .setMessage("Force stop " + app.name + "?")
            .setPositiveButton("Yes", (d, w) -> {
                Toast.makeText(this, "Stopping app...", Toast.LENGTH_SHORT).show();
                connectionManager.executeCommand("am force-stop " + app.packageName,
                    new AdbServerManager.CommandCallback() {
                        @Override
                        public void onOutput(String out) {}
                        
                        @Override
                        public void onError(String error) {
                            mainHandler.post(() -> showError("Failed to stop app: " + error));
                        }
                        
                        @Override
                        public void onComplete(int exitCode) {
                            mainHandler.post(() -> {
                                if (exitCode == 0) {
                                    Toast.makeText(AppsManagerActivity.this, "App stopped", Toast.LENGTH_SHORT).show();
                                    mainHandler.postDelayed(AppsManagerActivity.this::loadApps, 1500);
                                } else {
                                    showError("Failed to stop app");
                                }
                            });
                        }
                    });
            })
            .setNegativeButton("No", null)
            .show();
    }
    
    private void clearAppData(AppItem app) {
        new AlertDialog.Builder(this)
            .setTitle("Clear Data")
            .setMessage("Delete all data for " + app.name + "?")
            .setPositiveButton("Yes", (d, w) -> {
                Toast.makeText(this, "Clearing data...", Toast.LENGTH_SHORT).show();
                connectionManager.executeCommand("pm clear " + app.packageName,
                    new AdbServerManager.CommandCallback() {
                        @Override
                        public void onOutput(String out) {}
                        
                        @Override
                        public void onError(String error) {
                            mainHandler.post(() -> showError("Failed to clear data: " + error));
                        }
                        
                        @Override
                        public void onComplete(int exitCode) {
                            mainHandler.post(() -> {
                                if (exitCode == 0) {
                                    Toast.makeText(AppsManagerActivity.this, "Data cleared", Toast.LENGTH_SHORT).show();
                                    mainHandler.postDelayed(AppsManagerActivity.this::loadApps, 1500);
                                } else {
                                    showError("Failed to clear data");
                                }
                            });
                        }
                    });
            })
            .setNegativeButton("No", null)
            .show();
    }
    
    private void uninstallApp(AppItem app) {
        new AlertDialog.Builder(this)
            .setTitle("Uninstall")
            .setMessage("Uninstall " + app.name + "?")
            .setPositiveButton("Yes", (d, w) -> {
                Toast.makeText(this, "Uninstalling...", Toast.LENGTH_SHORT).show();
                connectionManager.executeCommand("pm uninstall --user 0 " + app.packageName,
                    new AdbServerManager.CommandCallback() {
                        @Override
                        public void onOutput(String out) {}
                        
                        @Override
                        public void onError(String error) {
                            mainHandler.post(() -> showError("Failed to uninstall: " + error));
                        }
                        
                        @Override
                        public void onComplete(int exitCode) {
                            mainHandler.post(() -> {
                                if (exitCode == 0) {
                                    Toast.makeText(AppsManagerActivity.this, "App uninstalled", Toast.LENGTH_SHORT).show();
                                    mainHandler.postDelayed(AppsManagerActivity.this::loadApps, 1500);
                                } else {
                                    showError("Failed to uninstall");
                                }
                            });
                        }
                    });
            })
            .setNegativeButton("No", null)
            .show();
    }
    
    private void showLoading() {
        loadingProgressBar.setVisibility(View.VISIBLE);
        emptyStateTextView.setVisibility(View.GONE);
        appsRecyclerView.setVisibility(View.GONE);
    }
    
    private void hideLoading() {
        loadingProgressBar.setVisibility(View.GONE);
    }
    
    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
    
    private void getRunningPackages(Runnable onComplete) {
        runningPackages.clear();
        StringBuilder output = new StringBuilder();
        
        connectionManager.executeCommand("ps -A", 
            new AdbServerManager.CommandCallback() {
                @Override
                public void onOutput(String out) {
                    if (out != null) output.append(out);
                }
                
                @Override
                public void onError(String error) {
                    logManager.logError("getRunningPackages: Failed to get running packages: " + error);
                    // Continue even if there's an error - apps will just show as not running
                    mainHandler.post(onComplete);
                }
                
                @Override
                public void onComplete(int exitCode) {
                    // Parse running packages from ps output
                    String result = output.toString();
                    logManager.logDebug("getRunningPackages: ps output length: " + result.length() + ", exitCode: " + exitCode);
                    
                    if (result.isEmpty()) {
                        logManager.logWarn("getRunningPackages: Empty output from ps command");
                        mainHandler.post(onComplete);
                        return;
                    }
                    
                    String[] lines = result.split("\n");
                    final String REMOTE_SERVER_PACKAGE = "com.freeadbremote.remoteserver";
                    int processedLines = 0;
                    
                    for (String line : lines) {
                        line = line.trim();
                        if (line.isEmpty() || line.contains("PID") || line.contains("USER")) continue;
                        
                        // Skip if contains remoteServer package
                        if (line.contains(REMOTE_SERVER_PACKAGE)) continue;
                        
                        // Extract package name (last word in line that contains a dot)
                        String[] parts = line.split("\\s+");
                        for (int i = parts.length - 1; i >= 0; i--) {
                            String part = parts[i];
                            if (part.contains(".") && part.length() > 3 && !REMOTE_SERVER_PACKAGE.equals(part)) {
                                // Handle u0_a60:package format
                                if (part.contains(":")) {
                                    String[] subParts = part.split(":", 2);
                                    if (subParts.length == 2 && !REMOTE_SERVER_PACKAGE.equals(subParts[1])) {
                                        runningPackages.add(subParts[1]);
                                        processedLines++;
                                    }
                                } else {
                                    runningPackages.add(part);
                                    processedLines++;
                                }
                                break;
                            }
                        }
                    }
                    
                    logManager.logInfo("getRunningPackages: Processed " + processedLines + " lines, found " + runningPackages.size() + " unique running packages");
                    if (!runningPackages.isEmpty()) {
                        int count = 0;
                        for (String pkg : runningPackages) {
                            if (count < 3) {
                                logManager.logDebug("getRunningPackages: Sample package: " + pkg);
                                count++;
                            }
                        }
                    }
                    mainHandler.post(onComplete);
                }
            });
    }
    
    // ============================================================================
    // Data Models
    // ============================================================================
    
    static class AppItem {
        String packageName;
        String name;
        boolean isRunning;
    }
    
    // ============================================================================
    // RecyclerView Adapter
    // ============================================================================
    
    static class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.ViewHolder> {
        private List<AppItem> apps = new ArrayList<>();
        private final OnAppClickListener listener;
        
        interface OnAppClickListener {
            void onAppClick(AppItem app);
        }
        
        AppsAdapter(OnAppClickListener listener) {
            this.listener = listener;
        }
        
        void setApps(List<AppItem> newApps) {
            this.apps = new ArrayList<>(newApps);
            notifyDataSetChanged();
        }
        
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_process, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            AppItem app = apps.get(position);
            
            holder.processNameTextView.setText(app.name);
            
            // Show running status with theme-aware colors
            if (app.isRunning) {
                holder.memoryTextView.setText("Running");
                // Use theme-aware color for running apps
                int runningColor = holder.itemView.getContext().getResources()
                    .getColor(android.R.color.holo_green_light, null);
                holder.itemView.setBackgroundColor(runningColor);
            } else {
                holder.memoryTextView.setText("Stopped");
                // Use theme-aware surface color
                int stoppedColor = holder.itemView.getContext().getResources()
                    .getColor(R.color.surface, null);
                holder.itemView.setBackgroundColor(stoppedColor);
            }
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onAppClick(app);
            });
        }
        
        @Override
        public int getItemCount() {
            return apps.size();
        }
        
        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView processNameTextView;
            TextView memoryTextView;
            
            ViewHolder(View view) {
                super(view);
                processNameTextView = view.findViewById(R.id.processNameTextView);
                memoryTextView = view.findViewById(R.id.memoryTextView);
            }
        }
    }
}

