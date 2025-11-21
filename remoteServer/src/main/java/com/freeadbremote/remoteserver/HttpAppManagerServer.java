package com.freeadbremote.remoteserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import fi.iki.elonen.NanoHTTPD;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Privileged HTTP App Manager Server
 * Runs as system/priv-app and uses real Context APIs
 */
public class HttpAppManagerServer extends NanoHTTPD {

    private static final String TAG = "HttpAppManagerServer";

    // Android core
    private final Context context;
    private final PackageManager pm;
    private final ActivityManager am;

    // Cache
    private final Map<String, AppInfo> appInfoCache = new ConcurrentHashMap<>();
    private final Map<String, String> iconCache = new ConcurrentHashMap<>();

    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static class AppInfo {
        String packageName;
        String name;
        String versionName;
        long versionCode;
        boolean isSystem;
        String iconBase64;
        boolean running;
    }

    public HttpAppManagerServer(Context ctx, int port) {
        super(port);
        this.context = ctx.getApplicationContext();
        this.pm = context.getPackageManager();
        this.am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        Log.i(TAG, "Server constructed. pm=" + (pm != null) + ", am=" + (am != null));
    }

    // ================= HTTP ROUTING =================

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        String path = uri.split("\\?")[0];

        Log.i(TAG, "HTTP " + method + " " + path);

        try {
            if (method == Method.OPTIONS) {
                Response r = newFixedLengthResponse(Response.Status.OK, "application/json", "");
                return addCors(r);
            }

            if ("/api/health".equals(path)) {
                return addCors(handleHealth());
            }

            if ("/api/apps/user".equals(path) && method == Method.GET) {
                return addCors(handleListUserApps());
            }

            return addCors(jsonError(Response.Status.NOT_FOUND,
                    "endpoint_not_found", "Unknown endpoint: " + path));

        } catch (Exception e) {
            Log.e(TAG, "Error in serve()", e);
            return addCors(jsonError(Response.Status.INTERNAL_ERROR,
                    "internal_error", e.getMessage()));
        }
    }

    private Response addCors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        r.addHeader("Content-Type", "application/json; charset=utf-8");
        return r;
    }

    // ================= HANDLERS =================

    private Response handleHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("timestamp", System.currentTimeMillis());

        Map<String, Object> services = new LinkedHashMap<>();
        services.put("context", context != null);
        services.put("pm", pm != null);
        services.put("am", am != null);
        out.put("services", services);

        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("appInfo", appInfoCache.size());
        cache.put("icons", iconCache.size());
        out.put("cache", cache);

        return jsonResponse(out);
    }

    private Response handleListUserApps() {
        try {
            List<Map<String, Object>> apps = listInstalledApps();
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("count", apps.size());
            res.put("apps", apps);
            return jsonResponse(res);
        } catch (Exception e) {
            Log.e(TAG, "handleListUserApps failed", e);
            return jsonError(Response.Status.INTERNAL_ERROR,
                    "list_failed", e.getMessage());
        }
    }


    // ================= CORE LOGIC =================

    /**
     * List only user-installed applications (excludes all system apps)
     * 
     * Includes ONLY:
     * - Regular user apps (FLAG_SYSTEM = false AND FLAG_UPDATED_SYSTEM_APP = false)
     * 
     * Excludes:
     * - All system apps (FLAG_SYSTEM = true)
     * - All updated system apps (FLAG_UPDATED_SYSTEM_APP = true)
     * - All Google services and framework apps
     */
    private List<Map<String, Object>> listInstalledApps() {
        List<Map<String, Object>> list = new ArrayList<>();
        if (pm == null) return list;

        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        if (apps == null) return list;

        // Get own package name to exclude it from the list
        String ownPackageName = context.getPackageName();
        
        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null) continue;
            
            // Exclude the remoteServer package itself
            if (ownPackageName != null && ownPackageName.equals(ai.packageName)) {
                continue;
            }

            // Check if it's a system app using multiple reliable methods
            // Method 1: Check flags
            boolean isSystemAppByFlag = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isUpdatedSystemApp = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            
            // Method 2: Check installation location (more reliable)
            // System apps are installed in system directories, not /data/app/
            boolean isSystemAppByLocation = false;
            if (ai.sourceDir != null) {
                String sourceDir = ai.sourceDir;
                // System apps are in: /system/app/, /system/priv-app/, /system/product/app/, 
                // /vendor/app/, /product/app/, /system_ext/app/
                // User apps are in: /data/app/
                isSystemAppByLocation = !sourceDir.startsWith("/data/app/");
            }
            
            // Exclude if it's a system app by ANY method
            // Only include pure user-installed apps (installed in /data/app/)
            if (isSystemAppByFlag || isUpdatedSystemApp || isSystemAppByLocation) {
                continue;
            }

            AppInfo info = buildAppInfoFromApplicationInfo(ai, false);
            if (info == null) continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("package", info.packageName);
            m.put("name", info.name);
            m.put("versionName", info.versionName);
            m.put("versionCode", info.versionCode);
            m.put("isSystem", false); // Always false since we exclude system apps
            m.put("running", isAppRunning(info.packageName));
            m.put("icon", null);
            list.add(m);
        }

        Log.i(TAG, "listInstalledApps: Returning " + list.size() + " user-installed apps (system apps excluded)");
        return list;
    }


    private AppInfo buildAppInfoFromApplicationInfo(ApplicationInfo ai, boolean includeIcon) {
        if (ai == null || pm == null) return null;

        AppInfo info = new AppInfo();
        info.packageName = ai.packageName;

        // Since we only include user apps, isSystem is always false
        boolean isSystem = ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                || ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
        info.isSystem = isSystem;

        // Label
        try {
            CharSequence label = ai.loadLabel(pm);
            if (label != null && label.length() > 0) {
                info.name = label.toString();
            } else {
                info.name = ai.packageName;
            }
        } catch (Throwable t) {
            info.name = ai.packageName;
        }

        // Version
        try {
            PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
            info.versionName = pi.versionName;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.versionCode = pi.getLongVersionCode();
            } else {
                info.versionCode = pi.versionCode;
            }
        } catch (Throwable t) {
            info.versionName = null;
            info.versionCode = 0;
        }

        if (includeIcon) {
            info.iconBase64 = loadAppIconBase64(ai.packageName);
        }

        return info;
    }

    private String loadAppIconBase64(String packageName) {
        String cached = iconCache.get(packageName);
        if (cached != null) return cached;

        if (pm == null) return null;

        try {
            Drawable d = pm.getApplicationIcon(packageName);
            if (d == null) return null;

            Bitmap bitmap;
            if (d instanceof BitmapDrawable) {
                bitmap = ((BitmapDrawable) d).getBitmap();
            } else {
                int width = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 96;
                int height = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 96;
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                d.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                d.draw(canvas);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] bytes = baos.toByteArray();
            String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            iconCache.put(packageName, b64);
            return b64;
        } catch (Throwable t) {
            Log.e(TAG, "Icon load failed for " + packageName, t);
            return null;
        }
    }

    private boolean isAppRunning(String packageName) {
        if (am == null) return false;
        List<RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) return false;
        for (RunningAppProcessInfo p : procs) {
            if (p.pkgList == null) continue;
            for (String pkg : p.pkgList) {
                if (packageName.equals(pkg)) return true;
            }
        }
        return false;
    }


    // ================= JSON HELPERS =================

    private Response jsonResponse(Object data) {
        return newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                gson.toJson(data)
        );
    }

    private Response jsonError(Response.Status status, String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("success", false);
        err.put("error", code);
        err.put("message", message);
        err.put("timestamp", System.currentTimeMillis());
        return newFixedLengthResponse(
                status,
                "application/json; charset=utf-8",
                gson.toJson(err)
        );
    }
}


