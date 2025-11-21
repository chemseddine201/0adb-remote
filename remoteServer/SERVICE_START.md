# كيفية بدء AppManagerService يدوياً

## الطرق المتاحة لبدء السيرفر

### 1. عبر Activity (لـ monkey وأدوات الاختبار)
```bash
# بدء التطبيق عبر monkey
adb -s 192.168.1.128:5555 shell  monkey -p com.freeadbremote.remoteserver -c android.intent.category.LAUNCHER 1

# أو بدء Activity مباشرة
adb -s 192.168.1.128:5555 shell  am start -n com.freeadbremote.remoteserver/.LauncherActivity
```

### 2. بدء Service مباشرة عبر ADB (Android 7.1 أو أقل فقط)
```bash
# ⚠️ في Android 8.0+ هذا لن يعمل بسبب قيود foreground service
# استخدم الطريقة 1 بدلاً من ذلك
adb -s 192.168.1.128:5555 shell  am startservice -n com.freeadbremote.remoteserver/.AppManagerService -a com.freeadbremote.remoteserver.START_SERVER
```

### 2b. بدء Activity (الطريقة الموصى بها لـ Android 8.0+)
```bash
# بدء Activity التي ستبدأ Service تلقائياً
adb -s 192.168.1.128:5555 shell  am start -n com.freeadbremote.remoteserver/.LauncherActivity
```

### 3. إيقاف Service
```bash
adb -s 192.168.1.128:5555 shell  am stopservice com.freeadbremote.remoteserver/.AppManagerService
```

### 4. التحقق من حالة Service
```bash
# التحقق من Service قيد التشغيل
adb -s 192.168.1.128:5555 shell  dumpsys activity services | grep AppManagerService

# أو التحقق من السيرفر HTTP
curl http://<device-ip>:3000/api/health
```

## ملاحظات

- **LauncherActivity**: Activity بسيطة تبدأ Service وتنتهي فوراً - مفيدة لـ monkey
- **AppManagerService**: Service يبدأ HttpAppManagerServer على المنفذ 3000
- **BootReceiver**: يبدأ السيرفر تلقائياً بعد إعادة التشغيل
- **AppManagerApplication**: يبدأ السيرفر عند بدء التطبيق (إذا تم تشغيله)

## المنفذ الافتراضي
- **Port**: 3000
- يمكن تغييره في `AppManagerService.DEFAULT_PORT`

