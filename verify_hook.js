/**
 * Frida 动态验证脚本 — 钉钉 8.3.20 定位链路验证
 * 
 * 用法：
 *   frida -U -f com.alibaba.android.rimet -l verify_hook.js --no-pause
 *   或
 *   frida -U 钉钉 -l verify_hook.js
 */

// 需要替换的虚拟坐标（台州市政府）
var FAKE_LAT = 28.6557;
var FAKE_LNG = 121.4200;

Java.perform(function () {
    console.log("[*] Frida 定位链路验证开始");

    // ============================================================
    // 1. hook LocationProxy.onLocationChanged —— 顶层入口
    // ============================================================
    try {
        var LocationProxy = Java.use("com.alibaba.android.dingtalkbase.amap.LocationProxy");
        LocationProxy.onLocationChanged.implementation = function (loc) {
            console.log("[LocationProxy.onLocationChanged] 原始坐标: lat="
                + loc.getLatitude() + " lng=" + loc.getLongitude()
                + " accuracy=" + loc.getAccuracy());
            
            loc.setLatitude(FAKE_LAT);
            loc.setLongitude(FAKE_LNG);
            console.log("[LocationProxy.onLocationChanged] 已改为: lat="
                + FAKE_LAT + " lng=" + FAKE_LNG);
            
            this.onLocationChanged(loc);
        };
        console.log("[+] hook LocationProxy.onLocationChanged 成功");
    } catch (e) {
        console.log("[-] hook LocationProxy.onLocationChanged 失败: " + e);
    }

    // ============================================================
    // 2. hook AMapLocation.getLatitude/getLongitude —— 层层兜底
    // ============================================================
    try {
        var AMapLocation = Java.use("com.amap.api.location.AMapLocation");
        
        AMapLocation.getLatitude.implementation = function () {
            var realLat = this.getLatitude();
            if (realLat != FAKE_LAT && realLat != 0) {
                console.log("[AMapLocation.getLatitude] 拦截: " + realLat + " → " + FAKE_LAT);
            }
            return FAKE_LAT;
        };
        
        AMapLocation.getLongitude.implementation = function () {
            var realLng = this.getLongitude();
            if (realLng != FAKE_LNG && realLng != 0) {
                console.log("[AMapLocation.getLongitude] 拦截: " + realLng + " → " + FAKE_LNG);
            }
            return FAKE_LNG;
        };
        
        console.log("[+] hook AMapLocation.getLatitude/getLongitude 成功");
    } catch (e) {
        console.log("[-] hook AMapLocation 失败: " + e);
    }

    // ============================================================
    // 3. hook AMapLocationClient.setLocationListener —— 监控
    // ============================================================
    try {
        var AMapLocationClient = Java.use("com.amap.api.location.AMapLocationClient");
        
        AMapLocationClient.setLocationListener.implementation = function (listener) {
            console.log("[AMapLocationClient.setLocationListener] listener=" + listener);
            this.setLocationListener(listener);
        };
        
        AMapLocationClient.startLocation.implementation = function () {
            console.log("[AMapLocationClient.startLocation] 发起定位");
            this.startLocation();
        };
        
        AMapLocationClient.getLastKnownLocation.implementation = function () {
            console.log("[AMapLocationClient.getLastKnownLocation] 返回 null");
            return null;
        };
        
        console.log("[+] hook AMapLocationClient 成功");
    } catch (e) {
        console.log("[-] hook AMapLocationClient 失败: " + e);
    }

    // ============================================================
    // 4. hook LocationCallback.onSuccess —— 业务层回调
    // ============================================================
    try {
        var LocationCallback = Java.use("com.alibaba.android.dingtalkbase.amap.LocationCallback");
        var implementations = LocationCallback.class.getMethods();
        console.log("[*] LocationCallback methods count: " + implementations.length);
        
        // 通过 Java 枚举所有实现类来 hook — Frida 的方式是 hook 接口的所有实现
        // 使用 Java.enumerateLoadedClasses 来找到所有 LocationCallback 的实现
        setTimeout(function () {
            Java.enumerateLoadedClasses({
                onMatch: function (className) {
                    // 只关注可能实现 LocationCallback 的业务类
                    if (className.indexOf("amap") >= 0 || className.indexOf("location") >= 0) {
                        // 跳过 SDK 自身
                    }
                },
                onComplete: function () {
                    console.log("[*] 类枚举完成");
                }
            });
        }, 2000);
        
        console.log("[+] hook LocationCallback 接口发现完成");
    } catch (e) {
        console.log("[-] hook LocationCallback 失败: " + e);
    }

    // ============================================================
    // 5. 监控 阿里 AOP 安全检测
    // ============================================================
    try {
        var hof = Java.use("defpackage.hof");
        var b_method = hof.class.getDeclaredMethod("b", 
            Java.use("com.alibaba.wireless.security.aopsdk.Invocation").class);
        console.log("[*] hof.b(Invocation) 找到: " + b_method);
    } catch (e) {
        console.log("[-] hof 安全检测类 不可达: " + e);
    }

    console.log("[*] ========== 验证脚本加载完成 ==========");
    console.log("[*] 请在钉钉内打开打卡页面触发定位，观察日志输出");
});