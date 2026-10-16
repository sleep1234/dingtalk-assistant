package com.sky.xposed.rimet;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Hooks the message APIs used by DingTalk 9.0.2.2 to apply recall state. */
public class RecallHook {
    private static final String TAG = "RimetHook-Recall";
    private static final String CALLBACK = "com.alibaba.wukong.Callback";
    private static final Set<Class<?>> HOOKED_SERVICE_CLASSES = Collections.newSetFromMap(
        new IdentityHashMap<Class<?>, Boolean>());
    private static final XC_MethodHook BLOCK_BATCH_RECALL = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Log.d(TAG, "阻止批量撤回请求");
            param.setResult(null);
        }
    };

    public static void hook(ClassLoader cl) {
        boolean messageImplHooked = hookMessageImpl(cl);
        boolean batchHooked = hookBatchRecall(cl);
        if (messageImplHooked || batchHooked) {
            Log.i(TAG, "防撤回 Hook 安装成功");
        } else {
            Log.w(TAG, "未找到可用的防撤回 API");
        }
    }

    private static boolean hookMessageImpl(ClassLoader cl) {
        try {
            Class<?> messageImpl = XposedHelpers.findClass(RecallHookPolicy.MESSAGE_IMPL, cl);
            XposedHelpers.findAndHookMethod(messageImpl, RecallHookPolicy.RECALL_METHOD,
                CALLBACK, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Log.d(TAG, "阻止单条撤回请求");
                        param.setResult(null);
                    }
                });
            XposedHelpers.findAndHookMethod(messageImpl, RecallHookPolicy.LOCAL_STATUS_METHOD,
                int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Log.d(TAG, "阻止本地撤回状态写入: status=" + param.args[0]);
                        param.setResult(null);
                    }
                });
            XposedHelpers.findAndHookMethod(messageImpl, "recallStatus",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(0);
                    }
                });
            Log.i(TAG, "MessageImpl 撤回 API Hook 成功");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "MessageImpl Hook 失败: " + t.getMessage());
            return false;
        }
    }

    private static boolean hookBatchRecall(ClassLoader cl) {
        boolean hookedLookup = false;
        hookedLookup |= hookServiceLookup(cl, RecallHookPolicy.IM_ENGINE, RecallHookPolicy.GET_IM_SERVICE);
        hookedLookup |= hookServiceLookup(cl, RecallHookPolicy.IM_MODULE, RecallHookPolicy.GET_SERVICE);
        return hookedLookup;
    }

    private static boolean hookServiceLookup(ClassLoader cl, String ownerName, String methodName) {
        try {
            Class<?> owner = XposedHelpers.findClass(ownerName, cl);
            XposedHelpers.findAndHookMethod(owner, methodName, Class.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object service = param.getResult();
                        Class<?> requestedType = param.args.length == 0 || !(param.args[0] instanceof Class)
                            ? null : (Class<?>) param.args[0];
                        if (service != null && isRecallService(requestedType, service.getClass())) {
                            hookConcreteBatchMethods(service);
                        }
                    }
                });
            Log.i(TAG, ownerName + "." + methodName + " 服务出口 Hook 已安装");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, ownerName + "." + methodName + " 服务出口 Hook 失败: " + t.getMessage());
            return false;
        }
    }

    private static boolean isRecallService(Class<?> requestedType, Class<?> implementationType) {
        if (requestedType != null && RecallHookPolicy.isRecallServiceTypeName(requestedType.getName())) {
            return true;
        }
        return implementsRecallService(implementationType);
    }

    private static boolean implementsRecallService(Class<?> type) {
        if (type == null) return false;
        for (Class<?> iface : type.getInterfaces()) {
            if (RecallHookPolicy.isRecallServiceTypeName(iface.getName())
                    || implementsRecallService(iface)) {
                return true;
            }
        }
        return implementsRecallService(type.getSuperclass());
    }

    private static boolean hookConcreteBatchMethods(Object service) {
        Class<?> implementation = service.getClass();
        synchronized (HOOKED_SERVICE_CLASSES) {
            if (!HOOKED_SERVICE_CLASSES.add(implementation)) {
                return false;
            }
        }

        int hookedMethods = 0;
        try {
            for (Class<?> type = implementation; type != null && type != Object.class;
                    type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!RecallHookPolicy.BATCH_RECALL_METHOD.equals(method.getName())
                            || Modifier.isAbstract(method.getModifiers())) {
                        continue;
                    }
                    XposedBridge.hookMethod(method, BLOCK_BATCH_RECALL);
                    hookedMethods++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "具体批量撤回实现 Hook 失败: " + t.getMessage());
        }
        if (hookedMethods > 0) {
            Log.i(TAG, "具体批量撤回实现 Hook 成功: " + implementation.getName()
                + ", methods=" + hookedMethods);
            return true;
        }
        return false;
    }
}
