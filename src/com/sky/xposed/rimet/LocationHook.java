package com.sky.xposed.rimet;

import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import android.content.Context;
import android.provider.Settings;
import android.database.Cursor;
import android.net.Uri;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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

    // 默认：台州市政府（椒江区）
    private static final double DEFAULT_LAT = 28.6557;
    private static final double DEFAULT_LNG = 121.4200;

    private static XSharedPreferences sPrefs;
    private static java.util.Map<String, String> sFilePrefs;  // 回退：/sdcard/rimet_location.txt
    private static Context sAppCtx;
    private static java.util.Map<String, String> sProvider;
    private static long sProviderTs;

    public static void hook(ClassLoader cl) {
        try {
            sPrefs = new XSharedPreferences("com.sky.xposed.rimet", "location");
            sPrefs.reload();
            sFilePrefs = loadFilePrefs();
            // provider 的 context 动态获取（Application 创建后才可用），不在此初始化

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

    /** 代理高德 AMapLocationListener */
    private static Object proxyListener(final Object realListener) {
        try {
            Class<?>[] interfaces = realListener.getClass().getInterfaces();
            if (interfaces == null || interfaces.length == 0) {
                Class<?> itf = XposedHelpers.findClass(
                    "com.amap.api.location.AMapLocationListener",
                    realListener.getClass().getClassLoader());
                interfaces = new Class<?>[]{itf};
            }
            return Proxy.newProxyInstance(
                realListener.getClass().getClassLoader(),
                interfaces,
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
     * 虚拟定位开关：Settings.System 里 rimet_enabled = "0" 时，放行真实位置
     */
    private static boolean isEnabled() {
        try {
            if (ctx() != null) {
                String v = Settings.System.getString(ctx().getContentResolver(), "rimet_enabled");
                if (v != null && v.equals("0")) return false;
            }
        } catch (Throwable ignored) {}
        return true;  // 默认开启
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
            lat = getDouble("lat_gcj", DEFAULT_LAT);
            lng = getDouble("lng_gcj", DEFAULT_LNG);
        } else {
            // 系统通道：读 WGS-84 坐标
            lat = getDouble("lat", DEFAULT_LAT);
            lng = getDouble("lng", DEFAULT_LNG);
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

    private static double getDouble(String key, double def) {
        // 0) Settings.System（任何进程免权限读取，HyperOS 下最可靠）
        try {
            if (ctx() != null) {
                String sv = Settings.System.getString(ctx().getContentResolver(), "rimet_" + key);
                if (sv != null && !sv.isEmpty()) {
                    double d = Double.parseDouble(sv);
                    if (d != 0) return d;
                }
            }
        } catch (Throwable ignored) {}
        // 1) ContentProvider（备选）
        String pv = providerValues().get(key);
        if (pv != null) {
            try {
                double d = Double.parseDouble(pv);
                if (d != 0) return d;
            } catch (Throwable ignored) {}
        }
        // 2) XSharedPreferences（如果数据没被容器化）
        try {
            if (sPrefs != null) {
                float v = sPrefs.getFloat(key, Float.NaN);
                if (!Float.isNaN(v)) return v;
            }
        } catch (Throwable ignored) {}
        // 3) 公共文件回退
        if (sFilePrefs != null && sFilePrefs.containsKey(key)) {
            try { return Double.parseDouble(sFilePrefs.get(key)); } catch (Throwable ignored) {}
        }
        // 3) 旧数据兜底：GCJ 键缺失时退回 WGS 键
        if (key.equals("lat_gcj") || key.equals("lng_gcj")) {
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

    /** 动态获取 Application 上下文（Application 创建前会失败，调用方需重试） */
    private static Context ctx() {
        if (sAppCtx != null) return sAppCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = de.robv.android.xposed.XposedHelpers.callStaticMethod(at, "currentApplication");
            if (app instanceof Context) {
                sAppCtx = (Context) app;
                Log.i(TAG, "App 上下文已获取");
                return sAppCtx;
            }
            Object atObj = de.robv.android.xposed.XposedHelpers.callStaticMethod(at, "currentActivityThread");
            if (atObj != null) {
                Object app2 = de.robv.android.xposed.XposedHelpers.callMethod(atObj, "getApplication");
                if (app2 instanceof Context) {
                    sAppCtx = (Context) app2;
                    Log.i(TAG, "App 上下文已获取 (via currentActivityThread)");
                    return sAppCtx;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "获取上下文失败: " + t.getMessage());
        }
        return null;
    }

    /** 通过 ContentProvider 读坐标（成功后 30s 缓存；失败不缓存，下次重试） */
    private static java.util.Map<String, String> providerValues() {
        if (sProvider != null && sProviderTs > 0
            && System.currentTimeMillis() - sProviderTs < 30000) return sProvider;

        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (ctx() != null) {
            try {
                Cursor cur = sAppCtx.getContentResolver().query(
                    Uri.parse("content://com.sky.xposed.rimet.loc/"), null, null, null, null);
                if (cur != null) {
                    int kIdx = cur.getColumnIndex("key");
                    int vIdx = cur.getColumnIndex("value");
                    if (kIdx >= 0 && vIdx >= 0) {
                        while (cur.moveToNext()) m.put(cur.getString(kIdx), cur.getString(vIdx));
                    }
                    cur.close();
                }
                if (!m.isEmpty()) {
                    sProvider = m;
                    sProviderTs = System.currentTimeMillis();
                    Log.i(TAG, "Provider 坐标已加载: " + m);
                } else {
                    Log.w(TAG, "Provider 返回空（模块应用还没保存过？）");
                }
            } catch (Throwable t) {
                Log.w(TAG, "Provider 查询失败: " + t.getMessage());
            }
        }
        return m;
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
