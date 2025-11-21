package com.freeadbremote;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

/**
 * Number Pad Activity - Full-featured number pad (0-9)
 */
public class NumberPadActivity extends AppCompatActivity {
    private TextView connectionStatusTextView;
    private MaterialButton backButton;
    private MaterialButton number0Button, number1Button, number2Button, number3Button;
    private MaterialButton number4Button, number5Button, number6Button;
    private MaterialButton number7Button, number8Button, number9Button;
    private MaterialButton backspaceButton;
    
    private AdbConnectionManager connectionManager;
    private LogManager logManager;
    private String connectedHost;
    private int connectedPort;
    private AudioManager audioManager;
    
    // Repeat functionality for backspace
    private Handler repeatHandler;
    private Runnable repeatRunnable;
    private static final long INITIAL_REPEAT_DELAY = 300; // ms before first repeat
    private static final long REPEAT_INTERVAL = 100; // ms between repeats
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_numberpad);
        
        logManager = LogManager.getInstance(this);
        logManager.logInfo("NumberPadActivity onCreate");
        
        Intent intent = getIntent();
        connectedHost = intent.getStringExtra("host");
        connectedPort = intent.getIntExtra("port", 5555);
        
        connectionManager = AdbConnectionManager.getInstance(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        
        // Initialize repeat handler
        repeatHandler = new Handler(Looper.getMainLooper());
        
        initViews();
        setupListeners();
    }
    
    private void initViews() {
        connectionStatusTextView = findViewById(R.id.connectionStatusTextView);
        backButton = findViewById(R.id.backButton);
        number0Button = findViewById(R.id.number0Button);
        number1Button = findViewById(R.id.number1Button);
        number2Button = findViewById(R.id.number2Button);
        number3Button = findViewById(R.id.number3Button);
        number4Button = findViewById(R.id.number4Button);
        number5Button = findViewById(R.id.number5Button);
        number6Button = findViewById(R.id.number6Button);
        number7Button = findViewById(R.id.number7Button);
        number8Button = findViewById(R.id.number8Button);
        number9Button = findViewById(R.id.number9Button);
        backspaceButton = findViewById(R.id.backspaceButton);
        
        connectionStatusTextView.setText("Number Pad");
    }
    
    private void setupListeners() {
        backButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            finish();
        });
        
        number0Button.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_0");
        });
        number1Button.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_1");
        });
        number2Button.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_2");
        });
        number3Button.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_3");
        });
        number4Button.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_4");
        });
        number5Button.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_5");
        });
        number6Button.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_6");
        });
        number7Button.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_7");
        });
        number8Button.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_8");
        });
        number9Button.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_9");
        });
        
        // Setup backspace button with repeat functionality
        setupRepeatBackspaceButton(backspaceButton);
    }
    
    /**
     * Setup backspace button with repeat functionality - sends backspace repeatedly while held
     */
    private void setupRepeatBackspaceButton(MaterialButton button) {
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // Stop any existing repeat
                        stopRepeat();
                        // Provide feedback
                        provideButtonFeedback(v);
                        // Execute immediately
                        sendBackspace();
                        // Start repeat
                        startRepeat();
                        return true; // Consume the event
                        
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // Stop repeat when button is released
                        stopRepeat();
                        return true; // Consume the event
                }
                return false;
            }
        });
    }
    
    /**
     * Start repeating backspace
     */
    private void startRepeat() {
        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (repeatRunnable != null) {
                    sendBackspace();
                    // Schedule next repeat
                    repeatHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            }
        };
        // Start repeating after initial delay
        repeatHandler.postDelayed(repeatRunnable, INITIAL_REPEAT_DELAY);
    }
    
    /**
     * Stop repeating
     */
    private void stopRepeat() {
        if (repeatHandler != null && repeatRunnable != null) {
            repeatHandler.removeCallbacks(repeatRunnable);
            repeatRunnable = null;
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
    
    private void executeKeyEvent(String keyCode) {
        // Use the same keycode mapping as ControlActivity
        String keycodeValue = null;
        if (keyCode.equals("KEYCODE_0")) keycodeValue = "7";
        else if (keyCode.equals("KEYCODE_1")) keycodeValue = "8";
        else if (keyCode.equals("KEYCODE_2")) keycodeValue = "9";
        else if (keyCode.equals("KEYCODE_3")) keycodeValue = "10";
        else if (keyCode.equals("KEYCODE_4")) keycodeValue = "11";
        else if (keyCode.equals("KEYCODE_5")) keycodeValue = "12";
        else if (keyCode.equals("KEYCODE_6")) keycodeValue = "13";
        else if (keyCode.equals("KEYCODE_7")) keycodeValue = "14";
        else if (keyCode.equals("KEYCODE_8")) keycodeValue = "15";
        else if (keyCode.equals("KEYCODE_9")) keycodeValue = "16";
        
        if (keycodeValue == null) {
            keycodeValue = keyCode.replace("KEYCODE_", "");
            logManager.logWarn("Keycode not found, using: " + keycodeValue);
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
    private void sendBackspace() {
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
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stop any ongoing repeat
        stopRepeat();
        if (repeatHandler != null) {
            repeatHandler.removeCallbacksAndMessages(null);
        }
    }
}