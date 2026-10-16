package com.sky.xposed.rimet;

final class LocationHookPolicy {
    static final String[] DIRECT_SERVICE_METHODS = {"b", "i"};
    static final String[] IMPLEMENTATION_METHODS = {"getCurrentLocation", "startPersistentLocation"};
    static final String[] IMPLEMENTATION_CLASSES = {
        "com.alibaba.android.rimet.service.system.b",
        "com.alibaba.android.rimet.service.system.a",
        "defpackage.d7d",
        "defpackage.njd",
        "defpackage.x9x",
        "defpackage.ere",
        "defpackage.t7t",
        "defpackage.k8t",
        "defpackage.s7t"
    };

    private LocationHookPolicy() {}
}
