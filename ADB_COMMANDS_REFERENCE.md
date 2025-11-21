# ADB Commands Reference - Working Examples from Open Source

This document contains working ADB command implementations collected from GitHub, GitLab, and other open-source repositories.

## Key Findings from Research

### 1. Command Format
All working implementations use these formats:
- **Key Events**: `input keyevent <keycode>`
- **Text Input**: `input text <text>` or `input keyboard text <text>`
- **Touch Events**: `input touchscreen tap <x> <y>`
- **Swipe**: `input swipe <x1> <y1> <x2> <y2> <duration>`

### 2. Working Implementations

#### ADB Remote Control by Oberien
- Repository: https://github.com/oberien/adb-remote-control
- **Key Features**:
  - Mouse clicks, drags, keyboard inputs
  - ASCII characters (32-126) sent as text
  - Enter key emulates Return
  - Escape key emulates Back
  - Insert key emulates Home
  - Navigation keys (Arrow keys, Home, End, Page Up, Page Down)

#### AndroidTV Remote Controller by Jekso
- Repository: https://github.com/Jekso/AndroidTV-Remote-Controller
- **Key Features**:
  - Python-based ADB client
  - `send_keyevent_input` method for button presses
  - Text input support
  - Connection management

#### Play with ADB by Sky-bro
- Repository: https://github.com/sky-bro/play-with-adb
- **Commands**:
  ```bash
  # Tap at coordinates
  adb shell input touchscreen tap 300 400
  
  # Swipe
  adb shell input swipe <start_x> <start_y> <end_x> <end_y> <duration_ms>
  
  # Text input
  adb shell input text 'your_text_here'
  ```

#### Phone-MCP by Hao-cyber
- Repository: https://github.com/hao-cyber/phone-mcp
- **Features**:
  - Screen interaction (tap, swipe, type)
  - Media control
  - UI interaction commands

### 3. Common ADB Shell Commands

#### Navigation Keys
```bash
# D-Pad
adb shell input keyevent KEYCODE_DPAD_UP      # 19
adb shell input keyevent KEYCODE_DPAD_DOWN    # 20
adb shell input keyevent KEYCODE_DPAD_LEFT    # 21
adb shell input keyevent KEYCODE_DPAD_RIGHT   # 22
adb shell input keyevent KEYCODE_DPAD_CENTER  # 23

# Media Keys
adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE  # 85
adb shell input keyevent KEYCODE_MEDIA_STOP        # 86
adb shell input keyevent KEYCODE_MEDIA_NEXT       # 87
adb shell input keyevent KEYCODE_MEDIA_PREVIOUS   # 88

# Volume
adb shell input keyevent KEYCODE_VOLUME_UP    # 24
adb shell input keyevent KEYCODE_VOLUME_DOWN  # 25
adb shell input keyevent KEYCODE_VOLUME_MUTE  # 164

# System Keys
adb shell input keyevent KEYCODE_HOME         # 3
adb shell input keyevent KEYCODE_BACK        # 4
adb shell input keyevent KEYCODE_MENU        # 82
adb shell input keyevent KEYCODE_POWER       # 26
```

#### Alternative Command Formats
```bash
# Using numeric keycodes
adb shell input keyevent 19  # UP
adb shell input keyevent 20  # DOWN
adb shell input keyevent 21  # LEFT
adb shell input keyevent 22  # RIGHT

# Text input with spaces
adb shell input text "hello world"
# Or using keyboard text
adb shell input keyboard text "hello world"

# URL opening
adb shell am start -a android.intent.action.VIEW -d "https://example.com"

# App launching
adb shell monkey -p com.package.name -c android.intent.category.LAUNCHER 1
```

### 4. Rbox Implementation Analysis

From Rbox source code analysis:
- Commands are queued to `LinkedBlockingQueue`
- Format: `"input keyevent <keycode> \n"` (note the space before newline)
- Commands sent via `g()` method which:
  1. Waits for `writeReady` flag (AtomicBoolean)
  2. Sends WRTE message
  3. Waits for OKAY response
  4. OKAY sets `writeReady` back to true

### 5. Critical Implementation Details

#### Command Queue Flow (Rbox Style)
1. Command added to `LinkedBlockingQueue` as bytes
2. Write thread takes command from queue (`take()` - blocks forever)
3. Write thread calls `shellStream.write()` which:
   - Waits for `writeReady.compareAndSet(true, false)`
   - Sends WRTE message
   - `writeReady` is now false
4. Response reader receives OKAY
5. OKAY handler sets `writeReady` back to true
6. Next command can proceed

#### Key Synchronization Points
- `writeReady` must be set to true when OKAY is received
- `notifyAll()` must be called to wake waiting write threads
- Commands must wait for `writeReady` before sending WRTE
- Each WRTE must be acknowledged with OKAY before next command

### 6. Troubleshooting

#### Commands Not Working After First
- **Issue**: `writeReady` not reset after OKAY
- **Fix**: Ensure OKAY handler sets `writeReady.set(true)` and calls `notifyAll()`

#### Commands Blocking
- **Issue**: Write thread waiting indefinitely
- **Fix**: Check that OKAY responses are being received and processed

#### Connection Lost
- **Issue**: Socket timeout or connection closed
- **Fix**: Remove socket timeout after connection, keep response reader alive

### 7. Recommended Command Formats

Based on research, use these formats:

```java
// Key events (Rbox style)
"input keyevent " + keycode + " \n"

// Text input (Rbox style)
"input keyboard text " + text.replace(" ", "%s") + " \n"

// URL opening (Rbox style)
"am start -a android.intent.action.VIEW -d " + url + " -e source 30 \n"

// App launching (Rbox style)
"monkey --pct-syskeys 0 -p " + packageName + " -v 1 \n"
```

### 8. References

- [ADB Remote Control](https://github.com/oberien/adb-remote-control)
- [AndroidTV Remote Controller](https://github.com/Jekso/AndroidTV-Remote-Controller)
- [Play with ADB](https://github.com/sky-bro/play-with-adb)
- [Phone-MCP](https://github.com/hao-cyber/phone-mcp)
- [ADB Commands Collection](https://github.com/lana-20/adb-commands)

