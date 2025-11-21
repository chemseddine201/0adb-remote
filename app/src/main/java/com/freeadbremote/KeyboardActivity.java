package com.freeadbremote;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

/**
 * Keyboard Activity - Full QWERTY keyboard for text input
 */
public class KeyboardActivity extends AppCompatActivity {
    private TextView connectionStatusTextView;
    private MaterialButton backButton;
    private EditText textInput;
    
    private AdbConnectionManager connectionManager;
    private LogManager logManager;
    private String connectedHost;
    private int connectedPort;
    private AudioManager audioManager;
    
    // Repeat functionality for backspace
    private Handler backspaceRepeatHandler;
    private Runnable backspaceRepeatRunnable;
    private static final long INITIAL_REPEAT_DELAY = 300; // ms before first repeat
    private static final long REPEAT_INTERVAL = 100; // ms between repeats
    
    // Keyboard keep-alive handler
    private Handler keyboardKeepAliveHandler;
    private Runnable keyboardKeepAliveRunnable;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_keyboard);
        
        logManager = LogManager.getInstance(this);
        logManager.logInfo("KeyboardActivity onCreate");
        
        Intent intent = getIntent();
        connectedHost = intent.getStringExtra("host");
        connectedPort = intent.getIntExtra("port", 5555);
        
        connectionManager = AdbConnectionManager.getInstance(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        
        // Initialize backspace repeat handler
        backspaceRepeatHandler = new Handler(Looper.getMainLooper());
        
        // Initialize keyboard keep-alive handler
        keyboardKeepAliveHandler = new Handler(Looper.getMainLooper());
        keyboardKeepAliveRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed() && textInput != null) {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        if (!textInput.hasFocus()) {
                            textInput.requestFocus();
                        }
                        // Keep keyboard open
                        imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        // Schedule next check
                        keyboardKeepAliveHandler.postDelayed(this, 2000);
                    }
                }
            }
        };
        
        initViews();
        setupListeners();
        
        // Show keyboard immediately
        showKeyboard();
    }
    
    private void initViews() {
        connectionStatusTextView = findViewById(R.id.connectionStatusTextView);
        backButton = findViewById(R.id.backButton);
        textInput = findViewById(R.id.textInput);
        
        connectionStatusTextView.setText("Keyboard");
    }
    
    private void setupListeners() {
        backButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            // Don't hide keyboard, just finish activity
            finish();
        });
        
        // Handle all key events directly - send immediately to device
        textInput.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DEL) {
                        // Handle backspace with repeat
                        stopBackspaceRepeat();
                        sendBackspaceToDevice();
                        startBackspaceRepeat();
                        // Clear the text input to prevent it from showing
                        textInput.setText("");
                        return true; // Consume the event
                    } else if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                        // Send Enter key
                        sendKeyEvent("KEYCODE_ENTER");
                        textInput.setText(""); // Clear after sending
                        return true;
                    } else {
                        // For other keys, get the character and send it
                        // The character will be handled by onTextChanged
                        return false; // Let the default handler process it
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (keyCode == KeyEvent.KEYCODE_DEL) {
                        stopBackspaceRepeat();
                        return true;
                    }
                }
                return false;
            }
        });
        
        // Handle text input - send each character immediately
        textInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Not needed
            }
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Send new characters immediately
                if (count > 0 && start + count <= s.length()) {
                    String newChars = s.subSequence(start, start + count).toString();
                    // Send each character immediately
                    for (int i = 0; i < newChars.length(); i++) {
                        char c = newChars.charAt(i);
                        sendCharacter(c);
                    }
                    // Clear the text input after sending
                    textInput.setText("");
                }
            }
            
            @Override
            public void afterTextChanged(Editable s) {
                // Not needed
            }
        });
    }
    
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Handle key events at activity level as backup
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                stopBackspaceRepeat();
                sendBackspaceToDevice();
                startBackspaceRepeat();
                return true;
            } else if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                sendKeyEvent("KEYCODE_ENTER");
                return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                stopBackspaceRepeat();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    
    /**
     * Show system keyboard - keeps it always open
     */
    private void showKeyboard() {
        if (textInput == null) {
            return;
        }
        
        // Request focus first
        textInput.requestFocus();
        textInput.setFocusable(true);
        textInput.setFocusableInTouchMode(true);
        
        // Show keyboard with multiple attempts to ensure it shows
        Handler handler = new Handler(Looper.getMainLooper());
        
        // First attempt - immediate
        handler.post(() -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && textInput != null && !isFinishing() && !isDestroyed()) {
                textInput.requestFocus();
                imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
        
        // Second attempt - after short delay
        handler.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && textInput != null && !isFinishing() && !isDestroyed()) {
                if (!textInput.hasFocus()) {
                    textInput.requestFocus();
                }
                imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
            }
        }, 300);
        
        // Third attempt - after longer delay
        handler.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && textInput != null && !isFinishing() && !isDestroyed()) {
                if (!textInput.hasFocus()) {
                    textInput.requestFocus();
                }
                imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0);
            }
        }, 500);
        
        // Start periodic check to ensure keyboard stays open (every 2 seconds)
        if (keyboardKeepAliveHandler != null && keyboardKeepAliveRunnable != null) {
            keyboardKeepAliveHandler.removeCallbacks(keyboardKeepAliveRunnable);
            keyboardKeepAliveHandler.postDelayed(keyboardKeepAliveRunnable, 2000);
        }
    }
    
    /**
     * Hide system keyboard
     */
    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(textInput.getWindowToken(), 0);
        }
    }
    
    private void provideButtonFeedback(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        if (audioManager != null) {
            try {
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
            } catch (Exception e) {
                // Ignore
            }
        }
    }
    
    /**
     * Send a single character to the device
     */
    private void sendCharacter(char c) {
        // Escape special characters for ADB input text command
        String charStr = String.valueOf(c);
        if (charStr.equals(" ")) {
            charStr = "%s";
        } else if (charStr.equals("&")) {
            charStr = "\\&";
        } else if (charStr.equals("|")) {
            charStr = "\\|";
        }
        
        String command = "input text \"" + charStr + "\" \n";
        
        connectionManager.executeCommand(command, new AdbServerManager.CommandCallback() {
            @Override
            public void onOutput(String output) {}
            
            @Override
            public void onError(String error) {
                if (error != null && (error.contains("Not connected") || error.contains("connection"))) {
                    logManager.logError("Character send failed", new Exception(error));
                }
            }
            
            @Override
            public void onComplete(int exitCode) {}
        });
    }
    
    /**
     * Send key event to device
     */
    private void sendKeyEvent(String keyCode) {
        // Map common keycodes
        String keycodeValue = null;
        if (keyCode.equals("KEYCODE_ENTER")) {
            keycodeValue = "66"; // KEYCODE_ENTER
        }
        
        if (keycodeValue == null) {
            keycodeValue = keyCode.replace("KEYCODE_", "");
        }
        
        String command = "input keyevent " + keycodeValue + " \n";
        
        connectionManager.executeCommand(command, new AdbServerManager.CommandCallback() {
            @Override
            public void onOutput(String output) {}
            
            @Override
            public void onError(String error) {
                if (error != null && (error.contains("Not connected") || error.contains("connection"))) {
                    logManager.logError("Key event failed: " + keyCode, new Exception(error));
                }
            }
            
            @Override
            public void onComplete(int exitCode) {}
        });
    }
    
    /**
     * Send backspace keyevent to remote device to delete characters
     */
    private void sendBackspaceToDevice() {
        String command = "input keyevent 67 \n"; // KEYCODE_DEL (backspace)
        
        connectionManager.executeCommand(command, new AdbServerManager.CommandCallback() {
            @Override
            public void onOutput(String output) {}
            
            @Override
            public void onError(String error) {
                if (error != null && (error.contains("Not connected") || error.contains("connection"))) {
                    logManager.logError("Backspace failed", new Exception(error));
                }
            }
            
            @Override
            public void onComplete(int exitCode) {}
        });
    }
    
    /**
     * Start repeating backspace
     */
    private void startBackspaceRepeat() {
        backspaceRepeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (backspaceRepeatRunnable != null) {
                    sendBackspaceToDevice();
                    // Schedule next repeat
                    backspaceRepeatHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            }
        };
        // Start repeating after initial delay
        backspaceRepeatHandler.postDelayed(backspaceRepeatRunnable, INITIAL_REPEAT_DELAY);
    }
    
    /**
     * Stop repeating backspace
     */
    private void stopBackspaceRepeat() {
        if (backspaceRepeatHandler != null && backspaceRepeatRunnable != null) {
            backspaceRepeatHandler.removeCallbacks(backspaceRepeatRunnable);
            backspaceRepeatRunnable = null;
        }
    }
    
    @Override
    public void onBackPressed() {
        // Override back button to prevent keyboard from closing
        // Re-show keyboard immediately after back button press
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            // Re-show keyboard to keep it open
            showKeyboard();
        });
        // Don't finish - keep activity and keyboard open
        // User can use the back button in the UI to exit
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // Don't hide keyboard on pause - keep it open
        // Only hide when activity is actually destroyed
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Ensure keyboard is shown when activity resumes
        showKeyboard();
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Show keyboard when window gains focus
            showKeyboard();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stop keyboard keep-alive
        if (keyboardKeepAliveHandler != null && keyboardKeepAliveRunnable != null) {
            keyboardKeepAliveHandler.removeCallbacks(keyboardKeepAliveRunnable);
        }
        // Only hide keyboard when activity is actually being destroyed
        hideKeyboard();
        // Stop any ongoing backspace repeat
        stopBackspaceRepeat();
        if (backspaceRepeatHandler != null) {
            backspaceRepeatHandler.removeCallbacksAndMessages(null);
        }
    }
}