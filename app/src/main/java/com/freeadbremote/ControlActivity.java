package com.freeadbremote;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.freeadbremote.service.AdbConnectionService;
import java.util.HashMap;
import java.util.Map;

/**
 * Control Activity - Based on Rbox command implementation
 * Uses exact same commands and keycode mappings as Rbox
 */
public class ControlActivity extends AppCompatActivity {
    // Debug tag for volume buttons
    private static final String VOLUME_DEBUG_TAG = "VolumeButton";
    
    private View connectionStatusIndicator;
    private TextView connectionStatusText;
    private com.google.android.material.button.MaterialButton infoButton;
    private com.google.android.material.button.MaterialButton restartButton;
    private com.google.android.material.button.MaterialButton homeButton;
    private com.google.android.material.button.MaterialButton backButton;
    private com.google.android.material.button.MaterialButton powerButton;
    private com.google.android.material.button.MaterialButton numberPadButton;
    private com.google.android.material.button.MaterialButton keyboardButton;
    private com.google.android.material.button.MaterialButton appsManagerButton;
    private com.google.android.material.button.MaterialButton channelUpButton;
    private com.google.android.material.button.MaterialButton channelDownButton;
    private com.google.android.material.button.MaterialButton upButton;
    private com.google.android.material.button.MaterialButton downButton;
    private com.google.android.material.button.MaterialButton leftButton;
    private com.google.android.material.button.MaterialButton rightButton;
    private com.google.android.material.button.MaterialButton okButton;
    private com.google.android.material.button.MaterialButton volumeUpButton;
    private com.google.android.material.button.MaterialButton volumeDownButton;
    private com.google.android.material.button.MaterialButton muteButton;
    private com.google.android.material.button.MaterialButton mediaPlayPauseButton;
    private com.google.android.material.button.MaterialButton mediaPreviousButton;
    private com.google.android.material.button.MaterialButton mediaNextButton;
    private com.google.android.material.button.MaterialButton mediaStopButton;
    private com.google.android.material.button.MaterialButton mediaRewindButton;
    private com.google.android.material.button.MaterialButton mediaFastForwardButton;
    
    private AdbConnectionManager connectionManager;
    private LogManager logManager;
    private String connectedHost;
    private int connectedPort;
    private boolean isExecuting = false;
    private AudioManager audioManager;
    private String connectionState = "disconnected"; // disconnected, connecting, connected
    
    // Repeat functionality
    private Handler repeatHandler;
    private Runnable repeatRunnable;
    private String currentRepeatKeyCode = null;
    
    // Connection status update handler - uses static volatile variables from AdbConnectionManager
    private Handler connectionStatusHandler;
    private Runnable connectionStatusUpdateRunnable;
    private static final long CONNECTION_STATUS_UPDATE_INTERVAL = 500; // Update every 500ms (faster response)
    private static final long INITIAL_REPEAT_DELAY = 300; // ms before first repeat
    private static final long REPEAT_INTERVAL = 100; // ms between repeats
    
    // Keycode mappings from Rbox RemoteKeyCode.java - EXACT VALUES
    // Verified from Rbox/sources/com/allrcs/xfinitybox/core/control/atv/RemoteKeyCode.java
    private static final Map<String, String> KEYCODE_MAP = new HashMap<>();
    static {
        // System keys
        KEYCODE_MAP.put("KEYCODE_UNKNOWN", "0");
        KEYCODE_MAP.put("KEYCODE_SOFT_LEFT", "1");
        KEYCODE_MAP.put("KEYCODE_SOFT_RIGHT", "2");
        KEYCODE_MAP.put("KEYCODE_HOME", "3");
        KEYCODE_MAP.put("KEYCODE_BACK", "4");
        KEYCODE_MAP.put("KEYCODE_CALL", "5");
        KEYCODE_MAP.put("KEYCODE_ENDCALL", "6");
        KEYCODE_MAP.put("KEYCODE_POWER", "26");
        KEYCODE_MAP.put("KEYCODE_CAMERA", "27");
        KEYCODE_MAP.put("KEYCODE_CLEAR", "28");
        KEYCODE_MAP.put("KEYCODE_MENU", "82");
        KEYCODE_MAP.put("KEYCODE_NOTIFICATION", "83");
        KEYCODE_MAP.put("KEYCODE_SEARCH", "84");
        KEYCODE_MAP.put("KEYCODE_APP_SWITCH", "187");
        KEYCODE_MAP.put("KEYCODE_ASSIST", "219");
        KEYCODE_MAP.put("KEYCODE_VOICE_ASSIST", "231");
        KEYCODE_MAP.put("KEYCODE_HELP", "259");
        
        // Number keys
        KEYCODE_MAP.put("KEYCODE_0", "7");
        KEYCODE_MAP.put("KEYCODE_1", "8");
        KEYCODE_MAP.put("KEYCODE_2", "9");
        KEYCODE_MAP.put("KEYCODE_3", "10");
        KEYCODE_MAP.put("KEYCODE_4", "11");
        KEYCODE_MAP.put("KEYCODE_5", "12");
        KEYCODE_MAP.put("KEYCODE_6", "13");
        KEYCODE_MAP.put("KEYCODE_7", "14");
        KEYCODE_MAP.put("KEYCODE_8", "15");
        KEYCODE_MAP.put("KEYCODE_9", "16");
        KEYCODE_MAP.put("KEYCODE_STAR", "17");
        KEYCODE_MAP.put("KEYCODE_POUND", "18");
        KEYCODE_MAP.put("KEYCODE_11", "227");
        KEYCODE_MAP.put("KEYCODE_12", "228");
        
        // D-Pad keys
        KEYCODE_MAP.put("KEYCODE_DPAD_UP", "19");
        KEYCODE_MAP.put("KEYCODE_DPAD_DOWN", "20");
        KEYCODE_MAP.put("KEYCODE_DPAD_LEFT", "21");
        KEYCODE_MAP.put("KEYCODE_DPAD_RIGHT", "22");
        KEYCODE_MAP.put("KEYCODE_DPAD_CENTER", "23");
        KEYCODE_MAP.put("KEYCODE_DPAD_UP_LEFT", "268");
        KEYCODE_MAP.put("KEYCODE_DPAD_DOWN_LEFT", "269");
        KEYCODE_MAP.put("KEYCODE_DPAD_UP_RIGHT", "270");
        KEYCODE_MAP.put("KEYCODE_DPAD_DOWN_RIGHT", "271");
        
        // Volume keys - Multiple values found in Rbox for different TV brands
        // Standard Android: 24/25 (most common)
        // Samsung TV: 115/114
        // LG TV: 33/32
        // Try standard first, then fallback to alternatives if needed
        KEYCODE_MAP.put("KEYCODE_VOLUME_UP", "24");      // Standard Android (primary)
        KEYCODE_MAP.put("KEYCODE_VOLUME_UP_SAMSUNG", "115");  // Samsung TV (fallback)
        KEYCODE_MAP.put("KEYCODE_VOLUME_UP_LG", "33");   // LG TV (fallback)
        KEYCODE_MAP.put("KEYCODE_VOLUME_DOWN", "25");    // Standard Android (primary)
        KEYCODE_MAP.put("KEYCODE_VOLUME_DOWN_SAMSUNG", "114"); // Samsung TV (fallback)
        KEYCODE_MAP.put("KEYCODE_VOLUME_DOWN_LG", "32"); // LG TV (fallback)
        KEYCODE_MAP.put("KEYCODE_VOLUME_MUTE", "164");
        
        // Letter keys
        KEYCODE_MAP.put("KEYCODE_A", "29");
        KEYCODE_MAP.put("KEYCODE_B", "30");
        KEYCODE_MAP.put("KEYCODE_C", "31");
        KEYCODE_MAP.put("KEYCODE_D", "32");
        KEYCODE_MAP.put("KEYCODE_E", "33");
        KEYCODE_MAP.put("KEYCODE_F", "34");
        KEYCODE_MAP.put("KEYCODE_G", "35");
        KEYCODE_MAP.put("KEYCODE_H", "36");
        KEYCODE_MAP.put("KEYCODE_I", "37");
        KEYCODE_MAP.put("KEYCODE_J", "38");
        KEYCODE_MAP.put("KEYCODE_K", "39");
        KEYCODE_MAP.put("KEYCODE_L", "40");
        KEYCODE_MAP.put("KEYCODE_M", "41");
        KEYCODE_MAP.put("KEYCODE_N", "42");
        KEYCODE_MAP.put("KEYCODE_O", "43");
        KEYCODE_MAP.put("KEYCODE_P", "44");
        KEYCODE_MAP.put("KEYCODE_Q", "45");
        KEYCODE_MAP.put("KEYCODE_R", "46");
        KEYCODE_MAP.put("KEYCODE_S", "47");
        KEYCODE_MAP.put("KEYCODE_T", "48");
        KEYCODE_MAP.put("KEYCODE_U", "49");
        KEYCODE_MAP.put("KEYCODE_V", "50");
        KEYCODE_MAP.put("KEYCODE_W", "51");
        KEYCODE_MAP.put("KEYCODE_X", "52");
        KEYCODE_MAP.put("KEYCODE_Y", "53");
        KEYCODE_MAP.put("KEYCODE_Z", "54");
        
        // Special character keys
        KEYCODE_MAP.put("KEYCODE_COMMA", "55");
        KEYCODE_MAP.put("KEYCODE_PERIOD", "56");
        KEYCODE_MAP.put("KEYCODE_ALT_LEFT", "57");
        KEYCODE_MAP.put("KEYCODE_ALT_RIGHT", "58");
        KEYCODE_MAP.put("KEYCODE_SHIFT_LEFT", "59");
        KEYCODE_MAP.put("KEYCODE_SHIFT_RIGHT", "60");
        KEYCODE_MAP.put("KEYCODE_TAB", "61");
        KEYCODE_MAP.put("KEYCODE_SPACE", "62");
        KEYCODE_MAP.put("KEYCODE_SYM", "63");
        KEYCODE_MAP.put("KEYCODE_EXPLORER", "64");
        KEYCODE_MAP.put("KEYCODE_ENVELOPE", "65");
        KEYCODE_MAP.put("KEYCODE_ENTER", "66");
        KEYCODE_MAP.put("KEYCODE_DEL", "67");
        KEYCODE_MAP.put("KEYCODE_GRAVE", "68");
        KEYCODE_MAP.put("KEYCODE_MINUS", "69");
        KEYCODE_MAP.put("KEYCODE_EQUALS", "70");
        KEYCODE_MAP.put("KEYCODE_LEFT_BRACKET", "71");
        KEYCODE_MAP.put("KEYCODE_RIGHT_BRACKET", "72");
        KEYCODE_MAP.put("KEYCODE_BACKSLASH", "73");
        KEYCODE_MAP.put("KEYCODE_SEMICOLON", "74");
        KEYCODE_MAP.put("KEYCODE_APOSTROPHE", "75");
        KEYCODE_MAP.put("KEYCODE_SLASH", "76");
        KEYCODE_MAP.put("KEYCODE_AT", "77");
        KEYCODE_MAP.put("KEYCODE_NUM", "78");
        KEYCODE_MAP.put("KEYCODE_HEADSETHOOK", "79");
        KEYCODE_MAP.put("KEYCODE_FOCUS", "80");
        KEYCODE_MAP.put("KEYCODE_PLUS", "81");
        KEYCODE_MAP.put("KEYCODE_PAGE_UP", "92");
        KEYCODE_MAP.put("KEYCODE_PAGE_DOWN", "93");
        KEYCODE_MAP.put("KEYCODE_PICTSYMBOLS", "94");
        KEYCODE_MAP.put("KEYCODE_SWITCH_CHARSET", "95");
        KEYCODE_MAP.put("KEYCODE_ESCAPE", "111");
        KEYCODE_MAP.put("KEYCODE_FORWARD", "125");
        KEYCODE_MAP.put("KEYCODE_NUM_LOCK", "143");
        KEYCODE_MAP.put("KEYCODE_LANGUAGE_SWITCH", "204");
        KEYCODE_MAP.put("KEYCODE_MANNER_MODE", "205");
        KEYCODE_MAP.put("KEYCODE_3D_MODE", "206");
        KEYCODE_MAP.put("KEYCODE_ZENKAKU_HANKAKU", "211");
        KEYCODE_MAP.put("KEYCODE_EISU", "212");
        KEYCODE_MAP.put("KEYCODE_MUHENKAN", "213");
        KEYCODE_MAP.put("KEYCODE_HENKAN", "214");
        KEYCODE_MAP.put("KEYCODE_KANA", "215");
        KEYCODE_MAP.put("KEYCODE_CUT", "277");
        KEYCODE_MAP.put("KEYCODE_COPY", "278");
        KEYCODE_MAP.put("KEYCODE_PASTE", "279");
        
        // Media keys
        KEYCODE_MAP.put("KEYCODE_MEDIA_PLAY_PAUSE", "85");
        KEYCODE_MAP.put("KEYCODE_MEDIA_STOP", "86");
        KEYCODE_MAP.put("KEYCODE_MEDIA_NEXT", "87");
        KEYCODE_MAP.put("KEYCODE_MEDIA_PREVIOUS", "88");
        KEYCODE_MAP.put("KEYCODE_MEDIA_REWIND", "89");
        KEYCODE_MAP.put("KEYCODE_MEDIA_FAST_FORWARD", "90");
        KEYCODE_MAP.put("KEYCODE_MEDIA_PLAY", "126");
        KEYCODE_MAP.put("KEYCODE_MEDIA_PAUSE", "127");
        KEYCODE_MAP.put("KEYCODE_MEDIA_CLOSE", "128");
        KEYCODE_MAP.put("KEYCODE_MEDIA_EJECT", "129");
        KEYCODE_MAP.put("KEYCODE_MEDIA_RECORD", "130");
        KEYCODE_MAP.put("KEYCODE_MEDIA_AUDIO_TRACK", "222");
        KEYCODE_MAP.put("KEYCODE_MEDIA_TOP_MENU", "226");
        KEYCODE_MAP.put("KEYCODE_MEDIA_SKIP_FORWARD", "272");
        KEYCODE_MAP.put("KEYCODE_MEDIA_SKIP_BACKWARD", "273");
        KEYCODE_MAP.put("KEYCODE_MEDIA_STEP_FORWARD", "274");
        KEYCODE_MAP.put("KEYCODE_MEDIA_STEP_BACKWARD", "275");
        
        // Gamepad/Controller buttons
        KEYCODE_MAP.put("KEYCODE_BUTTON_A", "96");
        KEYCODE_MAP.put("KEYCODE_BUTTON_B", "97");
        KEYCODE_MAP.put("KEYCODE_BUTTON_C", "98");
        KEYCODE_MAP.put("KEYCODE_BUTTON_X", "99");
        KEYCODE_MAP.put("KEYCODE_BUTTON_Y", "100");
        KEYCODE_MAP.put("KEYCODE_BUTTON_Z", "101");
        KEYCODE_MAP.put("KEYCODE_BUTTON_L1", "102");
        KEYCODE_MAP.put("KEYCODE_BUTTON_R1", "103");
        KEYCODE_MAP.put("KEYCODE_BUTTON_L2", "104");
        KEYCODE_MAP.put("KEYCODE_BUTTON_R2", "105");
        KEYCODE_MAP.put("KEYCODE_BUTTON_THUMBL", "106");
        KEYCODE_MAP.put("KEYCODE_BUTTON_THUMBR", "107");
        KEYCODE_MAP.put("KEYCODE_BUTTON_START", "108");
        KEYCODE_MAP.put("KEYCODE_BUTTON_SELECT", "109");
        KEYCODE_MAP.put("KEYCODE_BUTTON_MODE", "110");
        KEYCODE_MAP.put("KEYCODE_BUTTON_1", "188");
        KEYCODE_MAP.put("KEYCODE_BUTTON_2", "189");
        KEYCODE_MAP.put("KEYCODE_BUTTON_3", "190");
        KEYCODE_MAP.put("KEYCODE_BUTTON_4", "191");
        KEYCODE_MAP.put("KEYCODE_BUTTON_5", "192");
        KEYCODE_MAP.put("KEYCODE_BUTTON_6", "193");
        KEYCODE_MAP.put("KEYCODE_BUTTON_7", "194");
        KEYCODE_MAP.put("KEYCODE_BUTTON_8", "195");
        KEYCODE_MAP.put("KEYCODE_BUTTON_9", "196");
        KEYCODE_MAP.put("KEYCODE_BUTTON_10", "197");
        KEYCODE_MAP.put("KEYCODE_BUTTON_11", "198");
        KEYCODE_MAP.put("KEYCODE_BUTTON_12", "199");
        KEYCODE_MAP.put("KEYCODE_BUTTON_13", "200");
        KEYCODE_MAP.put("KEYCODE_BUTTON_14", "201");
        KEYCODE_MAP.put("KEYCODE_BUTTON_15", "202");
        KEYCODE_MAP.put("KEYCODE_BUTTON_16", "203");
        
        // Function keys
        KEYCODE_MAP.put("KEYCODE_F1", "131");
        KEYCODE_MAP.put("KEYCODE_F2", "132");
        KEYCODE_MAP.put("KEYCODE_F3", "133");
        KEYCODE_MAP.put("KEYCODE_F4", "134");
        KEYCODE_MAP.put("KEYCODE_F5", "135");
        KEYCODE_MAP.put("KEYCODE_F6", "136");
        KEYCODE_MAP.put("KEYCODE_F7", "137");
        KEYCODE_MAP.put("KEYCODE_F8", "138");
        KEYCODE_MAP.put("KEYCODE_F9", "139");
        KEYCODE_MAP.put("KEYCODE_F10", "140");
        KEYCODE_MAP.put("KEYCODE_F11", "141");
        KEYCODE_MAP.put("KEYCODE_F12", "142");
        
        // Numpad keys
        KEYCODE_MAP.put("KEYCODE_NUMPAD_0", "144");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_1", "145");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_2", "146");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_3", "147");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_4", "148");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_5", "149");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_6", "150");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_7", "151");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_8", "152");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_9", "153");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_DIVIDE", "154");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_MULTIPLY", "155");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_SUBTRACT", "156");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_ADD", "157");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_DOT", "158");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_COMMA", "159");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_ENTER", "160");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_EQUALS", "161");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_LEFT_PAREN", "162");
        KEYCODE_MAP.put("KEYCODE_NUMPAD_RIGHT_PAREN", "163");
        
        // TV/Remote keys
        KEYCODE_MAP.put("KEYCODE_INFO", "165");
        KEYCODE_MAP.put("KEYCODE_CHANNEL_UP", "166");
        KEYCODE_MAP.put("KEYCODE_CHANNEL_DOWN", "167");
        KEYCODE_MAP.put("KEYCODE_ZOOM_IN", "168");
        KEYCODE_MAP.put("KEYCODE_ZOOM_OUT", "169");
        KEYCODE_MAP.put("KEYCODE_TV", "170");
        KEYCODE_MAP.put("KEYCODE_WINDOW", "171");
        KEYCODE_MAP.put("KEYCODE_GUIDE", "172");
        KEYCODE_MAP.put("KEYCODE_DVR", "173");
        KEYCODE_MAP.put("KEYCODE_BOOKMARK", "174");
        KEYCODE_MAP.put("KEYCODE_CAPTIONS", "175");
        KEYCODE_MAP.put("KEYCODE_SETTINGS", "176");
        KEYCODE_MAP.put("KEYCODE_TV_POWER", "177");
        KEYCODE_MAP.put("KEYCODE_TV_INPUT", "178");
        KEYCODE_MAP.put("KEYCODE_STB_POWER", "179");
        KEYCODE_MAP.put("KEYCODE_STB_INPUT", "180");
        KEYCODE_MAP.put("KEYCODE_AVR_POWER", "181");
        KEYCODE_MAP.put("KEYCODE_AVR_INPUT", "182");
        KEYCODE_MAP.put("KEYCODE_PROG_RED", "183");
        KEYCODE_MAP.put("KEYCODE_PROG_GREEN", "184");
        KEYCODE_MAP.put("KEYCODE_PROG_YELLOW", "185");
        KEYCODE_MAP.put("KEYCODE_PROG_BLUE", "186");
        KEYCODE_MAP.put("KEYCODE_LAST_CHANNEL", "229");
        KEYCODE_MAP.put("KEYCODE_TV_DATA_SERVICE", "230");
        KEYCODE_MAP.put("KEYCODE_TV_RADIO_SERVICE", "232");
        KEYCODE_MAP.put("KEYCODE_TV_TELETEXT", "233");
        KEYCODE_MAP.put("KEYCODE_TV_NUMBER_ENTRY", "234");
        KEYCODE_MAP.put("KEYCODE_TV_TERRESTRIAL_ANALOG", "235");
        KEYCODE_MAP.put("KEYCODE_TV_TERRESTRIAL_DIGITAL", "236");
        KEYCODE_MAP.put("KEYCODE_TV_SATELLITE", "237");
        KEYCODE_MAP.put("KEYCODE_TV_SATELLITE_BS", "238");
        KEYCODE_MAP.put("KEYCODE_TV_SATELLITE_CS", "239");
        KEYCODE_MAP.put("KEYCODE_TV_SATELLITE_SERVICE", "240");
        KEYCODE_MAP.put("KEYCODE_TV_NETWORK", "241");
        KEYCODE_MAP.put("KEYCODE_TV_ANTENNA_CABLE", "242");
        KEYCODE_MAP.put("KEYCODE_TV_INPUT_HDMI_1", "243");
        KEYCODE_MAP.put("KEYCODE_TV_INPUT_HDMI_2", "244");
        KEYCODE_MAP.put("KEYCODE_TV_INPUT_HDMI_3", "245");
        KEYCODE_MAP.put("KEYCODE_TV_INPUT_HDMI_4", "246");
        KEYCODE_MAP.put("KEYCODE_TV_INPUT_COMPOSITE_1", "247");
        KEYCODE_MAP.put("KEYCODE_TV_INPUT_COMPOSITE_2", "248");
        KEYCODE_MAP.put("KEYCODE_TV_INPUT_COMPONENT_1", "249");
        KEYCODE_MAP.put("KEYCODE_TV_INPUT_COMPONENT_2", "250");
        KEYCODE_MAP.put("KEYCODE_TV_INPUT_VGA_1", "251");
        KEYCODE_MAP.put("KEYCODE_TV_AUDIO_DESCRIPTION", "252");
        KEYCODE_MAP.put("KEYCODE_TV_AUDIO_DESCRIPTION_MIX_UP", "253");
        KEYCODE_MAP.put("KEYCODE_TV_AUDIO_DESCRIPTION_MIX_DOWN", "254");
        KEYCODE_MAP.put("KEYCODE_TV_ZOOM_MODE", "255");
        KEYCODE_MAP.put("KEYCODE_TV_CONTENTS_MENU", "256");
        KEYCODE_MAP.put("KEYCODE_TV_MEDIA_CONTEXT_MENU", "257");
        KEYCODE_MAP.put("KEYCODE_TV_TIMER_PROGRAMMING", "258");
        
        // Application shortcuts
        KEYCODE_MAP.put("KEYCODE_CONTACTS", "207");
        KEYCODE_MAP.put("KEYCODE_CALENDAR", "208");
        KEYCODE_MAP.put("KEYCODE_MUSIC", "209");
        KEYCODE_MAP.put("KEYCODE_CALCULATOR", "210");
        
        // System navigation
        KEYCODE_MAP.put("KEYCODE_SYSTEM_NAVIGATION_UP", "280");
        KEYCODE_MAP.put("KEYCODE_SYSTEM_NAVIGATION_DOWN", "281");
        KEYCODE_MAP.put("KEYCODE_SYSTEM_NAVIGATION_LEFT", "282");
        KEYCODE_MAP.put("KEYCODE_SYSTEM_NAVIGATION_RIGHT", "283");
        KEYCODE_MAP.put("KEYCODE_NAVIGATE_PREVIOUS", "260");
        KEYCODE_MAP.put("KEYCODE_NAVIGATE_NEXT", "261");
        KEYCODE_MAP.put("KEYCODE_NAVIGATE_IN", "262");
        KEYCODE_MAP.put("KEYCODE_NAVIGATE_OUT", "263");
        
        // Stem keys
        KEYCODE_MAP.put("KEYCODE_STEM_PRIMARY", "264");
        KEYCODE_MAP.put("KEYCODE_STEM_1", "265");
        KEYCODE_MAP.put("KEYCODE_STEM_2", "266");
        KEYCODE_MAP.put("KEYCODE_STEM_3", "267");
        
        // Power and sleep
        KEYCODE_MAP.put("KEYCODE_SLEEP", "223");
        KEYCODE_MAP.put("KEYCODE_WAKEUP", "224");
        KEYCODE_MAP.put("KEYCODE_SOFT_SLEEP", "276");
        
        // Brightness
        KEYCODE_MAP.put("KEYCODE_BRIGHTNESS_DOWN", "220");
        KEYCODE_MAP.put("KEYCODE_BRIGHTNESS_UP", "221");
        
        // Pairing
        KEYCODE_MAP.put("KEYCODE_PAIRING", "225");
        
        // Amazon Fire TV specific keys
        KEYCODE_MAP.put("KEYCODE_AZM_NETFLIX", "290");
        KEYCODE_MAP.put("KEYCODE_AZM_PRIME", "291");
        KEYCODE_MAP.put("KEYCODE_AZM_DIRECTTV", "292");
        KEYCODE_MAP.put("KEYCODE_AZM_PEACOCK", "296");
        KEYCODE_MAP.put("KEYCODE_AZM_GUIDE", "297");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);
        
        logManager = LogManager.getInstance(this);
        logManager.logInfo("ControlActivity onCreate");
        
        // CRITICAL: Initialize ADB keys on startup to ensure they exist
        // This ensures persistent keys are available for one-time trust
        AdbKeyManager keyManager = new AdbKeyManager(this);
        if (keyManager.ensureKeysExist()) {
            String fingerprint = keyManager.getKeyFingerprint();
            logManager.logInfo("ADB Key Fingerprint: " + fingerprint);
            logManager.logInfo("CRITICAL: This fingerprint MUST be identical on every app launch");
        } else {
            logManager.logError("Failed to initialize ADB keys!", new Exception("Key initialization failed"));
        }
        
        Intent intent = getIntent();
        connectedHost = intent.getStringExtra("host");
        connectedPort = intent.getIntExtra("port", 5555);
        
        if (connectedHost == null) {
            // Try to get from static variable first
            connectedHost = AdbConnectionManager.connectedHost;
            if (connectedHost == null) {
                connectedHost = "192.168.1.128";
            }
        }
        
        // Use shared connection manager (same instance as Service)
        connectionManager = AdbConnectionManager.getInstance(this);
        
        // Sync local variables with static variables
        if (AdbConnectionManager.connectedHost != null) {
            connectedHost = AdbConnectionManager.connectedHost;
            connectedPort = AdbConnectionManager.connectedPort;
        }
        
        // Initialize repeat handler
        repeatHandler = new Handler(Looper.getMainLooper());
        
        // Initialize connection status update handler
        connectionStatusHandler = new Handler(Looper.getMainLooper());
        connectionStatusUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateConnectionStatus();
                // Schedule next update
                connectionStatusHandler.postDelayed(this, CONNECTION_STATUS_UPDATE_INTERVAL);
            }
        };
        
        initViews();
        setupListeners();
        updateUI();
        
        // Start periodic connection status updates
        startConnectionStatusUpdates();
    }
    
    private void initViews() {
        connectionStatusIndicator = findViewById(R.id.connectionStatusIndicator);
        connectionStatusText = findViewById(R.id.connectionStatusText);
        infoButton = findViewById(R.id.infoButton);
        restartButton = findViewById(R.id.restartButton);
        homeButton = findViewById(R.id.homeButton);
        backButton = findViewById(R.id.backButton);
        powerButton = findViewById(R.id.powerButton);
        numberPadButton = findViewById(R.id.numberPadButton);
        keyboardButton = findViewById(R.id.keyboardButton);
        appsManagerButton = findViewById(R.id.appsManagerButton);
        channelUpButton = findViewById(R.id.channelUpButton);
        channelDownButton = findViewById(R.id.channelDownButton);
        upButton = findViewById(R.id.upButton);
        downButton = findViewById(R.id.downButton);
        leftButton = findViewById(R.id.leftButton);
        rightButton = findViewById(R.id.rightButton);
        okButton = findViewById(R.id.okButton);
        volumeUpButton = findViewById(R.id.volumeUpButton);
        volumeDownButton = findViewById(R.id.volumeDownButton);
        muteButton = findViewById(R.id.muteButton);
        mediaPlayPauseButton = findViewById(R.id.mediaPlayPauseButton);
        mediaPreviousButton = findViewById(R.id.mediaPreviousButton);
        mediaNextButton = findViewById(R.id.mediaNextButton);
        mediaStopButton = findViewById(R.id.mediaStopButton);
        mediaRewindButton = findViewById(R.id.mediaRewindButton);
        mediaFastForwardButton = findViewById(R.id.mediaFastForwardButton);
        
        // Initialize AudioManager for sound feedback
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }
    
    private void setupListeners() {
        // Info button - show connection details dialog
        infoButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            showConnectionInfoDialog();
        });
        
        // Restart button - show confirmation dialog
        restartButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            showRestartConfirmationDialog();
        });
        
        // Quick Actions - Using Rbox commands
        homeButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_HOME");
        });
        backButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_BACK");
        });
        powerButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            showPowerConfirmationDialog();
        });
        
        // Feature navigation buttons
        numberPadButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            Intent intent = new Intent(this, NumberPadActivity.class);
            intent.putExtra("host", connectedHost);
            intent.putExtra("port", connectedPort);
            startActivity(intent);
        });
        
        keyboardButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            Intent intent = new Intent(this, KeyboardActivity.class);
            intent.putExtra("host", connectedHost);
            intent.putExtra("port", connectedPort);
            startActivity(intent);
        });
        
        appsManagerButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            Intent intent = new Intent(this, AppsManagerActivity.class);
            intent.putExtra("host", connectedHost);
            intent.putExtra("port", connectedPort);
            intent.putExtra("serverHost", connectedHost);
            intent.putExtra("serverPort", 3000);
            startActivity(intent);
        });
        
        // Channel controls - Using Rbox keycode mappings with repeat support
        setupRepeatButton(channelUpButton, "KEYCODE_CHANNEL_UP");
        setupRepeatButton(channelDownButton, "KEYCODE_CHANNEL_DOWN");
        
        // D-Pad controls - Using Rbox keycode mappings with repeat support
        setupRepeatButton(upButton, "KEYCODE_DPAD_UP");
        setupRepeatButton(downButton, "KEYCODE_DPAD_DOWN");
        setupRepeatButton(leftButton, "KEYCODE_DPAD_LEFT");
        setupRepeatButton(rightButton, "KEYCODE_DPAD_RIGHT");
        setupRepeatButton(okButton, "KEYCODE_DPAD_CENTER");
        // Volume buttons - use input keyevent with auto-repeat support
        setupRepeatButton(volumeUpButton, "KEYCODE_VOLUME_UP");
        setupRepeatButton(volumeDownButton, "KEYCODE_VOLUME_DOWN");

        // Mute button doesn't need repeat - uses simple OnClickListener
        muteButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            executeKeyEvent("KEYCODE_VOLUME_MUTE");
        });
        
        // Media controls - Use broadcast intents for better YouTube compatibility
        setupRepeatButton(mediaRewindButton, "KEYCODE_MEDIA_REWIND");
        setupRepeatButton(mediaFastForwardButton, "KEYCODE_MEDIA_FAST_FORWARD");

        // Play/Pause, Previous, Next, Stop - use broadcast intents for YouTube
        mediaPlayPauseButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            // Try broadcast intent first (better for YouTube), then fallback to keyevent
            sendMediaBroadcast("android.intent.action.MEDIA_BUTTON", "KEYCODE_MEDIA_PLAY_PAUSE");
            executeKeyEvent("KEYCODE_MEDIA_PLAY_PAUSE");
        });
        mediaPreviousButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            sendMediaBroadcast("android.intent.action.MEDIA_BUTTON", "KEYCODE_MEDIA_PREVIOUS");
            executeKeyEvent("KEYCODE_MEDIA_PREVIOUS");
        });
        mediaNextButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            sendMediaBroadcast("android.intent.action.MEDIA_BUTTON", "KEYCODE_MEDIA_NEXT");
            executeKeyEvent("KEYCODE_MEDIA_NEXT");
        });
        mediaStopButton.setOnClickListener(v -> {
            provideButtonFeedback(v);
            sendMediaBroadcast("android.intent.action.MEDIA_BUTTON", "KEYCODE_MEDIA_STOP");
            executeKeyEvent("KEYCODE_MEDIA_STOP");
        });
        
    }
    
    /**
     * Setup a button with repeat functionality - sends event repeatedly while held
     */
    private void setupRepeatButton(com.google.android.material.button.MaterialButton button, String keyCode) {
        if (button == null) {
            logManager.logError("setupRepeatButton: button is null for keyCode: " + keyCode, new Exception("Button not found"));
            return;
        }
        
        // Ensure button is clickable and focusable
        button.setClickable(true);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        
        // CRITICAL: Clear any existing listeners first to avoid conflicts
        // OnTouchListener will consume the event, so OnClickListener won't be called
        button.setOnClickListener(null);
        button.setOnTouchListener(null);
        
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getAction();
                logManager.logDebug("Touch event - keyCode: " + keyCode + ", action: " + action + 
                    " (ACTION_DOWN=" + MotionEvent.ACTION_DOWN + ", ACTION_UP=" + MotionEvent.ACTION_UP + ")");
                
                // Check if this is a volume button
                boolean isVolumeButton = keyCode != null && (keyCode.contains("VOLUME_UP") || keyCode.contains("VOLUME_DOWN"));
                
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        // Stop any existing repeat
                        stopRepeat();
                        // Provide feedback
                        provideButtonFeedback(v);
                        // Execute immediately
                        if (isVolumeButton) {
                            logManager.logVolumeInfo("=== VOLUME BUTTON TOUCH DOWN: " + keyCode + " ===");
                        } else {
                            logManager.logDebug("Touch DOWN: " + keyCode);
                        }
                        executeKeyEvent(keyCode);
                        // Start repeat
                        startRepeat(keyCode);
                        return true; // Consume the event
                        
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // Stop repeat when button is released
                        if (isVolumeButton) {
                            logManager.logVolumeInfo("=== VOLUME BUTTON TOUCH UP: " + keyCode + " ===");
                        } else {
                            logManager.logDebug("Touch UP: " + keyCode);
                        }
                        stopRepeat();
                        return true; // Consume the event
                }
                return false;
            }
        });
    }
    
    /**
     * Start repeating the key event
     */
    private void startRepeat(String keyCode) {
        currentRepeatKeyCode = keyCode;
        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentRepeatKeyCode != null && currentRepeatKeyCode.equals(keyCode)) {
                    executeKeyEvent(keyCode);
                    // Schedule next repeat
                    repeatHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            }
        };
        // Start repeating after initial delay
        repeatHandler.postDelayed(repeatRunnable, INITIAL_REPEAT_DELAY);
    }
    
    /**
     * Stop repeating the key event
     */
    private void stopRepeat() {
        currentRepeatKeyCode = null;
        if (repeatRunnable != null) {
            repeatHandler.removeCallbacks(repeatRunnable);
            repeatRunnable = null;
        }
    }
    
    /**
     * Provide haptic and sound feedback when button is clicked
     */
    private void provideButtonFeedback(View view) {
        // Haptic feedback (vibration) - very light like keyboard buttons
        if (view != null) {
            view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            );
        }
        
        // Sound feedback
        if (audioManager != null) {
            try {
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
            } catch (Exception e) {
                // Ignore if sound feedback fails (e.g., device in silent mode)
            }
        }
    }
    
    /**
     * Send media broadcast intent for better app compatibility (especially YouTube)
     */
    private void sendMediaBroadcast(String action, String keyCode) {
        String keycodeValue = KEYCODE_MAP.get(keyCode);
        if (keycodeValue == null) {
            keycodeValue = keyCode.replace("KEYCODE_", "");
        }
        
        // Send broadcast intent via ADB shell for better YouTube compatibility
        // Use multiple methods for maximum compatibility
        String command1 = "am broadcast -a " + action + " --ei android.intent.extra.KEY_EVENT_KEYCODE " + keycodeValue + " \n";
        String command2 = "am broadcast -a android.intent.action.MEDIA_BUTTON --es android.intent.extra.KEY_EVENT_KEYCODE " + keycodeValue + " \n";
        
        // Try first method
        connectionManager.executeCommand(command1, new AdbServerManager.CommandCallback() {
            @Override
            public void onOutput(String output) {}
            
            @Override
            public void onError(String error) {
                // Try second method if first fails
                if (error != null && !error.contains("Not connected") && !error.contains("connection")) {
                    connectionManager.executeCommand(command2, new AdbServerManager.CommandCallback() {
                        @Override
                        public void onOutput(String output) {}
                        
                        @Override
                        public void onError(String error) {
                            if (error != null && (error.contains("Not connected") || error.contains("connection"))) {
                                logManager.logError("Media broadcast failed: " + keyCode, new Exception(error));
                            }
                        }
                        
                        @Override
                        public void onComplete(int exitCode) {}
                    });
                }
            }
            
            @Override
            public void onComplete(int exitCode) {}
        });
    }
    
    
    /**
     * Execute key event using Rbox command format
     * Format: "input keyevent <keycode> \n"
     */
    private void executeKeyEvent(String keyCode) {
        // Check if this is a volume button
        boolean isVolumeButton = keyCode != null && (keyCode.contains("VOLUME_UP") || keyCode.contains("VOLUME_DOWN"));
        
        if (isVolumeButton) {
            logManager.logVolumeInfo(">>> executeKeyEvent() called with: " + keyCode);
        } else {
            logManager.logInfo(">>> executeKeyEvent() called with: " + keyCode);
        }
        
        // Check connection first
        if (connectionManager == null) {
            if (isVolumeButton) {
                logManager.logVolumeError("ConnectionManager is null", new Exception("ConnectionManager not initialized"));
                logManager.logVolumeInfo("<<< executeKeyEvent() EXIT: ConnectionManager is null");
            } else {
                logManager.logError("ConnectionManager is null", new Exception("ConnectionManager not initialized"));
            }
            Toast.makeText(this, "Connection not initialized", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Check connection - but be less strict, allow execution if connection appears active
        boolean connected = connectionManager.isConnected();
        boolean staticConnected = AdbConnectionManager.isConnected;
        
        if (isVolumeButton) {
            logManager.logVolumeDebug("Connection status - instance: " + connected + ", static: " + staticConnected);
        } else {
            logManager.logDebug("Connection status - instance: " + connected + ", static: " + staticConnected);
        }
        
        if (!connected && !staticConnected) {
            if (isVolumeButton) {
                logManager.logVolumeWarn("Not connected - cannot execute key event: " + keyCode + " (instance=" + connected + ", static=" + staticConnected + ")");
                logManager.logVolumeInfo("<<< executeKeyEvent() EXIT: Not connected");
            } else {
                logManager.logWarn("Not connected - cannot execute key event: " + keyCode + " (instance=" + connected + ", static=" + staticConnected + ")");
            }
            Toast.makeText(this, "Not connected to device", Toast.LENGTH_SHORT).show();
            updateConnectionStatus();
            return;
        }
        
        // If static says connected but instance doesn't, still try to execute (connection might be establishing)
        if (!connected && staticConnected) {
            if (isVolumeButton) {
                logManager.logVolumeDebug("Static connected but instance not - attempting execution anyway for: " + keyCode);
            } else {
                logManager.logDebug("Static connected but instance not - attempting execution anyway for: " + keyCode);
            }
        }
        
        // Get keycode value from Rbox mapping
        String keycodeValue = KEYCODE_MAP.get(keyCode);
        if (isVolumeButton) {
            logManager.logVolumeDebug("Keycode lookup - keyCode: " + keyCode + ", mapped value: " + keycodeValue);
        } else {
            logManager.logDebug("Keycode lookup - keyCode: " + keyCode + ", mapped value: " + keycodeValue);
        }
        
        if (keycodeValue == null) {
            // Try using keycode name directly (for standard Android keycodes)
            keycodeValue = keyCode.replace("KEYCODE_", "");
            // Only log if keycode not found (error case)
            if (isVolumeButton) {
                logManager.logVolumeWarn("Keycode not found in map, using: " + keycodeValue);
            } else {
                logManager.logWarn("Keycode not found in map, using: " + keycodeValue);
            }
        }
        
        // Build command: "input keyevent <keycode>\n"
        // Note: Mute works with this format, so use same for all
        String command = "input keyevent " + keycodeValue + "\n";
        
        if (isVolumeButton) {
            logManager.logVolumeInfo("Executing key event: " + keyCode + " (keycode=" + keycodeValue + ")");
            logManager.logVolumeInfo("Command to execute: [" + command.replace("\n", "\\n") + "]");
            logManager.logVolumeDebug("Command bytes length: " + command.getBytes().length);
        } else {
            logManager.logInfo("Executing key event: " + keyCode + " (keycode=" + keycodeValue + ")");
            logManager.logInfo("Command to execute: [" + command.replace("\n", "\\n") + "]");
        }
        
        executeCommandWithCallback(command, new AdbServerManager.CommandCallback() {
            @Override
            public void onOutput(String output) {
                if (isVolumeButton) {
                    logManager.logVolumeDebug("Key event output: " + (output != null ? output : "null"));
                } else {
                    logManager.logDebug("Key event output: " + (output != null ? output : "null"));
                }
            }
            
            @Override
            public void onError(String error) {
                if (isVolumeButton) {
                    logManager.logVolumeError("Key event error: " + keyCode + " - " + (error != null ? error : "null"), 
                        error != null ? new Exception(error) : new Exception("Unknown error"));
                    // Only log connection errors, not command execution errors
                    if (error != null && (error.contains("Not connected") || error.contains("connection") || error.contains("Socket"))) {
                        logManager.logVolumeError("Key event failed (connection issue): " + keyCode, new Exception(error));
                    }
                } else {
                    logManager.logError("Key event error: " + keyCode + " - " + (error != null ? error : "null"), 
                        error != null ? new Exception(error) : new Exception("Unknown error"));
                    // Only log connection errors, not command execution errors
                    if (error != null && (error.contains("Not connected") || error.contains("connection") || error.contains("Socket"))) {
                        logManager.logError("Key event failed (connection issue): " + keyCode, new Exception(error));
                    }
                }
            }
            
            @Override
            public void onComplete(int exitCode) {
                if (isVolumeButton) {
                    logManager.logVolumeInfo("Key event completed: " + keyCode + " (exitCode=" + exitCode + ")");
                    logManager.logVolumeInfo("<<< executeKeyEvent() COMPLETE");
                } else {
                    logManager.logInfo("Key event completed: " + keyCode + " (exitCode=" + exitCode + ")");
                    logManager.logInfo("<<< executeKeyEvent() COMPLETE");
                }
                // No completion handling needed for icon-only UI
            }
        });
    }
    
    private void updateUI() {
        // Update connection status color and visibility
        updateConnectionStatus();
    }
    
    /**
     * Update connection status indicator color based on connection state
     * Uses static volatile variables from AdbConnectionManager for real-time state
     */
    private void updateConnectionStatus() {
        if (connectionStatusIndicator == null) {
            return; // Views not initialized yet
        }
        
        // Read from static volatile variables - these are updated immediately when connection state changes
        boolean connected = AdbConnectionManager.isConnected;
        boolean connecting = AdbConnectionManager.isConnecting;
        String host = AdbConnectionManager.connectedHost;
        int port = AdbConnectionManager.connectedPort;
        
        int color;
        String newState;
        String statusText;
        
        if (connected) {
            // Green for connected
            color = 0xFF4CAF50; // #4CAF50
            newState = "connected";
            // Update text with IP:port from static variable
            if (host != null) {
                statusText = host + ":" + port;
            } else {
                statusText = "Connected";
            }
        } else if (connecting) {
            // Orange for connecting
            color = 0xFFFF9800; // #FF9800
            newState = "connecting";
            if (host != null) {
                statusText = "Connecting to " + host + ":" + port;
            } else {
                statusText = "Connecting...";
            }
        } else {
            // Red for disconnected
            color = 0xFFF44336; // #F44336
            newState = "disconnected";
            statusText = "Not connected";
        }
        
        // Only update UI if state changed to avoid unnecessary redraws
        if (!newState.equals(connectionState)) {
            connectionState = newState;
            
            // Update indicator color
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                connectionStatusIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
            } else {
                // For older APIs, create a new drawable with the color
                android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
                drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                drawable.setColor(color);
                connectionStatusIndicator.setBackground(drawable);
            }
            
            // IP/port display removed - only show connection indicator
        } else if (connected) {
            // Connection is active - indicator will show green
        }
    }
    
    /**
     * Show connection info dialog with device details and disconnect button
     */
    private void showConnectionInfoDialog() {
        // Use static volatile variables for real-time state
        boolean connected = AdbConnectionManager.isConnected;
        boolean connecting = AdbConnectionManager.isConnecting;
        String host = AdbConnectionManager.connectedHost;
        int port = AdbConnectionManager.connectedPort;
        
        String statusText = connected ? "Connected" : (connecting ? "Connecting..." : "Disconnected");
        String deviceInfo = connected ? (host + ":" + port) : (host != null ? (host + ":" + port) : "Not connected");
        
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_connection_info, null);
        TextView statusTextView = dialogView.findViewById(R.id.statusTextView);
        TextView deviceInfoTextView = dialogView.findViewById(R.id.deviceInfoTextView);
        com.google.android.material.button.MaterialButton disconnectBtn = dialogView.findViewById(R.id.dialogDisconnectButton);
        
        statusTextView.setText("Status: " + statusText);
        deviceInfoTextView.setText("Device: " + deviceInfo);
        
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Connection Information")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create();
        
        if (connected) {
            disconnectBtn.setVisibility(View.VISIBLE);
            disconnectBtn.setOnClickListener(v -> {
                disconnect();
                dialog.dismiss();
            });
        } else {
            disconnectBtn.setVisibility(View.GONE);
        }
        
        dialog.show();
    }
    
    /**
     * Show restart confirmation dialog
     */
    private void showRestartConfirmationDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Restart Device")
            .setMessage("Are you sure you want to restart the device?")
            .setPositiveButton("Yes", (dialog, which) -> restartDevice())
            .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show();
    }
    
    /**
     * Restart the device using ADB command
     */
    private void restartDevice() {
        // Use reboot command via ADB
        String command = "reboot \n";
        
        executeCommandWithCallback(command, new AdbServerManager.CommandCallback() {
            @Override
            public void onOutput(String output) {
                logManager.logInfo("Restart command output: " + output);
            }
            
            @Override
            public void onError(String error) {
                logManager.logError("Restart failed", new Exception(error));
            }
            
            @Override
            public void onComplete(int exitCode) {
                logManager.logInfo("Restart command completed with exit code: " + exitCode);
            }
        });
    }
    
    /**
     * Show confirmation dialog before executing power command
     */
    private void showPowerConfirmationDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Turn Off Device")
            .setMessage("Are you sure you want to turn off the device?")
            .setPositiveButton("Yes", (dialog, which) -> executeKeyEvent("KEYCODE_POWER"))
            .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show();
    }
    
    private void executeCommandWithCallback(String command, AdbServerManager.CommandCallback customCallback) {
        logManager.logInfo(">>> executeCommandWithCallback() called");
        logManager.logDebug("Command: [" + command.trim() + "]");
        logManager.logDebug("Command length: " + command.length());
        logManager.logDebug("Has callback: " + (customCallback != null));
        
        if (command.isEmpty()) {
            logManager.logWarn("Command is empty, returning");
            return;
        }
        
        // Check connection first
        if (connectionManager == null) {
            logManager.logError("ConnectionManager is null", new Exception("ConnectionManager not initialized"));
            if (customCallback != null) {
                customCallback.onError("Connection not initialized");
            }
            logManager.logInfo("<<< executeCommandWithCallback() EXIT: ConnectionManager is null");
            return;
        }
        
        // Check connection - but be less strict, allow execution if connection appears active
        boolean connected = connectionManager.isConnected();
        boolean staticConnected = AdbConnectionManager.isConnected;
        
        logManager.logDebug("Connection status - instance: " + connected + ", static: " + staticConnected);
        
        if (!connected && !staticConnected) {
            logManager.logWarn("Not connected - cannot execute command: " + command.trim() + " (instance=" + connected + ", static=" + staticConnected + ")");
            if (customCallback != null) {
                customCallback.onError("Not connected to device");
            }
            updateConnectionStatus();
            return;
        }
        
        // If static says connected but instance doesn't, still try to execute (connection might be establishing)
        if (!connected && staticConnected) {
            logManager.logDebug("Static connected but instance not - attempting execution anyway for: " + command.trim());
        }
        
        // Don't block if already executing - just queue the command
        if (isExecuting) {
            // Continue - don't return, let the command be queued (optimized for low latency)
        }
        
        isExecuting = true;
        
        logManager.logInfo("Executing command: [" + command.trim() + "]");
        logManager.logDebug("isExecuting set to: true");
        
        // Ensure command ends with " \n" (Rbox format - space + newline)
        if (!command.endsWith(" \n")) {
            if (command.endsWith("\n")) {
                // Add space before newline if missing
                command = command.substring(0, command.length() - 1) + " \n";
            } else {
                command = command + " \n";
            }
            logManager.logDebug("Command modified to end with space + \\n (Rbox format)");
        }
        
        AdbServerManager.CommandCallback callback = customCallback != null ? customCallback : new AdbServerManager.CommandCallback() {
            @Override
            public void onOutput(String output) {
                logManager.logDebug("Command output received: " + (output != null ? output : "null"));
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    logManager.logError("Command error: " + (error != null ? error : "null"), 
                        error != null ? new Exception(error) : new Exception("Unknown error"));
                    isExecuting = false;
                    logManager.logDebug("isExecuting set to: false (from onError)");
                    // Only log connection errors
                    if (error != null && (error.contains("Not connected") || error.contains("connection") || error.contains("Socket"))) {
                        logManager.logError("Command failed (connection issue)", new Exception(error));
                    }
                });
            }
            
            @Override
            public void onComplete(int exitCode) {
                runOnUiThread(() -> {
                    logManager.logInfo("Command completed with exit code: " + exitCode);
                    isExecuting = false;
                    logManager.logDebug("isExecuting set to: false (from onComplete)");
                    logManager.logInfo("<<< executeCommandWithCallback() COMPLETE");
                });
            }
        };
        
        try {
            logManager.logInfo("Calling connectionManager.executeCommand()");
            connectionManager.executeCommand(command, callback);
            logManager.logDebug("connectionManager.executeCommand() called successfully");
        } catch (Exception e) {
            logManager.logError("Exception in executeCommandWithCallback: " + e.getMessage(), e);
            isExecuting = false;
            logManager.logDebug("isExecuting set to: false (from exception)");
            // Only log connection errors
            if (e.getMessage() != null && (e.getMessage().contains("connection") || e.getMessage().contains("Socket"))) {
                logManager.logError("Failed to execute command", e);
            }
        }
    }
    
    private void disconnect() {
        logManager.logInfo("Disconnect button clicked");
        
        Intent serviceIntent = new Intent(this, AdbConnectionService.class);
        serviceIntent.putExtra("action", "stop");
        stopService(serviceIntent);
        
        if (connectionManager != null) {
            connectionManager.disconnect();
        }
        
        // Update connection status before navigating away
        updateConnectionStatus();
        
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Update connection status when activity resumes
        updateConnectionStatus();
        // Resume periodic updates
        startConnectionStatusUpdates();
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // Pause periodic updates to save resources
        stopConnectionStatusUpdates();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stop any ongoing repeat
        stopRepeat();
        if (repeatHandler != null) {
            repeatHandler.removeCallbacksAndMessages(null);
            repeatHandler = null; // Prevent memory leak
        }
        // Stop connection status updates
        stopConnectionStatusUpdates();
        if (connectionStatusHandler != null) {
            connectionStatusHandler.removeCallbacksAndMessages(null);
            connectionStatusHandler = null; // Prevent memory leak
        }
        connectionStatusUpdateRunnable = null; // Prevent memory leak
        logManager.logInfo("ControlActivity onDestroy");
        // Don't disconnect here - Service manages the connection
        // Only disconnect if explicitly requested by user
    }
    
    /**
     * Start periodic connection status updates
     */
    private void startConnectionStatusUpdates() {
        if (connectionStatusHandler != null && connectionStatusUpdateRunnable != null) {
            // Remove any existing callbacks to avoid duplicates
            connectionStatusHandler.removeCallbacks(connectionStatusUpdateRunnable);
            // Start periodic updates
            connectionStatusHandler.post(connectionStatusUpdateRunnable);
        }
    }
    
    /**
     * Stop periodic connection status updates
     */
    private void stopConnectionStatusUpdates() {
        if (connectionStatusHandler != null && connectionStatusUpdateRunnable != null) {
            connectionStatusHandler.removeCallbacks(connectionStatusUpdateRunnable);
        }
    }
}