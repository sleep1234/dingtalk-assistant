package com.sky.xposed.rimet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;

/**
 * 钉钉虚拟定位 Hook (v4)
 * 参考原始 xposed-rimet LocationPlugin 的实现：
 *   1. hook AMapLocationClient.setLocationListener → 代理 listener
 *   2. hook AMapLocationClient.getLastKnownLocation → 返回 null
 *   3. 代理 listener 的 onLocationChanged 原地修改 Location 坐标
 * 同时兜底 hook 系统 LocationManager.requestLocationUpdates
 */
public class LocationHook {

    private static final String TAG = "RimetHook-Location";

    // 默认：台州市政府（椒江区）GCJ-02
    private static final double DEFAULT_LAT = 28.6557;
    private static final double DEFAULT_LNG = 121.4200;

    // 坐标读取 key 常量
    private static final String KEY_LAT_GCJ = "lat_gcj";
    private static final String KEY_LNG_GCJ = "lng_gcj";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LNG = "lng";
    private static final String KEY_ENABLED = "enabled";

    // 开关状态缓存
    private static final long ENABLED_CACHE_MS = 5000L;
    private static boolean sEnabledCache = true;
    private static long sEnabledCacheTs;

    private static XSharedPreferences sPrefs;
    private static java.util.Map<String, String> sFilePrefs;

    public static void hook(ClassLoader cl) {
        try {
            // LSPosed 的 xposedsharedprefs 机制会自动把模块 prefs 重定向到共享目录，
            // 钉钉进程通过 XSharedPreferences 直接读，无需 root、无需 ContentProvider。
            sPrefs = new XSharedPreferences("com.sky.xposed.rimet", "location");
            sPrefs.reload();
            sFilePrefs = loadFilePrefs();

            // 1. 高德 AMapLocationClient
            hookAMap(cl);

            // 2. 系统 LocationManager 兜底
            hookSystemLocationManager(cl);

            Log.i(TAG, "虚拟定位 Hook 安装成功");
        } catch (Throwable t) {
            Log.e(TAG, "安装虚拟定位 Hook 失败", t);
        }
    }

    /** hook 高德 AMapLocationClient */
    private static void hookAMap(ClassLoader cl) {
        try {
            Class<?> clientCls = XposedHelpers.findClass(
                "com.amap.api.location.AMapLocationClient", cl);

            // getLastKnownLocation → 返回 null（让钉钉重新定位）
            try {
                XposedHelpers.findAndHookMethod(clientCls, "getLastKnownLocation",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    });
                Log.i(TAG, "hook getLastKnownLocation 成功");
            } catch (Throwable t) {
                Log.w(TAG, "hook getLastKnownLocation 失败: " + t.getMessage());
            }

            // setLocationListener → 代理 listener
            try {
                XposedHelpers.findAndHookMethod(clientCls, "setLocationListener",
                    "com.amap.api.location.AMapLocationListener",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object listener = param.args[0];
                            if (listener != null && !Proxy.isProxyClass(listener.getClass())) {
                                param.args[0] = proxyListener(listener);
                            }
                        }
                    });
                Log.i(TAG, "hook setLocationListener 成功");
            } catch (Throwable t) {
                Log.w(TAG, "hook setLocationListener 失败: " + t.getMessage());
            }
        } catch (Throwable t) {
            Log.w(TAG, "hook AMapLocationClient 失败: " + t.getMessage());
        }
    }

    /** 兜底：系统 LocationManager */
    private static void hookSystemLocationManager(ClassLoader cl) {
        try {
            Class<?> mgrCls = XposedHelpers.findClass(
                "android.location.LocationManager", cl);

            XposedHelpers.findAndHookMethod(mgrCls, "requestLocationUpdates",
                String.class, long.class, float.class,
                "android.location.LocationListener",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object listener = param.args[3];
                        if (listener != null && !Proxy.isProxyClass(listener.getClass())
                            && listener instanceof android.location.LocationListener) {
                            param.args[3] = proxySystemListener(
                                (android.location.LocationListener) listener);
                        }
                    }
                });
            Log.i(TAG, "系统 LocationManager hook 成功");
        } catch (Throwable t) {
            Log.w(TAG, "系统 LocationManager hook 失败: " + t.getMessage());
        }
    }

    /** 代理高德 AMapLocationListener（只代理该单一接口，避免多接口代理导致方法签名不匹配） */
    private static Object proxyListener(final Object realListener) {
        try {
            // 只代理 AMapLocationListener 接口，避免代理对象的其他接口在 invoke 时签名不匹配
            Class<?> itf = XposedHelpers.findClass(
                "com.amap.api.location.AMapLocationListener",
                realListener.getClass().getClassLoader());
            return Proxy.newProxyInstance(
                realListener.getClass().getClassLoader(),
                new Class<?>[]{itf},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("onLocationChanged".equals(method.getName())
                            && args != null && args.length > 0) {
                            patchLocation(args[0], true);
                        }
                        try {
                            return method.invoke(realListener, args);
                        } catch (Throwable t) {
                            return null;
                        }
                    }
                });
        } catch (Throwable t) {
            return realListener;
        }
    }

    /** 代理系统 LocationListener */
    private static Object proxySystemListener(final android.location.LocationListener real) {
        return Proxy.newProxyInstance(
            real.getClass().getClassLoader(),
            new Class<?>[]{android.location.LocationListener.class},
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onLocationChanged".equals(method.getName())
                        && args != null && args.length > 0) {
                        patchLocation(args[0], false);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (Throwable t) {
                        return null;
                    }
                }
            });
    }

    /**
     * 虚拟定位开关：prefs 里 enabled = "0"/false 时放行真实位置。
     * 通过 XSharedPreferences 读取（LSPosed 已共享），缓存 5 秒。
     */
    private static boolean isEnabled() {
        long now = System.currentTimeMillis();
        if (now - sEnabledCacheTs < ENABLED_CACHE_MS) {
            return sEnabledCache;
        }
        try {
            if (sPrefs != null) {
                sPrefs.reload();
                // 支持 boolean 和 string 两种存储形式
                if (sPrefs.contains(KEY_ENABLED)) {
                    try {
                        sEnabledCache = sPrefs.getBoolean(KEY_ENABLED, true);
                    } catch (Throwable t) {
                        String v = sPrefs.getString(KEY_ENABLED, "1");
                        sEnabledCache = !"0".equals(v) && !"false".equalsIgnoreCase(v);
                    }
                } else {
                    sEnabledCache = true;  // 默认开启
                }
            }
        } catch (Throwable t) {
            sEnabledCache = true;
        }
        sEnabledCacheTs = now;
        return sEnabledCache;
    }

    /**
     * 原地修改 Location/AMapLocation 坐标
     * @param gcj02 true=高德通道（用 GCJ-02 坐标），false=系统通道（用 WGS-84 坐标）
     */
    private static void patchLocation(Object loc, final boolean gcj02) {
        if (loc == null) return;
        if (!isEnabled()) {
            Log.d(TAG, "虚拟定位已关闭，放行真实位置");
            return;
        }
        double lat, lng;
        if (gcj02) {
            // 高德通道：读 GCJ-02 坐标
            lat = getDouble(KEY_LAT_GCJ, DEFAULT_LAT);
            lng = getDouble(KEY_LNG_GCJ, DEFAULT_LNG);
        } else {
            // 系统通道：读 WGS-84 坐标
            lat = getDouble(KEY_LAT, DEFAULT_LAT);
            lng = getDouble(KEY_LNG, DEFAULT_LNG);
        }

        // 优先用 setLatitude/setLongitude（android.location.Location 和高德 AMapLocation 都有）
        try {
            XposedHelpers.callMethod(loc, "setLatitude", lat);
            XposedHelpers.callMethod(loc, "setLongitude", lng);
            Log.d(TAG, "定位已改[" + (gcj02 ? "GCJ" : "WGS") + "]: lat=" + lat + " lng=" + lng);
        } catch (Throwable t) {
            // 兜底：反射修改字段
            trySetField(loc, new String[]{"f", "g", "h", "i"}, lat, lng);
        }
    }

    private static void trySetField(Object obj, String[] names, double lat, double lng) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    if ("D".equals(f.getType().getName())
                        || "double".equals(f.getType().getName())) {
                        String name = f.getName();
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

    /**
     * 读取坐标值。
     * 优先级：XSharedPreferences（LSPosed 已共享） → 公共文件回退 → 默认值
     */
    private static double getDouble(String key, double def) {
        //   XSharedPreferences，每次调用前增量刷新（内置 mtime/size 校验，变化时才重新解析）
        try {
            if (sPrefs != null) {
                sPrefs.reload();
                float v = sPrefs.getFloat(key, Float.NaN);
                if (!Float.isNaN(v)) return v;
            }
        } catch (Throwable ignored) {}
        // 2) 公共文件回退（/sdcard/rimet_location.txt）
        if (sFilePrefs != null && sFilePrefs.containsKey(key)) {
            try { return Double.parseDouble(sFilePrefs.get(key)); } catch (Throwable ignored) {}
        }
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

    /** 读 /sdcard/rimet_location.txt（key=value 行）作为回退数据源 */
    private static java.util.Map<String, String> loadFilePrefs() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        try {
            java.io.File f = new java.io.File("/sdcard/rimet_location.txt");
            if (f.canRead()) {
                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    int idx = line.indexOf('=');
                    if (idx > 0) m.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
                br.close();
                Log.i(TAG, "公共定位配置已加载: " + m);
            } else {
                Log.w(TAG, "/sdcard/rimet_location.txt 不可读，使用默认坐标");
            }
        } catch (Throwable t) {
            Log.w(TAG, "读公共定位配置失败: " + t.getMessage());
        }
        return m;
    }
}
