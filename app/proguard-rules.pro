# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep ADB client classes
-keep class com.freeadbremote.AdbClient { *; }
-keep class com.freeadbremote.AdbServerManager { *; }
-keep class ** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

