# Remote Server Application

A privileged Android application that runs as a system/priv-app to provide HTTP API endpoints for app management on the target device.

## Overview

The Remote Server is a companion application that must be installed on the target Android device to enable advanced app management features. It runs as a privileged system application to access package manager APIs that regular apps cannot access.

## Features

- 🌐 **HTTP API Server**: Runs on port 3000 providing REST API endpoints
- 📦 **App Listing**: Lists all user-installed applications (excludes system apps)
- 🔍 **App Information**: Provides app details including name, version, and running status
- 🔄 **Auto-Start**: Automatically starts on device boot via BootReceiver
- 🔐 **Privileged Access**: Runs as system/priv-app for full package manager access
- 🚫 **System App Filtering**: Automatically excludes system apps from listings

## Requirements

- **Android Version**: Android 8.0 (API 26) or higher
- **Installation**: Must be installed as system/priv-app for full functionality
- **Permissions**: Requires `QUERY_ALL_PACKAGES` permission (Android 11+)
- **Root Access**: Required for system/priv-app installation

## Installation

### Prerequisites

1. Root access on target device
2. ADB connection to device
3. Built APK from this project

### Installation Steps

#### Step 1: Build the APK

```bash
# From project root
./gradlew :remoteServer:assembleDebug

# Output: remoteServer/build/outputs/apk/debug/remoteServer-debug.apk
```

#### Step 2: Install as System/Priv-App

**Option A: Using ADB (Recommended)**

```bash
# Enable root access
adb root

# Remount system partition as writable
adb remount

# Create directory for the app
adb shell mkdir -p /system/priv-app/AppManagerServer

# Push APK to system partition
adb push remoteServer/build/outputs/apk/debug/remoteServer-debug.apk \
         /system/priv-app/AppManagerServer/AppManagerServer.apk

# Set correct permissions
adb shell chmod 644 /system/priv-app/AppManagerServer/AppManagerServer.apk
adb shell chmod 755 /system/priv-app/AppManagerServer

# Reboot device (required for system apps)
adb reboot
```

**Option B: Manual Installation**

1. Copy `remoteServer-debug.apk` to device storage
2. Using a root file manager (e.g., Root Explorer):
   - Navigate to `/system/priv-app/`
   - Create folder `AppManagerServer`
   - Copy APK to folder and rename to `AppManagerServer.apk`
   - Set permissions: `644` for APK, `755` for directory
3. Reboot device

**Option C: Regular Installation (Limited Functionality)**

```bash
# Install as regular app (some features may not work)
adb install remoteServer/build/outputs/apk/debug/remoteServer-debug.apk
```

⚠️ **Note**: Regular installation may not have access to all package manager APIs, especially on Android 11+.

### Step 3: Verify Installation

After reboot, verify the app is installed:

```bash
# Check if app is installed
adb shell pm list packages | grep remoteserver

# Should show: package:com.freeadbremote.remoteserver
```

## Starting the Server

### Method 1: Automatic Start (Recommended)

The server automatically starts:
- On device boot (via BootReceiver)
- When app is launched (via AppManagerApplication)

### Method 2: Manual Start via ADB

```bash
# Start via Activity (recommended for Android 8.0+)
adb shell am start -n com.freeadbremote.remoteserver/.LauncherActivity

# Or using monkey
adb shell monkey -p com.freeadbremote.remoteserver -c android.intent.category.LAUNCHER 1
```

### Method 3: Start Service Directly (Android 7.1 or lower only)

```bash
# ⚠️ This may not work on Android 8.0+ due to foreground service restrictions
adb shell am startservice -n com.freeadbremote.remoteserver/.AppManagerService \
    -a com.freeadbremote.remoteserver.START_SERVER
```

## Verifying Server Status

### Check HTTP Endpoint

```bash
# Test health endpoint
curl http://<device-ip>:3000/api/health

# Expected response:
# {
#   "status": "ok",
#   "timestamp": 1234567890,
#   "services": {
#     "context": true,
#     "pm": true,
#     "am": true
#   }
# }
```

### Check Service Status

```bash
# Check if service is running
adb shell dumpsys activity services | grep AppManagerService

# Check process
adb shell ps | grep remoteserver
```

### View Logs

```bash
# View server logs
adb logcat | grep -E "AppManagerService|HttpAppManagerServer"

# Or more specific
adb logcat -s AppManagerApplication:D AppManagerService:D HttpAppManagerServer:D
```

## Stopping the Server

```bash
# Stop the service
adb shell am stopservice com.freeadbremote.remoteserver/.AppManagerService

# Or force stop the app
adb shell am force-stop com.freeadbremote.remoteserver
```

## API Endpoints

The server runs on **port 3000** by default and provides the following endpoints:

### Health Check

```http
GET /api/health
```

**Response:**
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

### List User Apps

```http
GET /api/apps/user
```

**Response:**
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

**Notes:**
- Only user-installed apps are returned (system apps are excluded)
- System apps are filtered using both flags and installation location
- Apps installed in `/data/app/` are considered user apps
- Apps in `/system/app/`, `/system/priv-app/`, etc. are excluded

## Project Structure

```
remoteServer/
├── src/main/
│   ├── java/com/freeadbremote/remoteserver/
│   │   ├── HttpAppManagerServer.java    # HTTP API server (NanoHTTPD)
│   │   ├── AppManagerService.java       # Foreground service wrapper
│   │   ├── AppManagerApplication.java   # Application class
│   │   ├── LauncherActivity.java        # Launcher activity
│   │   └── BootReceiver.java            # Boot completed receiver
│   ├── res/                              # Resources
│   └── AndroidManifest.xml
├── build.gradle
└── README.md
```

## Key Components

### HttpAppManagerServer

The main HTTP server implementation using NanoHTTPD:

- Handles HTTP requests and responses
- Provides REST API endpoints
- Filters system apps from listings
- Caches app information for performance

### AppManagerService

Foreground service that runs the HTTP server:

- Starts HttpAppManagerServer on port 3000
- Maintains service lifecycle
- Handles service start/stop commands

### AppManagerApplication

Application class that starts the service:

- Automatically starts service when app is launched
- Initializes application context

### LauncherActivity

Simple launcher activity:

- Used for manual service start
- Compatible with monkey and testing tools
- Immediately finishes after starting service

### BootReceiver

Broadcast receiver for boot completion:

- Automatically starts service on device boot
- Ensures server is always available after reboot

## System App Filtering

The server uses multiple methods to reliably filter out system apps:

1. **Flag Check**: Checks `FLAG_SYSTEM` and `FLAG_UPDATED_SYSTEM_APP` flags
2. **Location Check**: Verifies installation location
   - System apps: `/system/app/`, `/system/priv-app/`, `/system/product/app/`, `/vendor/app/`, `/product/app/`, `/system_ext/app/`
   - User apps: `/data/app/`
3. **Combined Filtering**: App is excluded if it matches ANY system app criteria

This ensures that only genuine user-installed applications are returned in API responses.

## Configuration

### Default Port

The server runs on port **3000** by default. To change:

1. Edit `AppManagerService.java`
2. Modify `DEFAULT_PORT` constant
3. Rebuild and reinstall

## Troubleshooting

### Server Not Starting

**Problem**: Service doesn't start after installation

**Solutions**:
- Verify installation as system/priv-app
- Check permissions are set correctly (644 for APK, 755 for directory)
- Try manual start: `adb shell am start -n com.freeadbremote.remoteserver/.LauncherActivity`
- Check logs: `adb logcat | grep AppManagerService`

### API Not Responding

**Problem**: HTTP requests to port 3000 fail

**Solutions**:
- Verify service is running: `adb shell dumpsys activity services | grep AppManagerService`
- Check firewall settings
- Verify port 3000 is not blocked
- Test locally: `curl http://127.0.0.1:3000/api/health` (from device shell)

### Apps Not Listing

**Problem**: API returns empty app list or missing apps

**Solutions**:
- Verify `QUERY_ALL_PACKAGES` permission is granted
- Check if installed as system/priv-app
- Review logcat for permission errors
- Verify app is not being filtered as system app incorrectly

### Permission Denied Errors

**Problem**: Permission denied when accessing package manager

**Solutions**:
- Ensure app is installed in `/system/priv-app/`
- Verify app has system UID (check with `adb shell ps | grep remoteserver`)
- Check `privapp-permissions.xml` if using custom ROM
- Reboot device after installation

## Building

### Debug Build

```bash
./gradlew :remoteServer:assembleDebug
```

### Release Build

```bash
./gradlew :remoteServer:assembleRelease
```

### Output Location

- Debug: `remoteServer/build/outputs/apk/debug/remoteServer-debug.apk`
- Release: `remoteServer/build/outputs/apk/release/remoteServer-release.apk`

## Dependencies

- **NanoHTTPD**: Lightweight HTTP server library
- **Gson**: JSON serialization/deserialization
- **Android SDK**: Standard Android libraries

## Permissions

Required permissions (declared in AndroidManifest.xml):

- `QUERY_ALL_PACKAGES`: Required for listing all packages (Android 11+)
- `INTERNET`: For HTTP server
- `ACCESS_NETWORK_STATE`: For network state checking
- `KILL_BACKGROUND_PROCESSES`: For app management
- `RECEIVE_BOOT_COMPLETED`: For auto-start on boot
- `FOREGROUND_SERVICE`: For foreground service
- `FOREGROUND_SERVICE_DATA_SYNC`: For data sync service type
- `POST_NOTIFICATIONS`: For service notifications

## Security Considerations

- The server runs on port 3000 and is accessible on the local network
- No authentication is implemented (consider adding for production use)
- Only user-installed apps are exposed (system apps are filtered)
- Runs as system user when installed as priv-app

## License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## Related Documentation

- [Main Application README](../README.md)
- [Service Start Guide](SERVICE_START.md)
