package com.sky.rimet.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Main implements IXposedHookLoadPackage {

    public static final String RIMET = "com.alibaba.android.rimet";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!RIMET.equals(lpparam.packageName)) {
            return;
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
