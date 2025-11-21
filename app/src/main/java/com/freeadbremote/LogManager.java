package com.freeadbremote;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Log Manager - Captures and saves logs to file for debugging
 */
public class LogManager {
    private static final String TAG = "FreeAdbRemote";
    private static final String LOG_FILE_NAME = "adbremote_logs.txt";
    private static LogManager instance;
    private File logFile;
    private FileWriter fileWriter;
    private SimpleDateFormat dateFormat;
    private Context context;

    private LogManager(Context context) {
        this.context = context.getApplicationContext();
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        initializeLogFile();
    }

    public static synchronized LogManager getInstance(Context context) {
        if (instance == null) {
            instance = new LogManager(context);
        }
        return instance;
    }

    private void initializeLogFile() {
        try {
            File logDir = new File(context.getExternalFilesDir(null), "logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            
            logFile = new File(logDir, LOG_FILE_NAME);
            
            // Append to existing log file
            fileWriter = new FileWriter(logFile, true);
            
            log("INFO", "LogManager initialized. Log file: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize log file", e);
        }
    }

    public void log(String level, String message) {
        // CRITICAL: Always write to Android Log first (logcat) - this always works
        switch (level) {
            case "ERROR":
                Log.e(TAG, message);
                break;
            case "WARN":
                Log.w(TAG, message);
                break;
            case "DEBUG":
                Log.d(TAG, message);
                break;
            default:
                Log.i(TAG, message);
        }
        
        // Write to file - reopen if closed
        String timestamp = dateFormat.format(new Date());
        String logEntry = String.format("[%s] [%s] %s\n", timestamp, level, message);
        
        try {
            if (fileWriter == null) {
                Log.w(TAG, "fileWriter is null, attempting to reopen log file");
                reopenLogFile();
            }
            if (fileWriter != null) {
                fileWriter.write(logEntry);
                fileWriter.flush();
            } else {
                // If fileWriter is still null after reopen, log to Android Log only
                Log.w(TAG, "fileWriter is still null after reopen attempt. Log file: " + 
                    (logFile != null ? logFile.getAbsolutePath() : "null"));
            }
        } catch (IOException e) {
            // Try to reopen if stream was closed
            Log.w(TAG, "IOException writing to log file, attempting reopen: " + e.getMessage());
            try {
                reopenLogFile();
                if (fileWriter != null) {
                    fileWriter.write(logEntry);
                    fileWriter.flush();
                } else {
                    Log.e(TAG, "fileWriter still null after reopen. Cannot write to file.");
                }
            } catch (IOException e2) {
                Log.e(TAG, "Failed to write to log file after reopen: " + e2.getMessage());
                Log.e(TAG, "Log file path: " + (logFile != null ? logFile.getAbsolutePath() : "null"));
            }
        } catch (Exception e) {
            // Catch any other exceptions
            Log.e(TAG, "Unexpected error writing to log file: " + e.getMessage(), e);
        }
    }
    
    private void reopenLogFile() {
        try {
            if (fileWriter != null) {
                try {
                    fileWriter.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            if (logFile != null) {
                fileWriter = new FileWriter(logFile, true); // Append mode
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to reopen log file", e);
            fileWriter = null;
        }
    }

    public void logError(String message) {
        log("ERROR", message);
    }
    
    public void logError(String message, Throwable throwable) {
        // Handle null or invalid throwable gracefully
        if (throwable == null) {
            log("ERROR", message);
            return;
        }
        
        String errorMsg = message;
        
        // Safely extract error message from throwable
        try {
            String throwableMsg = throwable.getMessage();
            if (throwableMsg != null && !throwableMsg.trim().isEmpty()) {
                errorMsg += ": " + throwableMsg;
            } else {
                // If message is empty, try to get class name
                try {
                    String className = throwable.getClass().getSimpleName();
                    if (className != null && !className.isEmpty()) {
                        errorMsg += ": " + className;
                    }
                } catch (Exception e) {
                    // If getting class name fails, just use "Exception"
                    errorMsg += ": Exception";
                }
            }
        } catch (Exception e) {
            // If getting message fails completely, try to get class name
            try {
                String className = throwable.getClass().getSimpleName();
                if (className != null && !className.isEmpty()) {
                    errorMsg += ": " + className;
                } else {
                    errorMsg += ": Exception";
                }
            } catch (Exception e2) {
                // If even getting class name fails, just use the original message
                // Don't append anything
            }
        }
        
        // Log the error message
        log("ERROR", errorMsg);
        
        // Write stack trace to file (if file writer is available)
        // This is wrapped in try-catch to prevent logging errors from breaking the app
        try {
            if (fileWriter != null && throwable != null) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                
                // Safely print stack trace
                try {
                    throwable.printStackTrace(pw);
                    pw.flush();
                    String stackTrace = sw.toString();
                    
                    if (stackTrace != null && !stackTrace.trim().isEmpty()) {
                        fileWriter.write(stackTrace + "\n");
                        fileWriter.flush();
                    }
                } catch (Exception e) {
                    // If printStackTrace fails, try to write at least the error message
                    try {
                        fileWriter.write("Stack trace unavailable for: " + errorMsg + "\n");
                        fileWriter.flush();
                    } catch (IOException ioException) {
                        // Ignore - can't write to log file
                    }
                }
            }
        } catch (Exception e) {
            // Silently ignore - don't let logging errors break the app
            // Only log to Android log if it's a serious issue
            try {
                Log.e(TAG, "Failed to write stack trace to file", e);
            } catch (Exception logException) {
                // Even Android Log failed - completely ignore
            }
        }
    }

    public void logDebug(String message) {
        log("DEBUG", message);
    }

    public void logInfo(String message) {
        log("INFO", message);
    }

    public void logWarn(String message) {
        log("WARN", message);
    }
    
    // Volume button specific logging methods with custom tag
    public void logVolumeInfo(String message) {
        Log.i("VolumeButton", message);
        log("INFO", "[VolumeButton] " + message);
    }
    
    public void logVolumeDebug(String message) {
        Log.d("VolumeButton", message);
        log("DEBUG", "[VolumeButton] " + message);
    }
    
    public void logVolumeError(String message) {
        Log.e("VolumeButton", message);
        log("ERROR", "[VolumeButton] " + message);
    }
    
    public void logVolumeError(String message, Throwable throwable) {
        Log.e("VolumeButton", message, throwable);
        logError("[VolumeButton] " + message, throwable);
    }
    
    public void logVolumeWarn(String message) {
        Log.w("VolumeButton", message);
        log("WARN", "[VolumeButton] " + message);
    }

    public String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "Not available";
    }

    public void clearLogs() {
        try {
            if (fileWriter != null) {
                fileWriter.close();
            }
            if (logFile != null && logFile.exists()) {
                fileWriter = new FileWriter(logFile, false); // Overwrite
                log("INFO", "Log file cleared");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to clear logs", e);
        }
    }

    public void close() {
        try {
            if (fileWriter != null) {
                fileWriter.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to close log file", e);
        }
    }
}

