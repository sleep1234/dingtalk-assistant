package com.sky.xposed.rimet;

import android.content.ContentResolver;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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
                    if (key != null) {
                        if (key.equals("ro.debuggable") || key.equals("ro.adb_enabled") || key.contains("adb")) {
                            param.setResult("get".equals(param.method.getName()) ? "0" : 0);
                        } else if (key.equals("ro.secure") || key.equals("ro.adb.secure")) {
                            param.setResult("get".equals(param.method.getName()) ? "1" : 1);
                        } else if (key.equals("ro.allow.mock.location")) {
                            param.setResult("get".equals(param.method.getName()) ? "0" : 0);
                        } else if (key.equals("init.svc.adbd")) {
                            param.setResult("get".equals(param.method.getName()) ? "stopped" : null);
                        } else if (key.equals("sys.usb.state") || key.equals("sys.usb.config")) {
                            param.setResult("get".equals(param.method.getName()) ? "none" : null);
                        } else if (key.equals("persist.sys.usb.config")) {
                            param.setResult("get".equals(param.method.getName()) ? "none" : null);
                        }
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

    /** hook File.exists 拦截 su 文件检测 */
    private static void hookFileExists(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(java.io.File.class, "exists", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    File f = (File) param.thisObject;
                    String path = f.getAbsolutePath();
                    if (path.contains("su") && (path.startsWith("/system/") || 
                        path.startsWith("/sbin/") || path.startsWith("/magisk") || 
                        path.equals("/su"))) {
                        param.setResult(false);
                        Log.d(TAG, "File.exists(" + path + ") → false");
                    }
                }
            });
            Log.i(TAG, "File.exists hooked (su blocking)");
        } catch (Throwable t) {
            Log.w(TAG, "File.exists hook 失败: " + t.getMessage());
        }
    }
}
