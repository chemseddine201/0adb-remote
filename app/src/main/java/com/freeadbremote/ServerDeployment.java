package com.freeadbremote;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerDeployment {

    private static final String TAG = "ServerDeployment";

    private static final String APK_ASSET_NAME = "server.apk"; // apk in assets/
    private static final String REMOTE_APK_PATH = "/data/local/tmp/server.apk";

    private static final String PACKAGE_NAME = "com.freeadbremote.remoteserver";
    private static final String MAIN_ACTIVITY = "com.freeadbremote.remoteserver/.LauncherActivity"; // Format: package/.ActivityName

    private final Context context;
    private final AdbConnectionManager connectionManager;
    private final LogManager logManager;
    private final ExecutorService executor;

    public interface DeploymentCallback {
        void onSuccess(String packageName);
        void onError(String error);
    }

    public ServerDeployment(Context ctx, AdbConnectionManager mgr, LogManager log) {
        this.context = ctx;
        this.connectionManager = mgr;
        this.logManager = log;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * FINAL CLEAN METHOD
     * Install APK from assets via ADB and start it once
     */
    public void deployAndStartServer(DeploymentCallback callback) {
        executor.execute(() -> {
            try {
                log("=== Starting APK Deployment ===");

                AdbServerManager serverManager = connectionManager.getServerManager();
                if (serverManager == null) {
                    fail(callback, "ServerManager unavailable");
                    return;
                }

                AdbClient adb = serverManager.getAdbClient();
                if (adb == null || !adb.isConnected()) {
                    fail(callback, "ADB not connected");
                    return;
                }

                // STEP 1 — CHECK IF NEW VERSION NEEDED
                log("=== STEP 1: CHECKING APK VERSION ===");
                
                // Get version from assets APK
                File localApk = copyApkFromAssets();
                if (localApk == null || !localApk.exists()) {
                    fail(callback, "Failed to copy APK from assets");
                    return;
                }
                
                long assetsVersionCode = getApkVersionCode(localApk);
                log("Assets APK versionCode: " + assetsVersionCode);
                
                // First, check if package is actually installed (not just cached info)
                boolean isInstalled = isPackageInstalled(adb);
                log("Package is installed: " + isInstalled);
                
                // Get version from installed APK on device (only if installed)
                long installedVersionCode = 0;
                if (isInstalled) {
                    installedVersionCode = getInstalledVersionCode(adb);
                    log("Installed APK versionCode: " + installedVersionCode);
                } else {
                    log("Package not installed, skipping version check");
                }
                
                // Check if file already exists on device
                boolean fileExists = checkRemoteFileExists(adb, REMOTE_APK_PATH);
                log("Remote APK file exists: " + fileExists);
                
                // If installed, same version, and file exists, skip copy/install and just start
                if (isInstalled && installedVersionCode > 0 && installedVersionCode == assetsVersionCode && fileExists) {
                    log("Same version detected (" + assetsVersionCode + ") and file already exists. Skipping copy/install, starting app directly.");
                    
                    // Just start the app
                    final boolean[] startOK = {false};
                    final Object startLock = new Object();
                    String startCmd = "am start -n " + MAIN_ACTIVITY;
                    
                    adb.executeCommand(startCmd, new AdbClient.CommandCallback() {
                        @Override public void onOutput(String o) {
                            if (o != null && !o.trim().isEmpty()) {
                                log("am start output: " + o);
                            }
                        }
                        @Override public void onError(String e) {
                            log("am start error: " + e);
                        }
                        @Override public void onComplete(int exit) {
                            startOK[0] = (exit == 0);
                            synchronized (startLock) { startLock.notify(); }
                        }
                    });
                    
                    synchronized (startLock) { startLock.wait(5000); }
                    
                    if (startOK[0]) {
                        log("App started successfully (same version, file exists, no deployment needed)");
                        callback.onSuccess(PACKAGE_NAME);
                    } else {
                        fail(callback, "Failed to start app");
                    }
                    return;
                }
                
                // If same version but file doesn't exist, we need to copy but can skip install
                boolean skipInstall = (isInstalled && installedVersionCode > 0 && installedVersionCode == assetsVersionCode);
                
                if (skipInstall) {
                    log("Same version detected (" + assetsVersionCode + ") but file missing. Copying file only (no install needed).");
                } else {
                    log("New version detected or not installed. Proceeding with full deployment...");
                }
                log("APK copied successfully: " + localApk.getAbsolutePath());
                log("APK size: " + localApk.length() + " bytes");

                // STEP 2 — PUSH APK USING SOCKET DEPLOY (Fast & Reliable)
                log("=== STEP 2: PUSHING APK USING SOCKET DEPLOY ===");
                log("Local APK path: " + localApk.getAbsolutePath());
                log("Local APK size: " + localApk.length() + " bytes");
                log("Remote APK path: " + REMOTE_APK_PATH);
                
                boolean pushSuccess = pushFileViaSocket(adb, localApk, REMOTE_APK_PATH);
                
                if (!pushSuccess) {
                    fail(callback, "Socket Deploy failed");
                    return;
                }
                
                log("APK pushed successfully via Socket Deploy");

                // STEP 3 — INSTALL APK FROM /data/local/tmp/ (only if needed)
                if (skipInstall) {
                    log("=== STEP 3: SKIPPING INSTALL (same version already installed) ===");
                    log("File copied successfully. Installation not needed.");
                } else {
                    log("=== STEP 3: INSTALLING APK FROM /data/local/tmp/ ===");
                    log("Install command: pm install -r " + REMOTE_APK_PATH);
                }

                final boolean[] installOK = {skipInstall}; // If skipInstall, mark as OK
                final String[] installError = {null};
                final Object installLock = new Object();

                // Only install if not skipping
                if (!skipInstall) {
                    // Try without su first (works on most devices)
                    String installCmd = "pm install -r " + REMOTE_APK_PATH;
                    log("Trying install without su: " + installCmd);
                    
                    // Clean shell first
                    adb.executeCommand("echo ''", new AdbClient.CommandCallback() {
                        @Override public void onOutput(String o) {}
                        @Override public void onError(String e) {}
                        @Override public void onComplete(int exit) {}
                    });
                    Thread.sleep(100);
                    
                    adb.executeCommand(installCmd, new AdbClient.CommandCallback() {
                    @Override public void onOutput(String o) {
                        log("install output: " + o);
                        if (o != null && (o.contains("Success") || o.toLowerCase().contains("success") || 
                            o.contains("INSTALL_SUCCEEDED"))) {
                            installOK[0] = true;
                            log("Install succeeded (detected from output)");
                        }
                    }
                    @Override public void onError(String e) {
                        log("install error (without su): " + e);
                        installError[0] = e;
                    }
                    @Override public void onComplete(int exit) {
                        log("install complete (exitCode=" + exit + ")");
                        if (exit == 0) {
                            installOK[0] = true;
                            log("Install succeeded (exitCode=0)");
                        }
                        synchronized (installLock) { installLock.notify(); }
                    }
                    });

                    synchronized (installLock) { installLock.wait(30000); }

                    // If failed without su, try with su
                    if (!installOK[0]) {
                    log("Install without su failed, trying with su...");
                    installOK[0] = false;
                    installError[0] = null;
                    
                    String installCmdSu = "su -c 'pm install -r " + REMOTE_APK_PATH + "'";
                    log("Trying install with su: " + installCmdSu);
                    
                    // Clean shell first
                    adb.executeCommand("echo ''", new AdbClient.CommandCallback() {
                        @Override public void onOutput(String o) {}
                        @Override public void onError(String e) {}
                        @Override public void onComplete(int exit) {}
                    });
                    Thread.sleep(100);
                    
                    adb.executeCommand(installCmdSu, new AdbClient.CommandCallback() {
                        @Override public void onOutput(String o) {
                            log("install output (with su): " + o);
                            if (o != null && (o.contains("Success") || o.toLowerCase().contains("success") || 
                                o.contains("INSTALL_SUCCEEDED"))) {
                                installOK[0] = true;
                                log("Install succeeded with su (detected from output)");
                            }
                        }
                        @Override public void onError(String e) {
                            log("install error (with su): " + e);
                            installError[0] = e;
                        }
                        @Override public void onComplete(int exit) {
                            log("install complete with su (exitCode=" + exit + ")");
                            if (exit == 0) {
                                installOK[0] = true;
                                log("Install succeeded with su (exitCode=0)");
                            }
                            synchronized (installLock) { installLock.notify(); }
                        }
                    });
                    
                        synchronized (installLock) { installLock.wait(30000); }
                    }
                }
                
                if (!installOK[0]) {
                    String errorMsg = "APK installation failed";
                    if (installError[0] != null) {
                        errorMsg += ": " + installError[0];
                    }
                    fail(callback, errorMsg);
                    return;
                }

                if (skipInstall) {
                    log("Installation skipped (same version already installed).");
                } else {
                    log("APK installed successfully.");
                }
                
                // Keep file on device for future use (do not delete)
                log("APK file kept on device at " + REMOTE_APK_PATH + " for future deployments");

                // STEP 4 — VERIFY INSTALLATION (only if we installed)
                if (skipInstall) {
                    log("=== STEP 4: SKIPPING VERIFICATION (no installation performed) ===");
                } else {
                    log("=== STEP 4: VERIFYING INSTALLATION ===");
                    Thread.sleep(2000); // Wait for package manager to update
                    
                    final boolean[] verified = {false};
                    final Object verifyLock = new Object();
                    
                    // Clean shell first
                    adb.executeCommand("echo ''", new AdbClient.CommandCallback() {
                        @Override public void onOutput(String o) {}
                        @Override public void onError(String e) {}
                        @Override public void onComplete(int exit) {}
                    });
                    Thread.sleep(100);
                    
                    String verifyCmd = "pm path " + PACKAGE_NAME;
                    final StringBuilder verifyBuffer = new StringBuilder();
                    
                    adb.executeCommand(verifyCmd, new AdbClient.CommandCallback() {
                        @Override public void onOutput(String o) {
                            if (o != null) {
                                verifyBuffer.append(o);
                            }
                        }
                        @Override public void onError(String e) {
                            log("verify error: " + e);
                        }
                        @Override public void onComplete(int exit) {
                            String fullOutput = verifyBuffer.toString().trim();
                            if (fullOutput.contains("package:")) {
                                String[] lines = fullOutput.split("\n");
                                for (String line : lines) {
                                    line = line.trim();
                                    if (line.startsWith("package:")) {
                                        verified[0] = true;
                                        log("Package verified as installed: " + line);
                                        break;
                                    }
                                }
                            }
                            synchronized (verifyLock) { verifyLock.notify(); }
                        }
                    });
                    
                    synchronized (verifyLock) { verifyLock.wait(5000); }
                    
                    if (verified[0]) {
                        log("Installation verified successfully");
                    } else {
                        log("WARNING: Installation completed but package verification failed - continuing anyway");
                    }
                }

                // STEP 5 — START THE APP IN BACKGROUND
                log("=== STEP 5: STARTING THE APP ===");
                log("Starting app using: am start -n " + MAIN_ACTIVITY);

                final boolean[] startOK = {false};
                final Object startLock = new Object();

                // Use the simple command that works perfectly
                String startCmd = "am start -n " + MAIN_ACTIVITY;
                
                adb.executeCommand(startCmd, new AdbClient.CommandCallback() {
                    @Override public void onOutput(String o) {
                        if (o != null && !o.trim().isEmpty()) {
                            log("am start output: " + o);
                        }
                    }
                    @Override public void onError(String e) {
                        log("am start error: " + e);
                    }
                    @Override public void onComplete(int exit) {
                        startOK[0] = (exit == 0);
                        if (exit == 0) {
                            log("App started successfully (exitCode=0)");
                        } else {
                            log("App start failed (exitCode=" + exit + ")");
                        }
                        synchronized (startLock) { startLock.notify(); }
                    }
                });

                synchronized (startLock) { startLock.wait(5000); }

                if (!startOK[0]) {
                    log("Warning: am start command failed, but continuing deployment");
                }
                
                // Wait a bit for server to initialize
                Thread.sleep(2000);
                log("Deployment process completed");

                callback.onSuccess(PACKAGE_NAME);

            } catch (Exception e) {
                fail(callback, "Deployment failed: " + e.getMessage());
            }
        });
    }

    /**
     * Push file using Socket Deploy (fast and reliable)
     * Uses netcat on device to receive file via socket
     */
    private boolean pushFileViaSocket(AdbClient adb, File localApk, String remotePath) {
        Socket socket = null;
        FileInputStream fis = null;
        OutputStream os = null;
        
        try {
            log("=== Starting Socket Deploy ===");
            
            // Step 1: Clean up any existing file and old listeners
            log("Cleaning up old files and processes...");
            adb.executeCommand("rm -f " + remotePath + " && pkill -f 'nc -l -p 9999' 2>/dev/null; true", 
                new AdbClient.CommandCallback() {
                    @Override public void onOutput(String o) {}
                    @Override public void onError(String e) {}
                    @Override public void onComplete(int code) {}
                });
            Thread.sleep(500);
            
            // Step 2: Start socket listener on device using netcat (in background, detached)
            int socketPort = 9999;
            String listenerCmd = "nohup sh -c 'nc -l -p " + socketPort + " > " + remotePath + " 2>/dev/null' >/dev/null 2>&1 &";
            
            log("Starting socket listener on port " + socketPort + "...");
            
            final Object listenerLock = new Object();
            final boolean[] listenerStarted = {false};
            
            adb.executeCommand(listenerCmd, new AdbClient.CommandCallback() {
                @Override public void onOutput(String o) {}
                @Override public void onError(String e) {}
                @Override public void onComplete(int code) {
                    synchronized (listenerLock) {
                        listenerStarted[0] = true;
                        listenerLock.notify();
                    }
                }
            });
            
            synchronized (listenerLock) {
                listenerLock.wait(2000);
            }
            
            // Wait for listener to be ready
            Thread.sleep(1500);
            
            // Step 3: Connect and send file via socket
            log("Connecting to device and sending file...");
            
            // Get device IP from connection info
            String connectionInfo = adb.getConnectionInfo();
            String deviceIp = "192.168.1.128"; // Default
            if (connectionInfo != null && connectionInfo.contains(":")) {
                String[] parts = connectionInfo.split(":");
                if (parts.length > 0) {
                    deviceIp = parts[0];
                }
            }
            
            log("Opening socket connection to " + deviceIp + ":" + socketPort);
            socket = new Socket(deviceIp, socketPort);
            socket.setSoTimeout(60000); // 60 second timeout
            socket.setTcpNoDelay(true); // Disable Nagle algorithm for faster transfer
            
            log("Socket connected, starting file transfer...");
            fis = new FileInputStream(localApk);
            os = socket.getOutputStream();
            
            byte[] buffer = new byte[65536]; // 64KB buffer for faster transfer
            int read;
            long totalSent = 0;
            long fileSize = localApk.length();
            
            while ((read = fis.read(buffer)) != -1) {
                os.write(buffer, 0, read);
                totalSent += read;
                
                // Log progress every 1MB
                if (totalSent % (1024 * 1024) == 0) {
                    int percent = (int) ((totalSent * 100) / fileSize);
                    log("Progress: " + percent + "% (" + totalSent + "/" + fileSize + " bytes)");
                }
            }
            
            os.flush();
            log("File transfer complete: " + totalSent + "/" + fileSize + " bytes");
            
            // Close socket to signal EOF
            socket.shutdownOutput();
            Thread.sleep(100);
            socket.close();
            socket = null;
            
            // Wait for file to be fully written to disk
            log("Waiting for file to be written to disk...");
            Thread.sleep(2000); // Give more time for disk write
            
            // Verify file was written
            log("Verifying file existence...");
            final boolean[] verifyExists = {false};
            final Object verifyLock = new Object();
            
            adb.executeCommand("test -f " + remotePath + " && echo FILE_EXISTS || echo FILE_NOT_FOUND", 
                new AdbClient.CommandCallback() {
                    @Override public void onOutput(String o) {
                        if (o != null && o.contains("FILE_EXISTS")) {
                            verifyExists[0] = true;
                        }
                    }
                    @Override public void onError(String e) {}
                    @Override public void onComplete(int code) {
                        synchronized (verifyLock) { verifyLock.notify(); }
                    }
                });
            
            synchronized (verifyLock) {
                verifyLock.wait(5000);
            }
            
            if (!verifyExists[0]) {
                log("ERROR: File verification failed - file does not exist");
                return false;
            }
            
            log("Socket Deploy completed successfully");
            return true;
            
        } catch (Exception e) {
            log("ERROR: Socket Deploy failed: " + e.getMessage());
            Log.e(TAG, "Socket Deploy error", e);
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignore) {}
            try { if (os != null) os.close(); } catch (Exception ignore) {}
            try { if (socket != null) socket.close(); } catch (Exception ignore) {}
        }
    }

    // Extract APK file from assets
    private File copyApkFromAssets() {
        try {
            log("Opening APK from assets: " + APK_ASSET_NAME);
            InputStream is = context.getAssets().open(APK_ASSET_NAME);
            
            // Use getExternalFilesDir() instead of getFilesDir() for better compatibility
            // getExternalFilesDir() returns /sdcard/Android/data/com.freeadbremote/files/
            // which is accessible without special permissions
            File externalFilesDir = context.getExternalFilesDir(null);
            if (externalFilesDir == null) {
                // Fallback to cache directory if external storage is not available
                log("External files dir not available, using cache dir");
                externalFilesDir = context.getCacheDir();
            }
            
            // Ensure directory exists
            if (!externalFilesDir.exists()) {
                boolean created = externalFilesDir.mkdirs();
                log("Created directory: " + created + " at " + externalFilesDir.getAbsolutePath());
            }
            
            File local = new File(externalFilesDir, APK_ASSET_NAME);
            log("Target file: " + local.getAbsolutePath());
            log("Target directory exists: " + externalFilesDir.exists());
            log("Target directory writable: " + externalFilesDir.canWrite());
            
            // Delete existing file if present
            if (local.exists()) {
                boolean deleted = local.delete();
                log("Deleted existing file: " + deleted);
            }
            
            // Verify we can write to the directory
            if (!externalFilesDir.canWrite()) {
                throw new IOException("Cannot write to directory: " + externalFilesDir.getAbsolutePath());
            }
            
            FileOutputStream fos = new FileOutputStream(local);
            log("FileOutputStream created, copying data...");

            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                totalRead += read;
            }

            fos.flush();
            fos.close();
            is.close();
            
            log("APK copy completed: " + totalRead + " bytes");
            log("File exists: " + local.exists());
            log("File size: " + local.length() + " bytes");
            
            if (!local.exists() || local.length() == 0) {
                log("ERROR: Copied file is missing or empty!");
                return null;
            }
            
            return local;

        } catch (Exception e) {
            log("APK copy error: " + e.getMessage());
            if (e.getCause() != null) {
                log("APK copy error cause: " + e.getCause().getMessage());
            }
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Get versionCode from APK file
     */
    private long getApkVersionCode(File apkFile) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (info != null) {
                return info.versionCode;
            }
        } catch (Exception e) {
            log("Error reading APK version: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Check if package is actually installed on device
     */
    private boolean isPackageInstalled(AdbClient adb) {
        final boolean[] isInstalled = {false};
        final Object lock = new Object();
        final StringBuilder outputBuffer = new StringBuilder();
        
        try {
            // Clean shell first
            adb.executeCommand("echo ''", new AdbClient.CommandCallback() {
                @Override public void onOutput(String o) {}
                @Override public void onError(String e) {}
                @Override public void onComplete(int exit) {}
            });
            Thread.sleep(200);
            
            String cmd = "pm path " + PACKAGE_NAME;
            
            adb.executeCommand(cmd, new AdbClient.CommandCallback() {
                @Override
                public void onOutput(String o) {
                    if (o != null) {
                        outputBuffer.append(o);
                    }
                }

                @Override
                public void onError(String e) {
                    // Package not installed
                }

                @Override
                public void onComplete(int exit) {
                    // Parse complete output after collecting all chunks
                    String fullOutput = outputBuffer.toString().trim();
                    // Check if output contains "package:" (means installed)
                    // Also filter out command echo and other noise
                    if (fullOutput.contains("package:")) {
                        // Extract the actual package path line
                        String[] lines = fullOutput.split("\n");
                        for (String line : lines) {
                            line = line.trim();
                            if (line.startsWith("package:")) {
                                isInstalled[0] = true;
                                log("Package installation verified: " + line);
                                break;
                            }
                        }
                    } else {
                        log("Package not installed (no 'package:' in output)");
                    }
                    synchronized (lock) {
                        lock.notify();
                    }
                }
            });

            synchronized (lock) {
                lock.wait(5000);
            }
        } catch (Exception e) {
            log("Error checking package installation: " + e.getMessage());
        }
        
        return isInstalled[0];
    }

    /**
     * Check if remote file exists on device
     */
    private boolean checkRemoteFileExists(AdbClient adb, String remotePath) {
        final boolean[] exists = {false};
        final Object lock = new Object();
        
        try {
            String cmd = "test -f " + remotePath + " && echo FILE_EXISTS || echo FILE_NOT_FOUND";
            
            adb.executeCommand(cmd, new AdbClient.CommandCallback() {
                @Override
                public void onOutput(String o) {
                    if (o != null && o.contains("FILE_EXISTS")) {
                        exists[0] = true;
                    }
                }

                @Override
                public void onError(String e) {
                    // File doesn't exist
                }

                @Override
                public void onComplete(int exit) {
                    synchronized (lock) {
                        lock.notify();
                    }
                }
            });

            synchronized (lock) {
                lock.wait(3000);
            }
        } catch (Exception e) {
            log("Error checking file existence: " + e.getMessage());
        }
        
        return exists[0];
    }

    /**
     * Get versionCode from installed package on device
     * Note: Only call this if isPackageInstalled() returns true
     */
    private long getInstalledVersionCode(AdbClient adb) {
        final long[] versionCode = {0};
        final Object lock = new Object();
        final StringBuilder outputBuffer = new StringBuilder();
        
        try {
            // Clean shell first
            adb.executeCommand("echo ''", new AdbClient.CommandCallback() {
                @Override public void onOutput(String o) {}
                @Override public void onError(String e) {}
                @Override public void onComplete(int exit) {}
            });
            Thread.sleep(100);
            
            // Use dumpsys to get versionCode - collect all output chunks
            String cmd = "dumpsys package " + PACKAGE_NAME + " | grep versionCode";
            
            adb.executeCommand(cmd, new AdbClient.CommandCallback() {
                @Override
                public void onOutput(String o) {
                    if (o != null) {
                        outputBuffer.append(o);
                    }
                }

                @Override
                public void onError(String e) {
                    // Package not installed or error
                }

                @Override
                public void onComplete(int exit) {
                    // Parse complete output after collecting all chunks
                    String fullOutput = outputBuffer.toString().trim();
                    if (fullOutput.contains("versionCode=")) {
                        try {
                            String codeStr = fullOutput.substring(fullOutput.indexOf("versionCode=") + 12).trim();
                            // Extract number (may have other text after)
                            int spaceIdx = codeStr.indexOf(' ');
                            if (spaceIdx > 0) {
                                codeStr = codeStr.substring(0, spaceIdx);
                            }
                            // Also check for newline
                            int newlineIdx = codeStr.indexOf('\n');
                            if (newlineIdx > 0) {
                                codeStr = codeStr.substring(0, newlineIdx);
                            }
                            versionCode[0] = Long.parseLong(codeStr);
                            log("Parsed versionCode from dumpsys: " + versionCode[0]);
                        } catch (Exception e) {
                            log("Error parsing versionCode from: " + fullOutput);
                        }
                    }
                    synchronized (lock) {
                        lock.notify();
                    }
                }
            });

            synchronized (lock) {
                lock.wait(5000);
            }
        } catch (Exception e) {
            log("Error getting installed version: " + e.getMessage());
        }
        
        return versionCode[0];
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        if (logManager != null) logManager.logInfo(msg);
    }

    private void fail(DeploymentCallback cb, String error) {
        Log.e(TAG, error);
        if (logManager != null) logManager.logError(error);
        cb.onError(error);
    }

        /**
     * START THE INSTALLED APK (only start, no kill)
     */
    public void startInstalledApk(ServerDeployment.DeploymentCallback callback) {
        executor.execute(() -> {
            try {
                log("=== Starting installed APK ===");

                AdbServerManager serverManager = connectionManager.getServerManager();
                if (serverManager == null) {
                    fail(callback, "ServerManager unavailable");
                    return;
                }

                AdbClient adb = serverManager.getAdbClient();
                if (adb == null || !adb.isConnected()) {
                    fail(callback, "ADB not connected");
                    return;
                }

                final boolean[] success = {false};
                final Object lock = new Object();

                // Use the simple command that works perfectly
                String startCmd = "am start -n " + MAIN_ACTIVITY;

                adb.executeCommand(startCmd, new AdbClient.CommandCallback() {
                    @Override public void onOutput(String o) {
                        if (o != null && !o.trim().isEmpty()) {
                            log("start output: " + o);
                        }
                    }

                    @Override public void onError(String e) {
                        log("start error: " + e);
                    }

                    @Override public void onComplete(int exit) {
                        success[0] = (exit == 0);
                        synchronized (lock) { lock.notify(); }
                    }
                });

                synchronized (lock) { lock.wait(5000); }

                if (!success[0]) {
                    fail(callback, "Failed to start APK");
                    return;
                }

                log("APK started successfully.");
                callback.onSuccess(PACKAGE_NAME);

            } catch (Exception e) {
                fail(callback, "Start failed: " + e.getMessage());
            }
        });
    }



    /**
     * Check if the APK is installed on the device
     */
    public void checkIfInstalled(ServerDeployment.DeploymentCallback callback) {
        executor.execute(() -> {
            try {
                log("=== Checking if APK is installed ===");

                AdbServerManager serverManager = connectionManager.getServerManager();
                if (serverManager == null) {
                    fail(callback, "ServerManager unavailable");
                    return;
                }

                AdbClient adb = serverManager.getAdbClient();
                if (adb == null || !adb.isConnected()) {
                    fail(callback, "ADB not connected");
                    return;
                }

                final boolean[] isInstalled = {false};
                final Object checkLock = new Object();
                final StringBuilder outputBuffer = new StringBuilder();

                // Simple and reliable method: pm path
                // pm path returns "package:/path/to/apk" if installed, nothing if not
                log("Checking installation using: pm path");
                
                // Clean shell first to avoid mixed output
                adb.executeCommand("echo ''", new AdbClient.CommandCallback() {
                    @Override public void onOutput(String o) {}
                    @Override public void onError(String e) {}
                    @Override public void onComplete(int exit) {}
                });
                Thread.sleep(200);
                
                String checkCmd = "pm path " + PACKAGE_NAME;

                adb.executeCommand(checkCmd, new AdbClient.CommandCallback() {
                    @Override
                    public void onOutput(String o) {
                        if (o != null) {
                            outputBuffer.append(o);
                        }
                    }

                    @Override
                    public void onError(String e) {
                        log("check error: " + e);
                        // Error or empty output means package not found
                        isInstalled[0] = false;
                    }

                    @Override
                    public void onComplete(int exit) {
                        // Parse complete output after collecting all chunks
                        String fullOutput = outputBuffer.toString().trim();
                        // Check if output contains "package:" (means installed)
                        // Also filter out command echo and other noise
                        if (fullOutput.contains("package:")) {
                            // Extract the actual package path line
                            String[] lines = fullOutput.split("\n");
                            for (String line : lines) {
                                line = line.trim();
                                if (line.startsWith("package:")) {
                                    isInstalled[0] = true;
                                    log("Package found: " + line);
                                    break;
                                }
                            }
                        } else {
                            log("Package not found (no 'package:' in output)");
                        }
                        log("check complete (exitCode=" + exit + ", isInstalled=" + isInstalled[0] + ")");
                        synchronized (checkLock) {
                            checkLock.notifyAll();
                        }
                    }
                });

                synchronized (checkLock) {
                    checkLock.wait(5000);
                }

                if (isInstalled[0]) {
                    log("APK is installed on device (verified)");
                    callback.onSuccess(PACKAGE_NAME);
                } else {
                    log("APK is NOT installed on device (verified)");
                    callback.onError("APK not installed");
                }

            } catch (Exception e) {
                fail(callback, "Check failed: " + e.getMessage());
            }
        });
    }

    /**
     * RESTART THE INSTALLED APK
     * (Kill existing processes + start app again)
     */
    public void restartInstalledApk(ServerDeployment.DeploymentCallback callback) {
        executor.execute(() -> {
            try {
                log("=== Restarting installed APK ===");

                AdbServerManager serverManager = connectionManager.getServerManager();
                if (serverManager == null) {
                    fail(callback, "ServerManager unavailable");
                    return;
                }

                AdbClient adb = serverManager.getAdbClient();
                if (adb == null || !adb.isConnected()) {
                    fail(callback, "ADB not connected");
                    return;
                }

                // STEP 1 — KILL ANY RUNNING PROCESS OF THE PACKAGE
                log("Killing existing APK processes...");

                final Object killLock = new Object();

                String killCmd =
                        "su -c 'pkill -9 -f " + PACKAGE_NAME + " 2>/dev/null || true'";

                adb.executeCommand(killCmd, new AdbClient.CommandCallback() {
                    @Override public void onOutput(String o) {
                        log("kill output: " + o);
                    }

                    @Override public void onError(String e) {
                        log("kill error: " + e);
                    }

                    @Override public void onComplete(int exit) {
                        synchronized (killLock) { killLock.notify(); }
                    }
                });

                synchronized (killLock) { killLock.wait(3000); }
                Thread.sleep(1500);

                log("APK killed (if it was running).");

                // STEP 2 — START APK
                final boolean[] startOK = {false};
                final Object startLock = new Object();

                // Use the simple command that works perfectly
                String startCmd = "am start -n " + MAIN_ACTIVITY;
                log("Starting APK again...");

                adb.executeCommand(startCmd, new AdbClient.CommandCallback() {
                    @Override public void onOutput(String o) {
                        if (o != null && !o.trim().isEmpty()) {
                            log("start output: " + o);
                        }
                    }

                    @Override public void onError(String e) {
                        log("start error: " + e);
                    }

                    @Override public void onComplete(int exit) {
                        startOK[0] = (exit == 0);
                        synchronized (startLock) { startLock.notify(); }
                    }
                });

                synchronized (startLock) { startLock.wait(5000); }

                if (!startOK[0]) {
                    fail(callback, "Failed to restart APK");
                    return;
                }

                log("APK restarted successfully.");
                callback.onSuccess(PACKAGE_NAME);

            } catch (Exception e) {
                fail(callback, "Restart failed: " + e.getMessage());
            }
        });
    }

}
