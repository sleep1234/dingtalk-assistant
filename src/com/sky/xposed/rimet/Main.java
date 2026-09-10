package com.sky.xposed.rimet;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Main implements IXposedHookLoadPackage {

    public static final String RIMET = "com.alibaba.android.rimet";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!RIMET.equals(lpparam.packageName)) {
            return;
        }
        // 安全检测绕过（开发者选项/调试/root 检测）
        try {
            SafetyCheckHook.hook(lpparam.classLoader);
        } catch (Throwable t) {
            android.util.Log.e("RimetHook", "safety hook fail", t);
        }
        // 防撤回
        try {
            RecallHook.hook(lpparam.classLoader);
        } catch (Throwable t) {
            android.util.Log.e("RimetHook", "recall hook fail", t);
        }
        // 虚拟定位
        try {
            LocationHook.hook(lpparam.classLoader);
        } catch (Throwable t) {
            android.util.Log.e("RimetHook", "location hook fail", t);
        }
    }
}
