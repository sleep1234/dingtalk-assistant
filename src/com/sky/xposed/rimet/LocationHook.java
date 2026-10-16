package com.sky.xposed.rimet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;

/**
 * 钉钉虚拟定位 Hook (v6) — 针对钉钉 9.0.2.2 逆向分析后改进
 *
 * 钉钉定位链路（逆向分析结果）：
 *   业务层 → LocationProxy(门面) → AMapLocationClient(高德SDK)
 *         → 阿里安全AOP插桩监控 → 定位结果通过回调分发
 *
 * 坐标/开关通过公共文件 /data/local/tmp/rimet_location.txt 传递：
 *   模块界面通过 root 写入，钉钉进程直接读取，支持 mtime 热重载无需重启
 */
public class LocationHook {

    private static final String TAG = "RimetHook-Location";

    private static final double DEFAULT_LAT = 28.6557;
    private static final double DEFAULT_LNG = 121.4200;

    private static final String KEY_LAT_GCJ = "lat_gcj";
    private static final String KEY_LNG_GCJ = "lng_gcj";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LNG = "lng";
    private static final String KEY_ENABLED = "enabled";

    private static final long CACHE_MS = 500L;

    private static boolean sEnabledCache = true;
    private static long sEnabledCacheTs;
    private static double sCachedLatGcj = DEFAULT_LAT;
    private static double sCachedLngGcj = DEFAULT_LNG;
    private static double sCachedLat = DEFAULT_LAT;
    private static double sCachedLng = DEFAULT_LNG;
    private static long sCoordCacheTs;
    private static final Map<android.location.LocationListener, android.location.LocationListener>
        sSystemListenerProxies = Collections.synchronizedMap(new WeakHashMap<android.location.LocationListener, android.location.LocationListener>());

    private static XSharedPreferences sPrefs;
    private static java.util.Map<String, String> sFilePrefs;
    private static long sFileLastModified;
    private static String sFileContentSnapshot = "";

    // 公共定位文件，放在 /data/local/tmp 下避免 Android 14 Scoped Storage 限制
    private static final String PUBLIC_FILE = "/data/local/tmp/rimet_location.txt";

    public static void hook(ClassLoader cl) {
        try {
            sPrefs = new XSharedPreferences("com.sky.xposed.rimet", "location");
            sPrefs.reload();
            sFilePrefs = loadFilePrefs();
            refreshCoords();
            Log.i(TAG, "初始坐标 gcj=" + sCachedLatGcj + "," + sCachedLngGcj);

            hookAMapLocationResult(cl);
            hookAMapClient(cl);
            hookLocationProxy(cl);
            hookDtLocationFactories(cl);
            hookLocationService(cl);
            hookMockFlags(cl);
            hookSystemLocationManager(cl);
            hookLastKnownLocation(cl);

            Log.i(TAG, "虚拟定位 Hook v6 安装成功 [钉钉 9.0.2.2 适配]");
        } catch (Throwable t) {
            Log.e(TAG, "安装虚拟定位 Hook 失败", t);
        }
    }

    // ============================================================
    // 1. hook AMapLocation.getLatitude/getLongitude（结果层兜底）
    // ============================================================
    private static void hookAMapLocationResult(ClassLoader cl) {
        try {
            Class<?> locCls = XposedHelpers.findClass("com.amap.api.location.AMapLocation", cl);

            XposedHelpers.findAndHookMethod(locCls, "getLatitude",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabled()) return;
                        refreshCoords();
                        param.setResult(sCachedLatGcj);
                    }
                });

            XposedHelpers.findAndHookMethod(locCls, "getLongitude",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabled()) return;
                        refreshCoords();
                        param.setResult(sCachedLngGcj);
                    }
                });

            Log.i(TAG, "hook AMapLocation.getLatitude/getLongitude 成功");
        } catch (Throwable t) {
            Log.w(TAG, "hook AMapLocation 失败: " + t.getMessage());
        }
    }

    // ============================================================
    // 2. hook AMapLocationClient
    // ============================================================
    private static void hookAMapClient(ClassLoader cl) {
        try {
            Class<?> clientCls = XposedHelpers.findClass("com.amap.api.location.AMapLocationClient", cl);

            // getLastKnownLocation → 返回 null，逼迫重新定位
            XposedHelpers.findAndHookMethod(clientCls, "getLastKnownLocation",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isEnabled()) param.setResult(null);
                    }
                });

            // setLocationListener → 代理 listener
            XposedHelpers.findAndHookMethod(clientCls, "setLocationListener",
                "com.amap.api.location.AMapLocationListener",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object listener = param.args[0];
                        if (listener != null && !Proxy.isProxyClass(listener.getClass())) {
                            param.args[0] = proxyAMapListener(listener);
                        }
                    }
                });

            Log.i(TAG, "hook AMapLocationClient 成功");
        } catch (Throwable t) {
            Log.w(TAG, "hook AMapLocationClient 失败: " + t.getMessage());
        }
    }

    // ============================================================
    // 3. hook 钉钉 LocationProxy 门面（顶层定位回调入口）
    // ============================================================
    private static void hookLocationProxy(ClassLoader cl) {
        try {
            Class<?> proxyCls = XposedHelpers.findClass(
                "com.alibaba.android.dingtalkbase.amap.LocationProxy", cl);

            XposedHelpers.findAndHookMethod(proxyCls, "onLocationChanged",
                "com.amap.api.location.AMapLocation",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabled()) return;
                        refreshCoords();
                        Object loc = param.args[0];
                        if (loc != null) {
                            try {
                                XposedHelpers.callMethod(loc, "setLatitude", sCachedLatGcj);
                                XposedHelpers.callMethod(loc, "setLongitude", sCachedLngGcj);
                            } catch (Throwable ignored) {}
                        }
                    }
                });

            Log.i(TAG, "hook LocationProxy.onLocationChanged 成功");
        } catch (Throwable t) {
            Log.w(TAG, "hook LocationProxy 失败: " + t.getMessage());
        }
    }

    // ============================================================
    // 4. hook 系统 LocationManager.requestLocationUpdates
    // ============================================================
    private static void hookSystemLocationManager(ClassLoader cl) {
        try {
            Class<?> mgrCls = XposedHelpers.findClass("android.location.LocationManager", cl);

            XposedHelpers.findAndHookMethod(mgrCls, "requestLocationUpdates",
                String.class, long.class, float.class,
                "android.location.LocationListener",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object listener = param.args[3];
                        if (listener != null && !Proxy.isProxyClass(listener.getClass())
                            && listener instanceof android.location.LocationListener) {
                            param.args[3] = wrapSystemListener(
                                (android.location.LocationListener) listener);
                        }
                    }
                });

            // 带 Looper 的重载
            try {
                XposedHelpers.findAndHookMethod(mgrCls, "requestLocationUpdates",
                    String.class, long.class, float.class,
                    "android.location.LocationListener",
                    "android.os.Looper",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object listener = param.args[3];
                            if (listener != null && !Proxy.isProxyClass(listener.getClass())
                                && listener instanceof android.location.LocationListener) {
                                param.args[3] = wrapSystemListener(
                                    (android.location.LocationListener) listener);
                            }
                        }
                    });
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(mgrCls, "removeUpdates",
                    "android.location.LocationListener",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object listener = param.args[0];
                            if (listener instanceof android.location.LocationListener) {
                                android.location.LocationListener proxy = sSystemListenerProxies.get(
                                    (android.location.LocationListener) listener);
                                if (proxy != null) param.args[0] = proxy;
                            }
                        }
                    });
            } catch (Throwable ignored) {}

            Log.i(TAG, "系统 LocationManager hook 成功");
        } catch (Throwable t) {
            Log.w(TAG, "系统 LocationManager hook 失败: " + t.getMessage());
        }
    }

    // ============================================================
    // 5. hook LocationManager.getLastKnownLocation（防缓存绕过）
    // ============================================================
    private static void hookLastKnownLocation(ClassLoader cl) {
        try {
            Class<?> mgrCls = XposedHelpers.findClass("android.location.LocationManager", cl);
            XposedHelpers.findAndHookMethod(mgrCls, "getLastKnownLocation",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isEnabled()) param.setResult(null);
                    }
                });
            Log.i(TAG, "hook LocationManager.getLastKnownLocation 成功");
        } catch (Throwable t) {
            Log.w(TAG, "hook getLastKnownLocation 失败: " + t.getMessage());
        }
    }

    // ============================================================
    // 钉钉 9.x 定位服务和 DtLocation 结果层
    // ============================================================
    private static void hookDtLocationFactories(ClassLoader cl) {
        try {
            Class<?> dtLocation = XposedHelpers.findClass(
                "com.alibaba.android.rimet.model.DtLocation", cl);
            XC_MethodHook resultHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isEnabled() && param.getResult() != null) {
                        patchLocation(param.getResult(), true);
                        clearMockFlags(param.getResult());
                    }
                }
            };
            XposedHelpers.findAndHookMethod(dtLocation, "fromAMapLocation",
                "com.amap.api.location.AMapLocation",
                "com.alibaba.android.rimet.LocationService$ServiceType",
                resultHook);
            XposedHelpers.findAndHookMethod(dtLocation, "fromLocation",
                "android.location.Location",
                "com.alibaba.android.rimet.LocationService$ServiceType",
                resultHook);
            Log.i(TAG, "hook DtLocation.fromAMapLocation/fromLocation 成功");
        } catch (Throwable t) {
            Log.w(TAG, "hook DtLocation 工厂失败: " + t.getMessage());
        }
    }

    private static void hookLocationService(ClassLoader cl) {
        boolean hooked = false;
        try {
            Class<?> service = XposedHelpers.findClass(
                "com.alibaba.android.rimet.LocationService", cl);
            XC_MethodHook directCallbackHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 4 && param.args[4] != null
                            && !Proxy.isProxyClass(param.args[4].getClass())) {
                        param.args[4] = proxyDtLocationCallback(param.args[4]);
                    }
                }
            };
            for (String method : LocationHookPolicy.DIRECT_SERVICE_METHODS) {
                try {
                    XposedHelpers.findAndHookMethod(service, method,
                        "android.content.Context", String.class,
                        "com.alibaba.android.rimet.LocationService$ServiceType",
                        "defpackage.a7h",
                        "com.alibaba.android.rimet.DtLocationCallback", directCallbackHook);
                    hooked = true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "hook LocationService 门面失败: " + t.getMessage());
        }

        for (String className : LocationHookPolicy.IMPLEMENTATION_CLASSES) {
            try {
                Class<?> implementation = XposedHelpers.findClass(className, cl);
                for (String method : LocationHookPolicy.IMPLEMENTATION_METHODS) {
                    if (!XposedBridge.hookAllMethods(implementation, method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 3 && param.args[3] != null
                                    && !Proxy.isProxyClass(param.args[3].getClass())) {
                                param.args[3] = proxyDtLocationCallback(param.args[3]);
                            }
                        }
                    }).isEmpty()) {
                        hooked = true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (hooked) {
            Log.i(TAG, "hook LocationService 回调成功");
        } else {
            Log.w(TAG, "未找到 LocationService 回调方法");
        }
    }

    private static Object proxyDtLocationCallback(final Object realCallback) {
        try {
            Class<?> callback = XposedHelpers.findClass(
                "com.alibaba.android.rimet.DtLocationCallback",
                realCallback.getClass().getClassLoader());
            return Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("onGetLocation".equals(method.getName())
                                && args != null && args.length > 0 && isEnabled()) {
                            patchLocation(args[0], true);
                            clearMockFlags(args[0]);
                        }
                        return invokeListener(method, realCallback, args);
                    }
                });
        } catch (Throwable t) {
            return realCallback;
        }
    }

    private static void hookMockFlags(ClassLoader cl) {
        try {
            Class<?> amapLocation = XposedHelpers.findClass("com.amap.api.location.AMapLocation", cl);
            XposedHelpers.findAndHookMethod(amapLocation, "isMock", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (isEnabled()) param.setResult(false);
                }
            });
            XposedHelpers.findAndHookMethod(amapLocation, "setMock", boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled()) param.args[0] = false;
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "hook AMap mock 标记失败: " + t.getMessage());
        }
        try {
            Class<?> location = XposedHelpers.findClass("android.location.Location", cl);
            XposedHelpers.findAndHookMethod(location, "isFromMockProvider", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (isEnabled()) param.setResult(false);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "hook Location.isFromMockProvider 失败: " + t.getMessage());
        }
        try {
            Class<?> dtLocation = XposedHelpers.findClass(
                "com.alibaba.android.rimet.model.DtLocation", cl);
            for (String method : new String[]{"isFromMock", "isFromMockProvider", "isMock"}) {
                try {
                    XposedHelpers.findAndHookMethod(dtLocation, method, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (isEnabled()) param.setResult(false);
                        }
                    });
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "hook DtLocation mock 标记失败: " + t.getMessage());
        }
    }

    private static void clearMockFlags(Object location) {
        if (location == null) return;
        try { XposedHelpers.callMethod(location, "setFromMock", false); } catch (Throwable ignored) {}
        try { XposedHelpers.callMethod(location, "setMock", false); } catch (Throwable ignored) {}
    }

    // ============================================================
    // Listener 代理
    // ============================================================
    private static Object proxyAMapListener(final Object realListener) {
        try {
            Class<?> itf = XposedHelpers.findClass(
                "com.amap.api.location.AMapLocationListener",
                realListener.getClass().getClassLoader());
            return Proxy.newProxyInstance(
                realListener.getClass().getClassLoader(),
                new Class<?>[]{itf},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("onLocationChanged".equals(method.getName())
                            && args != null && args.length > 0) {
                            patchLocation(args[0], true);
                        }
                        return invokeListener(method, realListener, args);
                    }
                });
        } catch (Throwable t) {
            return realListener;
        }
    }

    private static android.location.LocationListener wrapSystemListener(
            final android.location.LocationListener real) {
        android.location.LocationListener existing = sSystemListenerProxies.get(real);
        if (existing != null) return existing;
        android.location.LocationListener proxy = (android.location.LocationListener) Proxy.newProxyInstance(
            real.getClass().getClassLoader(),
            new Class<?>[]{android.location.LocationListener.class},
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    if ("onLocationChanged".equals(method.getName())
                        && args != null && args.length > 0) {
                        patchLocation(args[0], false);
                    }
                    return invokeListener(method, real, args);
                }
            });
        sSystemListenerProxies.put(real, proxy);
        return proxy;
    }

    // ============================================================
    // 坐标修改
    // ============================================================
    private static void patchLocation(Object loc, final boolean gcj02) {
        if (loc == null) return;
        if (!isEnabled()) return;
        refreshCoords();
        double lat = gcj02 ? sCachedLatGcj : sCachedLat;
        double lng = gcj02 ? sCachedLngGcj : sCachedLng;
        try {
            XposedHelpers.callMethod(loc, "setLatitude", lat);
            XposedHelpers.callMethod(loc, "setLongitude", lng);
            Log.d(TAG, "坐标已改[" + (gcj02 ? "GCJ" : "WGS") + "]: lat=" + lat + " lng=" + lng);
        } catch (Throwable t) {
            trySetField(loc, lat, lng);
        }
    }

    private static void trySetField(Object obj, double lat, double lng) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    String name = f.getName();
                    if ("double".equals(f.getType().getName())) {
                        if (name.equals("f") || name.equals("h")
                            || name.contains("lat") || name.contains("Lat")) {
                            f.setDouble(obj, lat);
                        } else if (name.equals("g") || name.equals("i")
                            || name.contains("lon") || name.contains("lng")
                            || name.contains("Lon") || name.contains("Lng")) {
                            f.setDouble(obj, lng);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    private static Object invokeListener(Method method, Object listener, Object[] args) throws Throwable {
        try {
            return method.invoke(listener, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================
    private static boolean isEnabled() {
        reloadIfChanged();
        long now = System.currentTimeMillis();
        if (now - sEnabledCacheTs < CACHE_MS) return sEnabledCache;
        // 1) 公共文件 /data/local/tmp/rimet_location.txt
        if (sFilePrefs != null && sFilePrefs.containsKey(KEY_ENABLED)) {
            String v = sFilePrefs.get(KEY_ENABLED);
            sEnabledCache = !"0".equals(v) && !"false".equalsIgnoreCase(v);
            sEnabledCacheTs = now;
            return sEnabledCache;
        }
        // 2) XSharedPreferences
        try {
            if (sPrefs != null) {
                sPrefs.reload();
                if (sPrefs.contains(KEY_ENABLED)) {
                    try {
                        sEnabledCache = sPrefs.getBoolean(KEY_ENABLED, true);
                    } catch (Throwable t) {
                        String v = sPrefs.getString(KEY_ENABLED, "1");
                        sEnabledCache = !"0".equals(v) && !"false".equalsIgnoreCase(v);
                    }
                } else {
                    sEnabledCache = true;
                }
            }
        } catch (Throwable t) {
            sEnabledCache = true;
        }
        sEnabledCacheTs = now;
        return sEnabledCache;
    }

    private static void refreshCoords() {
        reloadIfChanged();
        long now = System.currentTimeMillis();
        if (now - sCoordCacheTs < CACHE_MS) return;
        sCachedLatGcj = getDouble(KEY_LAT_GCJ, DEFAULT_LAT);
        sCachedLngGcj = getDouble(KEY_LNG_GCJ, DEFAULT_LNG);
        sCachedLat = getDouble(KEY_LAT, DEFAULT_LAT);
        sCachedLng = getDouble(KEY_LNG, DEFAULT_LNG);
        sCoordCacheTs = now;
    }

    private static double getDouble(String key, double def) {
        // 1) 公共文件
        if (sFilePrefs != null && sFilePrefs.containsKey(key)) {
            try { return Double.parseDouble(sFilePrefs.get(key)); } catch (Throwable ignored) {}
        }
        // 2) XSharedPreferences
        try {
            if (sPrefs != null) {
                sPrefs.reload();
                float v = sPrefs.getFloat(key, Float.NaN);
                if (!Float.isNaN(v)) return v;
            }
        } catch (Throwable ignored) {}
        // 3) GCJ 键缺失时退回 WGS 键
        if (key.equals(KEY_LAT_GCJ) || key.equals(KEY_LNG_GCJ)) {
            String alt = key.replace("_gcj", "");
            if (sFilePrefs != null && sFilePrefs.containsKey(alt)) {
                try { return Double.parseDouble(sFilePrefs.get(alt)); } catch (Throwable ignored) {}
            }
            try {
                if (sPrefs != null) {
                    float v2 = sPrefs.getFloat(alt, Float.NaN);
                    if (!Float.isNaN(v2)) return v2;
                }
            } catch (Throwable ignored) {}
        }
        return def;
    }

    private static java.util.Map<String, String> loadFilePrefs() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        try {
            java.io.File f = new java.io.File(PUBLIC_FILE);
            if (f.canRead()) {
                String content = readFileContent(f);
                if (content == null) {
                    Log.w(TAG, "公共定位配置读取失败，保留现有配置");
                    return m;
                }
                sFileLastModified = f.lastModified();
                sFileContentSnapshot = content;
                m = parseFileContent(content);
                Log.i(TAG, "公共定位配置已加载: " + m);
            } else {
                sFileLastModified = 0L;
                sFileContentSnapshot = "";
                Log.w(TAG, PUBLIC_FILE + " 不可读，使用默认坐标");
            }
        } catch (Throwable t) {
            Log.w(TAG, "读公共定位配置失败: " + t.getMessage());
        }
        return m;
    }

    /** 检查公共文件是否被修改，若是则重新加载（实现热重载，无需重启钉钉） */
    private static void reloadIfChanged() {
        try {
            java.io.File f = new java.io.File(PUBLIC_FILE);
            if (!f.canRead()) {
                if (sFilePrefs != null && !sFilePrefs.isEmpty()) {
                    sFilePrefs = new java.util.HashMap<>();
                    sFileLastModified = 0L;
                    sFileContentSnapshot = "";
                    sCoordCacheTs = 0L;
                    sEnabledCacheTs = 0L;
                    Log.w(TAG, "公共定位配置不可读，已清除旧缓存");
                }
                return;
            }
            // 用 mtime + 内容双保险：mtime 可能因秒级精度在快速连续切换时相同，
            // 因此同时对比内容快照，确保任何变化都能被检测到
            long mtime = f.lastModified();
            String snapshot = readFileContent(f);
            if (snapshot == null) return;
            if (mtime != sFileLastModified || !snapshot.equals(sFileContentSnapshot)) {
                sFilePrefs = parseFileContent(snapshot);
                sFileLastModified = mtime;
                sFileContentSnapshot = snapshot;
                sCoordCacheTs = 0;  // 强制刷新坐标缓存
                sEnabledCacheTs = 0;  // 强制刷新开关缓存
                Log.i(TAG, "检测到定位配置变化，已热重载: " + sFilePrefs);
            }
        } catch (Throwable ignored) {}
    }

    private static String readFileContent(java.io.File f) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.util.Map<String, String> parseFileContent(String content) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        try {
            for (String line : content.split("\\r?\\n")) {
                int idx = line.indexOf('=');
                if (idx > 0) m.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        } catch (Throwable ignored) {}
        return m;
    }
}
