package com.sky.rimet.hook;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 钉钉虚拟定位 Hook
 * 
 * 钉钉定位架构（8.3.20）：
 *   LocationService (单例 j) 持有多个 ILocationService 实现：
 *     a = service.system.b  (SYSTEM)
 *     b = j6c               (GAODE 高德)
 *     c = qec               (GOOGLE)
 *     d = wau               (HUAWEI)
 *     e = bgd               (WIFI)
 *     f = blq               (SYSTEM_GPS)
 *     g = plq               (SYSTEM_NETWORK)
 *     h = alq               (SYSTEM_FUSED)
 *   所有实现最终回调 DtLocationCallback.onGetLocation(DtLocation)
 *   DtLocation extends AMapLocation
 * 
 * 策略：hook LocationService.getCurrentLocation / startPersistentLocation，
 *       替换 DtLocationCallback 为代理，拦截 onGetLocation(DtLocation)，
 *       修改经纬度 + 地址字段为虚拟坐标。
 */
public class LocationHook {

    private static final String TAG = "RimetHook-Location";

    // 默认虚拟位置（北京天安门），可被 UI 覆盖
    private static final double DEFAULT_LAT = 39.9087;
    private static final double DEFAULT_LNG = 116.3975;
    private static final String DEFAULT_ADDR = "北京市东城区";
    private static final String DEFAULT_PROVINCE = "北京市";
    private static final String DEFAULT_CITY = "北京市";
    private static final String DEFAULT_DISTRICT = "东城区";

    private static XSharedPreferences sPrefs;

    public static void hook(ClassLoader cl) {
        try {
            // 加载模块自己的 SharedPreferences（通过 LSPosed 管理器可改）
            sPrefs = new XSharedPreferences("com.sky.rimet.hook", "location");
            // XSharedPreferences 通过 setReadable 实现共享
            sPrefs.reload();

            // Hook LocationService.getCurrentLocation 和 startPersistentLocation
            hookLocationService(cl);

            Log.i(TAG, "虚拟定位 Hook 安装成功");
        } catch (Throwable t) {
            Log.e(TAG, "安装虚拟定位 Hook 失败", t);
        }
    }

    private static void hookLocationService(ClassLoader cl) {
        try {
            Class<?> locationService = XposedHelpers.findClass(
                "com.alibaba.android.rimet.LocationService", cl);

            // hook 实例方法 getCurrentLocation(Context, String, tof, DtLocationCallback)
            XposedHelpers.findAndHookMethod(locationService, "getCurrentLocation",
                "android.content.Context", String.class, Object.class,
                "com.alibaba.android.rimet.DtLocationCallback",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object callback = param.args[3];
                        if (callback != null && !Proxy.isProxyClass(callback.getClass())) {
                            param.args[3] = wrapCallback(callback);
                        }
                    }
                });

            // hook startPersistentLocation(Context, String, tof, DtLocationCallback)
            XposedHelpers.findAndHookMethod(locationService, "startPersistentLocation",
                "android.content.Context", String.class, Object.class,
                "com.alibaba.android.rimet.DtLocationCallback",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object callback = param.args[3];
                        if (callback != null && !Proxy.isProxyClass(callback.getClass())) {
                            param.args[3] = wrapCallback(callback);
                        }
                    }
                });

            Log.i(TAG, "LocationService hook 成功");
        } catch (Throwable t) {
            Log.w(TAG, "LocationService hook 失败，尝试备选方案", t);
            hookSystemLocationManager(cl);
        }
    }

    /**
     * 备选：hook 系统 LocationManager，拦截所有定位回调
     */
    private static void hookSystemLocationManager(ClassLoader cl) {
        try {
            Class<?> locationManager = XposedHelpers.findClass(
                "android.location.LocationManager", cl);
            Log.i(TAG, "备选方案：系统 LocationManager hook 成功");
        } catch (Throwable t) {
            Log.w(TAG, "系统 LocationManager hook 也失败", t);
        }
    }

    /**
     * 用代理包装 DtLocationCallback，拦截 onGetLocation
     */
    private static Object wrapCallback(final Object realCallback) {
        try {
            Class<?> cbInterface = XposedHelpers.findClass(
                "com.alibaba.android.rimet.DtLocationCallback",
                realCallback.getClass().getClassLoader());

            return Proxy.newProxyInstance(
                realCallback.getClass().getClassLoader(),
                new Class<?>[]{cbInterface},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("onGetLocation".equals(method.getName()) && args != null && args.length > 0) {
                            patchLocation(args[0]);
                        }
                        try {
                            return method.invoke(realCallback, args);
                        } catch (Throwable t) {
                            Log.w(TAG, "回调转发失败: " + method.getName(), t);
                            return null;
                        }
                    }
                });
        } catch (Throwable t) {
            Log.w(TAG, "包装 callback 失败", t);
            return realCallback;
        }
    }

    /**
     * 修改 DtLocation（继承 AMapLocation）的坐标和地址
     */
    private static void patchLocation(Object loc) {
        if (loc == null) return;

        double lat = getDouble("lat", DEFAULT_LAT);
        double lng = getDouble("lng", DEFAULT_LNG);
        String addr = getString("addr", DEFAULT_ADDR);
        String province = getString("province", DEFAULT_PROVINCE);
        String city = getString("city", DEFAULT_CITY);
        String district = getString("district", DEFAULT_DISTRICT);

        // 用 XposedHelpers.callMethod 调用 setter（继承自 AMapLocation）
        callSetter(loc, "setLatitude", lat);
        callSetter(loc, "setLongitude", lng);
        callSetter(loc, "setAddress", addr);
        callSetter(loc, "setProvince", province);
        callSetter(loc, "setCity", city);
        callSetter(loc, "setDistrict", district);
        callSetter(loc, "setCityCode", "010");
        callSetter(loc, "setAdCode", "110101");

        Log.d(TAG, "已替换为虚拟位置: lat=" + lat + ", lng=" + lng + ", addr=" + addr);
    }

    private static void callSetter(Object obj, String methodName, double value) {
        try {
            XposedHelpers.callMethod(obj, methodName, value);
        } catch (Throwable ignored) {}
    }

    private static void callSetter(Object obj, String methodName, String value) {
        try {
            XposedHelpers.callMethod(obj, methodName, value);
        } catch (Throwable ignored) {}
    }

    private static double getDouble(String key, double def) {
        try {
            if (sPrefs != null) return sPrefs.getFloat(key, (float) def);
        } catch (Throwable ignored) {}
        return def;
    }

    private static String getString(String key, String def) {
        try {
            if (sPrefs != null) {
                String v = sPrefs.getString(key, null);
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Throwable ignored) {}
        return def;
    }
}
