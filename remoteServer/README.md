# Remote Server Android Application

This is the Android application module for the Remote Server.

## Structure

- `src/main/java/com/freeadbremote/remoteserver/` - Java source code
- `src/main/res/` - Android resources (layouts, values, drawables, etc.)
- `src/main/AndroidManifest.xml` - Application manifest

## Build

To build the APK:
```bash
./gradlew :remoteServer:assembleDebug
```

The output APK will be at:
`remoteServer/build/outputs/apk/debug/remoteServer-debug.apk`

## Notes

- Launcher icons need to be added to the `mipmap-*` directories
- Add your application code in the `MainActivity` or create new activities/services as needed

