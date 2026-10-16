package com.sky.xposed.rimet;

/** Centralizes the property values exposed to the target process. */
final class SafetyCheckPolicy {
    private SafetyCheckPolicy() {}

    static String valueFor(String key) {
        if (key == null) return null;
        if ("init.svc.adbd".equals(key)) return "stopped";
        if ("sys.usb.state".equals(key) || "sys.usb.config".equals(key)
                || "persist.sys.usb.config".equals(key)) return "none";
        if ("ro.adb.secure".equals(key) || "ro.secure".equals(key)) return "1";
        if ("ro.debuggable".equals(key) || "ro.adb_enabled".equals(key)
                || "ro.allow.mock.location".equals(key) || key.contains("adb")) return "0";
        return null;
    }
}
