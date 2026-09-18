package com.sky.xposed.rimet;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Main implements IXposedHookLoadPackage {

    public static final String RIMET = "com.alibaba.android.rimet";
    private static final String TAG = "RimetHook";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!RIMET.equals(lpparam.packageName)) {
            return;
        }
        try {
            SafetyCheckHook.hook(lpparam.classLoader);
        } catch (Throwable t) {
            Log.e(TAG, "safety hook fail", t);
        }
        try {
            RecallHook.hook(lpparam.classLoader);
        } catch (Throwable t) {
            Log.e(TAG, "recall hook fail", t);
        }
        try {
            LocationHook.hook(lpparam.classLoader);
        } catch (Throwable t) {
            Log.e(TAG, "location hook fail", t);
        }
    }
}
