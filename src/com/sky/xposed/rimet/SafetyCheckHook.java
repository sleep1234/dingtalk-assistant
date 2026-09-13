package com.sky.xposed.rimet;

import android.content.ContentResolver;
import android.provider.Settings;
import android.util.Log;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SafetyCheckHook {
    private static final String TAG = "RimetHook-Safety";

    public static void hook(ClassLoader cl) {
        try {
            hookSystemProperties(cl);
            hookSettings(cl);
            hookDebugger(cl);
            hookFileExists(cl);
            Log.i(TAG, "安全检测绕过安装成功");
        } catch (Throwable t) {
            Log.e(TAG, "安装安全检测绕过失败", t);
        }
    }

    /** hook android.os.SystemProperties.get/read 覆盖更多重载 */
    private static void hookSystemProperties(ClassLoader cl) {
        try {
            Class<?> spCls = XposedHelpers.findClass("android.os.SystemProperties", cl);

            XC_MethodHook spBlock = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String key = null;
                    if (param.args.length >= 1 && param.args[0] instanceof String) {
                        key = (String) param.args[0];
                    }
                    if (key == null) return;

                    // 按方法名字符串推断返回类型，避免 getReturnType() 在 android.jar stub 下不可用
                    String mn = param.method.getName();

                    if (key.equals("ro.debuggable") || key.equals("ro.adb_enabled") || key.equals("ro.allow.mock.location")
                            || key.contains("adb")) {
                        // 统一返回「非调试」
                        setTypedResult(param, 0, false, "0");
                    } else if (key.equals("ro.secure") || key.equals("ro.adb.secure")) {
                        setTypedResult(param, 1, true, "1");
                    } else if (key.equals("init.svc.adbd")) {
                        // adbd 未运行
                        setTypedNull(param, "stopped");
                    } else if (key.equals("sys.usb.state") || key.equals("sys.usb.config")
                            || key.equals("persist.sys.usb.config")) {
                        // USB 无调试连接
                        setTypedNull(param, "none");
                    }
                }

                /** 根据方法名字符串推断返回类型，设置对应的安全值 */
                private void setTypedResult(MethodHookParam param, int iVal, boolean bVal, String sVal) {
                    String n = param.method.getName();
                    if (n.equals("getInt") || n.equals("getLong")) {
                        param.setResult(n.equals("getLong") ? (long) iVal : iVal);
                    } else if (n.equals("getBoolean")) {
                        param.setResult(bVal);
                    } else {
                        param.setResult(sVal);
                    }
                }

                /** 对非数值/布尔方法返回字符串，数值返回 0，布尔返回 false */
                private void setTypedNull(MethodHookParam param, String sVal) {
                    String n = param.method.getName();
                    if (n.equals("getInt") || n.equals("getLong")) {
                        param.setResult(n.equals("getLong") ? 0L : 0);
                    } else if (n.equals("getBoolean")) {
                        param.setResult(false);
                    } else {
                        param.setResult(sVal);
                    }
                }
            };

            // get/set/read/getInt/getBoolean - 覆盖所有重载
            for (String methodName : new String[]{"get", "read", "getInt", "getBoolean", "getLong"}) {
                try {
                    for (java.lang.reflect.Method m : spCls.getDeclaredMethods()) {
                        if (m.getName().equals(methodName)) {
                            XposedBridge.hookMethod(m, spBlock);
                            Log.i(TAG, "  SystemProperties." + methodName + " hooked");
                        }
                    }
                } catch (Throwable ignored) {}
            }

            Log.i(TAG, "SystemProperties hook 完成");
        } catch (Throwable t) {
            Log.w(TAG, "SystemProperties hook 失败: " + t.getMessage());
        }
    }

    /** hook Settings 所有 getInt/getString/getLong 查询开发者选项 */
    private static void hookSettings(ClassLoader cl) {
        try {
            Set<String> blackKeys = new HashSet<>();
            blackKeys.add("adb_enabled");
            blackKeys.add("adb_enabled_transit");
            blackKeys.add("development_settings_enabled");
            blackKeys.add("development_settings_enabled_transit");
            blackKeys.add("adb_wifi_enabled");
            blackKeys.add("developer_options_enabled");

            XC_MethodHook blocker = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String mn = param.method.getName();
                    for (int i = 0; i < param.args.length; i++) {
                        if (param.args[i] instanceof String && blackKeys.contains((String) param.args[i])) {
                            Log.d(TAG, "拦截: " + mn + "(" + param.args[i] + ")");
                            if (mn.equals("getInt")) param.setResult(0);
                            else if (mn.equals("getLong")) param.setResult(0L);
                            else if (mn.equals("getFloat")) param.setResult(0.0f);
                            else if (mn.equals("getBoolean")) param.setResult(false);
                            else param.setResult("0");
                            break;
                        }
                    }
                }
            };

            // hook Settings.Global/Secure/System 的所有 getInt/getString/getLong
            for (Class<?> cls : new Class<?>[]{Settings.Global.class, Settings.Secure.class, Settings.System.class}) {
                for (String methodName : new String[]{"getInt", "getString", "getLong"}) {
                    try {
                        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                            if (m.getName().equals(methodName) && m.getParameterTypes().length >= 2 && 
                                m.getParameterTypes()[0] == ContentResolver.class && 
                                m.getParameterTypes()[1] == String.class) {
                                XposedBridge.hookMethod(m, blocker);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            Log.i(TAG, "Settings hook 完成");
        } catch (Throwable t) {
            Log.w(TAG, "Settings hook 失败: " + t.getMessage());
        }
    }

    /** hook Debug.isDebuggerConnected */
    private static void hookDebugger(ClassLoader cl) {
        try {
            Class<?> debugCls = XposedHelpers.findClass("android.os.Debug", cl);
            XposedHelpers.findAndHookMethod(debugCls, "isDebuggerConnected", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
            Log.i(TAG, "Debug.isDebuggerConnected hooked");
        } catch (Throwable t) {
            Log.w(TAG, "Debug hook 失败: " + t.getMessage());
        }
    }

    /** hook File.exists 拦截常见 su 二进制探测（精确路径段匹配，避免误伤 libsuspend 等库） */
    private static void hookFileExists(ClassLoader cl) {
        // su 常见路径 —— 只有以这些精确路径结尾时才拦截
        final String[] SU_PATHS = {
            "/su", "/su/bin/su", "/system/xbin/su",
            "/system/bin/su", "/sbin/su", "/system/su",
            "/system/.su", "/system/xbin/.su",
            "/system/bin/.su", "/system/sbin/su"
        };
        try {
            XposedHelpers.findAndHookMethod(java.io.File.class, "exists", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    File f = (File) param.thisObject;
                    String path = f.getAbsolutePath();
                    // /magisk 前缀精细化：只拦截 magisk su 和 zygisk su
                    if (path.startsWith("/magisk") &&
                            (path.equals("/magisk/.core/bin/su") ||
                             path.endsWith("/magisk/su") ||
                             path.contains("magiskhide"))) {
                        param.setResult(false);
                        Log.d(TAG, "File.exists(" + path + ") → false (magisk)");
                        return;
                    }
                    for (String suP : SU_PATHS) {
                        if (path.equals(suP)) {
                            param.setResult(false);
                            Log.d(TAG, "File.exists(" + path + ") → false (su)");
                            return;
                        }
                    }
                }
            });
            Log.i(TAG, "File.exists hooked (su blocking)");
        } catch (Throwable t) {
            Log.w(TAG, "File.exists hook 失败: " + t.getMessage());
        }
    }
}
