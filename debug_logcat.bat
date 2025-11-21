@echo off
REM Debug script for Free ADB Remote
REM Captures logcat output to file for debugging crashes

echo ========================================
echo Free ADB Remote - Logcat Debug Tool
echo ========================================
echo.

REM Check if device is connected
echo Checking for connected devices...
adb devices
echo.

REM Clear old logs
echo Clearing old logcat buffer...
adb logcat -c
echo.

REM Start capturing logs
echo Starting log capture...
echo Logs will be saved to: adbremote_debug.log
echo Press Ctrl+C to stop capturing
echo.
echo Filtering: Errors, Warnings, and FreeAdbRemote logs
echo.

adb logcat -v time *:E *:W FreeAdbRemote:* > adbremote_debug.log

pause

