# PowerShell script for capturing logcat
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Free ADB Remote - Logcat Debug Tool" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check if device is connected
Write-Host "Checking for connected devices..." -ForegroundColor Yellow
adb devices
Write-Host ""

# Clear old logs
Write-Host "Clearing old logcat buffer..." -ForegroundColor Yellow
adb logcat -c
Write-Host ""

# Start capturing logs
Write-Host "Starting log capture..." -ForegroundColor Green
Write-Host "Logs will be saved to: adbremote_debug.log" -ForegroundColor Green
Write-Host "Press Ctrl+C to stop capturing" -ForegroundColor Yellow
Write-Host ""
Write-Host "Filtering: Errors, Warnings, and FreeAdbRemote logs" -ForegroundColor Cyan
Write-Host ""

# Capture logs with filtering
adb logcat -v time *:E *:W FreeAdbRemote:* | Tee-Object -FilePath adbremote_debug.log

