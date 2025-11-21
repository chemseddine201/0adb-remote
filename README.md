# Free ADB Remote

A free, open-source Android application for remote ADB control over WiFi. Control your Android device remotely using ADB commands with a user-friendly interface.

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Android](https://img.shields.io/badge/platform-Android-green.svg)
![Min SDK](https://img.shields.io/badge/min%20SDK-26-orange.svg)

## Features

- 🔐 **One-Time Trust**: Establishes trust on first connection, no prompts on subsequent connections
- 🔄 **Auto-Reconnect**: Automatically reconnects when connection is lost
- 📱 **Remote Control**: Full remote control of Android device via ADB commands
- 🎮 **Device Control**: Send key events, control media, navigate device
- 📊 **Process Manager**: View and manage running applications
- 🔌 **Persistent Connection**: Maintains connection via foreground service
- 🚀 **Native Implementation**: Pure Java ADB protocol implementation - no external dependencies

## Screenshots

*Add screenshots here*

## Installation

### Build from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/FreeAdbRemote.git
   cd FreeAdbRemote
   ```

2. Build the APK:
   ```bash
   ./gradlew assembleDebug
   ```

3. Install on your device:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Download APK

Download the latest release APK from the [Releases](https://github.com/yourusername/FreeAdbRemote/releases) page.

## Usage

### Initial Setup

1. **Enable ADB over WiFi** on your Android device:
   ```bash
   adb tcpip 5555
   adb connect <device-ip>:5555
   ```

2. **Open the app** on your Android device

3. **Enter connection details**:
   - Host: Your device's IP address (e.g., `192.168.1.100`)
   - Port: ADB port (default: `5555`)

4. **Connect**: Tap "Connect" button
   - On first connection, accept the authorization prompt on your device
   - Subsequent connections will be automatic

### Features

#### Device Control
- **Navigation**: Home, Back, Recent Apps
- **Media Control**: Play/Pause, Next/Previous, Volume control
- **Power Control**: Restart, Power off
- **Channel Control**: Channel up/down for TV boxes

#### Process Manager
- **View Running Apps**: See all opened user-installed applications
- **App Names**: Display friendly app names instead of package names
- **App Control**: Open, Force Stop, Clear Data, Uninstall apps
- **Auto-Refresh**: List automatically updates after app actions

#### Keyboard & Number Pad
- **Virtual Keyboard**: Full keyboard input
- **Number Pad**: Quick number input for TV boxes

## Project Structure

```
FreeAdbRemote/
├── app/
│   ├── src/main/
│   │   ├── java/com/freeadbremote/
│   │   │   ├── AdbClient.java              # Native ADB protocol client
│   │   │   ├── AdbServerManager.java       # ADB server management
│   │   │   ├── AdbConnectionManager.java   # Connection manager
│   │   │   ├── AdbConnectionService.java   # Foreground service
│   │   │   ├── AdbKeyManager.java         # RSA key management
│   │   │   ├── MainActivity.java          # Main connection screen
│   │   │   ├── ControlActivity.java       # Device control interface
│   │   │   ├── ProcessManagerActivity.java # Process/app manager
│   │   │   ├── KeyboardActivity.java      # Virtual keyboard
│   │   │   └── NumberPadActivity.java     # Number pad
│   │   ├── res/                            # Resources (layouts, drawables)
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── gradle/
├── build.gradle
├── settings.gradle
├── README.md
└── LICENSE
```

## How It Works

### ADB Protocol Implementation

The app implements the full ADB protocol in pure Java:

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

1. User enters host and port
2. App connects via TCP socket to ADB server
3. ADB handshake (CNXN message)
4. Authentication (AUTH with RSA public key)
5. Open shell stream
6. Commands are queued and sent via WRTE messages
7. Responses received and parsed
8. Connection maintained via foreground service

## Requirements

- **Android Device**: Android 8.0 (API 26) or higher
- **ADB over WiFi**: Device must support ADB over network
- **Network**: Both devices on same network (or port forwarding)

## Permissions

The app requires the following permissions:

- `FOREGROUND_SERVICE_CONNECTED_DEVICE`: For foreground service
- `POST_NOTIFICATIONS`: For connection status notifications
- `CHANGE_NETWORK_STATE`: For network management
- `CHANGE_WIFI_STATE`: For WiFi management

## Building

### Prerequisites

- Android Studio Arctic Fox or later
- JDK 8 or higher
- Android SDK (API 26+)

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## Development

### Key Components

- **AdbClient**: Low-level ADB protocol implementation
- **AdbServerManager**: High-level ADB server management
- **AdbConnectionManager**: Singleton for connection sharing
- **AdbConnectionService**: Foreground service for persistent connection

### Architecture

- **Singleton Pattern**: AdbConnectionManager ensures single connection instance
- **Service Pattern**: Foreground service maintains connection lifecycle
- **Callback Pattern**: Async operations use callbacks for results

## Troubleshooting

### Connection Issues

- **"Not connected"**: Ensure ADB over WiFi is enabled on device
- **"Authorization failed"**: Accept the prompt on device on first connection
- **"Connection timeout"**: Check firewall settings and network connectivity

### Build Issues

- **Gradle sync failed**: Update Android Gradle Plugin
- **Build errors**: Clean and rebuild project

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Based on ADB protocol specification
- Inspired by open-source ADB implementations
- Uses Material Design components

## Disclaimer

This software is provided "as is" without warranty of any kind. Use at your own risk. The authors are not responsible for any damage or data loss caused by using this software.

## Support

For issues, questions, or contributions, please open an issue on [GitHub](https://github.com/yourusername/FreeAdbRemote/issues).

---

Made with ❤️ for the open-source community
