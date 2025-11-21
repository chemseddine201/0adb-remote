# Free ADB Remote

A free, open-source Android application for remote ADB control over WiFi. Control your Android device remotely using ADB commands with a user-friendly interface.

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Android](https://img.shields.io/badge/platform-Android-green.svg)
![Min SDK](https://img.shields.io/badge/min%20SDK-26-orange.svg)

## Overview

Free ADB Remote consists of two Android applications:

1. **Main App** (`app`): The client application that connects to your Android device and provides remote control interface
2. **Remote Server** (`remoteServer`): A privileged server application that runs on the target device to provide app management capabilities

## Features

### Main Application Features

- 🔐 **One-Time Trust**: Establishes trust on first connection, no prompts on subsequent connections
- 🔄 **Auto-Reconnect**: Automatically reconnects when connection is lost
- 📱 **Remote Control**: Full remote control of Android device via ADB commands
- 🎮 **Device Control**: Send key events, control media, navigate device
- 📊 **Apps Manager**: View and manage user-installed applications (excludes system apps)
- 🔌 **Persistent Connection**: Maintains connection via foreground service
- 🚀 **Native Implementation**: Pure Java ADB protocol implementation - no external dependencies
- 🌓 **Dark/Light Mode**: Full support for system theme switching
- ⌨️ **Virtual Keyboard**: Full keyboard input support
- 🔢 **Number Pad**: Quick number input for TV boxes

### Remote Server Features

- 🌐 **HTTP API Server**: Runs on port 3000 providing REST API endpoints
- 📦 **App Listing**: Lists all user-installed applications (excludes system apps)
- 🔍 **App Information**: Provides app details including name, version, and running status
- 🔄 **Auto-Start**: Automatically starts on device boot
- 🔐 **Privileged Access**: Runs as system/priv-app for full package manager access

## Project Structure

```
FreeAdbRemote/
├── app/                          # Main client application
│   ├── src/main/
│   │   ├── java/com/freeadbremote/
│   │   │   ├── AdbClient.java              # Native ADB protocol client
│   │   │   ├── AdbServerManager.java       # ADB server management
│   │   │   ├── AdbConnectionManager.java   # Connection manager (Singleton)
│   │   │   ├── AdbConnectionService.java   # Foreground service
│   │   │   ├── AdbKeyManager.java         # RSA key management
│   │   │   ├── ServerDeployment.java      # Remote server deployment
│   │   │   ├── ServerClient.java          # HTTP client for remote server
│   │   │   ├── MainActivity.java          # Main connection screen
│   │   │   ├── ControlActivity.java       # Device control interface
│   │   │   ├── AppsManagerActivity.java   # Apps manager interface
│   │   │   ├── KeyboardActivity.java      # Virtual keyboard
│   │   │   ├── NumberPadActivity.java     # Number pad
│   │   │   ├── CacheManager.java          # Caching system
│   │   │   ├── ThreadPoolManager.java     # Thread pool management
│   │   │   ├── ErrorHandler.java          # Error handling with circuit breaker
│   │   │   ├── RetryManager.java          # Retry logic with exponential backoff
│   │   │   └── ...                        # Other utility classes
│   │   ├── res/                            # Resources (layouts, drawables, values)
│   │   └── AndroidManifest.xml
│   └── build.gradle
│
├── remoteServer/                  # Remote server application
│   ├── src/main/
│   │   ├── java/com/freeadbremote/remoteserver/
│   │   │   ├── HttpAppManagerServer.java   # HTTP API server (NanoHTTPD)
│   │   │   ├── AppManagerService.java      # Foreground service
│   │   │   ├── AppManagerApplication.java  # Application class
│   │   │   ├── LauncherActivity.java       # Launcher activity
│   │   │   └── BootReceiver.java           # Boot receiver
│   │   ├── res/                             # Resources
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── README.md
│
├── build.gradle                   # Root build configuration
├── settings.gradle                # Project settings
├── gradle.properties              # Gradle properties
└── README.md                      # This file
```

## Requirements

### For Main Application
- **Android Device**: Android 8.0 (API 26) or higher
- **ADB over WiFi**: Device must support ADB over network
- **Network**: Both devices on same network (or port forwarding)

### For Remote Server
- **Android Device**: Android 8.0 (API 26) or higher
- **Root/Privileged Access**: Must be installed as system/priv-app for full functionality
- **Permissions**: Requires `QUERY_ALL_PACKAGES` permission (Android 11+)

## Installation

### Building from Source

#### Prerequisites
- Android Studio Arctic Fox or later
- JDK 8 or higher
- Android SDK (API 26+)
- Gradle 7.0+

#### Build Main Application

```bash
# Clone the repository
git clone https://github.com/chemseddine201/0adb-remote.git
cd FreeAdbRemote

# Build debug APK
./gradlew :app:assembleDebug

# Build release APK
./gradlew :app:assembleRelease

# Output location
# app/build/outputs/apk/debug/app-debug.apk
```

#### Build Remote Server

```bash
# Build debug APK
./gradlew :remoteServer:assembleDebug

# Build release APK
./gradlew :remoteServer:assembleRelease

# Output location
# remoteServer/build/outputs/apk/debug/remoteServer-debug.apk
```

#### Build Both Applications

```bash
# Build both applications
./gradlew assembleDebug

# Clean and rebuild
./gradlew clean assembleDebug
```

### Installing Applications

#### Install Main Application

```bash
# Install via ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Or install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Install Remote Server (Privileged Installation)

The remote server must be installed as a system/priv-app for full functionality:

**Option 1: Using ADB (Requires Root)**

```bash
# Push APK to system partition
adb root
adb remount
adb push remoteServer/build/outputs/apk/debug/remoteServer-debug.apk /system/priv-app/AppManagerServer/AppManagerServer.apk

# Set permissions
adb shell chmod 644 /system/priv-app/AppManagerServer/AppManagerServer.apk
adb shell chmod 755 /system/priv-app/AppManagerServer

# Reboot device
adb reboot
```

**Option 2: Manual Installation (Root Required)**

1. Copy `remoteServer-debug.apk` to device
2. Move to `/system/priv-app/AppManagerServer/` using root file manager
3. Set permissions: `644` for APK, `755` for directory
4. Reboot device

**Option 3: Regular Installation (Limited Functionality)**

```bash
# Install as regular app (some features may not work)
adb install remoteServer/build/outputs/apk/debug/remoteServer-debug.apk
```

## Usage

### Initial Setup

#### Step 1: Enable ADB over WiFi

On your Android device:

```bash
# Connect device via USB first
adb devices

# Enable TCP/IP mode
adb tcpip 5555

# Connect wirelessly (replace with your device IP)
adb connect <device-ip>:5555

# Verify connection
adb devices
```

#### Step 2: Install and Start Remote Server

1. Install remote server as system/priv-app (see installation section above)
2. Start the server:

```bash
# Start via ADB
adb shell am start -n com.freeadbremote.remoteserver/.LauncherActivity

# Or using monkey
adb shell monkey -p com.freeadbremote.remoteserver -c android.intent.category.LAUNCHER 1
```

3. Verify server is running:

```bash
# Check HTTP endpoint
curl http://<device-ip>:3000/api/health

# Or check service
adb shell dumpsys activity services | grep AppManagerService
```

#### Step 3: Connect Main Application

1. **Open the app** on your Android device (or another device)
2. **Enter connection details**:
   - Host: Your device's IP address (e.g., `192.168.1.100`)
   - Port: ADB port (default: `5555`)
3. **Connect**: Tap "Connect" button
   - On first connection, accept the authorization prompt on your device
   - Subsequent connections will be automatic

### Main Application Features

#### Device Control
- **Navigation**: Home, Back, Recent Apps buttons
- **Media Control**: Play/Pause, Next/Previous, Volume Up/Down
- **Power Control**: Restart (orange), Power Off (black), Info (blue)
- **Channel Control**: Channel up/down for TV boxes
- **Directional Control**: Arrow keys for navigation

#### Apps Manager
- **View User Apps**: Lists all user-installed applications (system apps excluded)
- **App Status**: Shows running/stopped status for each app
- **App Information**: Displays app name, package name, and memory usage
- **Auto-Refresh**: List automatically updates when apps change
- **Dark/Light Mode**: Full theme support

#### Keyboard & Number Pad
- **Virtual Keyboard**: Full QWERTY keyboard input
- **Number Pad**: Quick number input (0-9) for TV boxes and channel selection

### Remote Server API

The remote server provides HTTP REST API endpoints:

#### Health Check
```bash
GET http://<device-ip>:3000/api/health
```

Response:
```json
{
  "status": "ok",
  "timestamp": 1234567890,
  "services": {
    "context": true,
    "pm": true,
    "am": true
  },
  "cache": {
    "appInfo": 0,
    "icons": 0
  }
}
```

#### List User Apps
```bash
GET http://<device-ip>:3000/api/apps/user
```

Response:
```json
{
  "success": true,
  "count": 25,
  "apps": [
    {
      "package": "com.example.app",
      "name": "Example App",
      "versionName": "1.0.0",
      "versionCode": 1,
      "isSystem": false,
      "running": true,
      "icon": null
    }
  ]
}
```

## How It Works

### ADB Protocol Implementation

The main app implements the full ADB protocol in pure Java:

- **CNXN**: Connection handshake
- **AUTH**: Authentication (RSA public key)
- **OPEN**: Open shell stream
- **WRTE**: Write command data
- **OKAY**: Acknowledgment
- **CLSE**: Close stream

### One-Time Trust Mechanism

1. On first connection, the app generates an RSA key pair
2. Public key is sent to the device during authentication
3. Device shows authorization prompt (user accepts once)
4. Public key is written to `/data/misc/adb/adb_keys` on device
5. Subsequent connections use the same key - no prompt needed

### Connection Flow

1. User enters host and port in MainActivity
2. App connects via TCP socket to ADB server
3. ADB handshake (CNXN message)
4. Authentication (AUTH with RSA public key)
5. Open shell stream
6. Commands are queued and sent via WRTE messages
7. Responses received and parsed
8. Connection maintained via foreground service (AdbConnectionService)

### Remote Server Deployment

The main app automatically deploys the remote server to the target device:

1. Checks if remote server is already installed
2. Compares version codes (only installs if newer version)
3. Transfers APK via optimized socket-based method
4. Installs using `pm install`
5. Starts server automatically

### System App Filtering

The remote server uses multiple methods to filter out system apps:

1. **Flag Check**: `FLAG_SYSTEM` and `FLAG_UPDATED_SYSTEM_APP`
2. **Location Check**: System apps are in `/system/app/`, `/system/priv-app/`, etc.
3. **User apps only**: Only apps installed in `/data/app/` are included

## Architecture

### Design Patterns

- **Singleton Pattern**: `AdbConnectionManager`, `CacheManager`, `ThreadPoolManager`
- **Service Pattern**: `AdbConnectionService` maintains connection lifecycle
- **Callback Pattern**: Async operations use callbacks for results
- **Circuit Breaker Pattern**: `ErrorHandler` prevents cascading failures
- **Retry Pattern**: `RetryManager` with exponential backoff

### Key Components

#### Main Application
- **AdbClient**: Low-level ADB protocol implementation
- **AdbServerManager**: High-level ADB server management
- **AdbConnectionManager**: Singleton for connection sharing
- **AdbConnectionService**: Foreground service for persistent connection
- **ServerDeployment**: Handles remote server deployment
- **ServerClient**: HTTP client for remote server API
- **CacheManager**: LRU cache for app names and icons
- **ThreadPoolManager**: Centralized thread pool management
- **ErrorHandler**: Error handling with circuit breaker
- **RetryManager**: Retry logic with exponential backoff

#### Remote Server
- **HttpAppManagerServer**: HTTP API server using NanoHTTPD
- **AppManagerService**: Foreground service that runs the HTTP server
- **AppManagerApplication**: Application class that starts service
- **LauncherActivity**: Simple launcher for manual/service start
- **BootReceiver**: Starts server automatically on boot

## Permissions

### Main Application Permissions
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`: For foreground service
- `POST_NOTIFICATIONS`: For connection status notifications
- `CHANGE_NETWORK_STATE`: For network management
- `CHANGE_WIFI_STATE`: For WiFi management
- `INTERNET`: For network connections
- `ACCESS_NETWORK_STATE`: For network state checking

### Remote Server Permissions
- `QUERY_ALL_PACKAGES`: Required for listing all packages (Android 11+)
- `INTERNET`: For HTTP server
- `ACCESS_NETWORK_STATE`: For network state checking
- `KILL_BACKGROUND_PROCESSES`: For app management
- `RECEIVE_BOOT_COMPLETED`: For auto-start on boot
- `FOREGROUND_SERVICE`: For foreground service
- `FOREGROUND_SERVICE_DATA_SYNC`: For data sync service type
- `POST_NOTIFICATIONS`: For service notifications

## Troubleshooting

### Connection Issues

**"Not connected"**
- Ensure ADB over WiFi is enabled on device
- Check firewall settings
- Verify both devices are on same network

**"Authorization failed"**
- Accept the prompt on device on first connection
- Check if RSA key is properly stored

**"Connection timeout"**
- Check network connectivity
- Verify ADB port (default: 5555) is not blocked
- Try restarting ADB: `adb kill-server && adb start-server`

### Remote Server Issues

**Server not starting**
- Verify installation as system/priv-app
- Check permissions are set correctly
- Try manual start: `adb shell am start -n com.freeadbremote.remoteserver/.LauncherActivity`

**API not responding**
- Check if service is running: `adb shell dumpsys activity services | grep AppManagerService`
- Verify port 3000 is accessible: `curl http://<device-ip>:3000/api/health`
- Check logs: `adb logcat | grep AppManagerService`

**Apps not listing**
- Verify `QUERY_ALL_PACKAGES` permission is granted
- Check if installed as system/priv-app
- Review logcat for errors

### Build Issues

**Gradle sync failed**
- Update Android Gradle Plugin
- Check JDK version (requires JDK 8+)
- Clean project: `./gradlew clean`

**Build errors**
- Clean and rebuild: `./gradlew clean assembleDebug`
- Check Android SDK is properly installed
- Verify compileSdk and targetSdk versions

## Development

### Key Classes

#### Main Application
- `AdbClient.java`: Core ADB protocol implementation
- `AdbConnectionManager.java`: Connection state management
- `ServerDeployment.java`: Remote server deployment logic
- `ControlActivity.java`: Main control interface
- `AppsManagerActivity.java`: Apps management interface

#### Remote Server
- `HttpAppManagerServer.java`: HTTP API server
- `AppManagerService.java`: Service wrapper
- `LauncherActivity.java`: Manual launcher

### Building for Development

```bash
# Debug build with logging
./gradlew :app:assembleDebug
./gradlew :remoteServer:assembleDebug

# View logs
adb logcat | grep -E "FreeAdbRemote|AppManagerService"
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Based on ADB protocol specification
- Inspired by open-source ADB implementations
- Uses Material Design components
- HTTP server powered by NanoHTTPD

## Disclaimer

This software is provided "as is" without warranty of any kind. Use at your own risk. The authors are not responsible for any damage or data loss caused by using this software.

## Support

For issues, questions, or contributions, please open an issue on [GitHub](https://github.com/chemseddine201/0adb-remote/issues).

---

Made with ❤️ for the open-source community
