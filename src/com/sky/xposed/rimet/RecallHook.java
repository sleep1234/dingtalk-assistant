package com.sky.xposed.rimet;

import android.content.ContentValues;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 钉钉 8.x 防撤回 Hook
 * 
 * 新版混淆映射 (8.x):
 *   MessageDs        → defpackage/x6h (继承 IMDatabase)
 *   handler 方法      → x6h.c(String, Collection, boolean)
 *   update 方法       → x6h.T(String, String, List)
 *   撤回 RPC (单条)   → v9h.K(String cid, long mid, Callback)
 *   撤回 RPC (批量)   → v9h.J(String cid, List mids, int recallType, Callback)
 */
public class RecallHook {

    private static ClassLoader sClassLoader;
    private static final String TAG = "RimetHook-Recall";
    private static final int MSG_TYPE_RECALL = 126;
    private static final int MSG_TYPE_TEXT = 10;

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;
        try {
            // 1. 阻止撤回 RPC 请求
            hookRecallRpc(cl);

            // 2. 拦截撤回通知消息，标记 "[已撤回]"
            hookMessageHandler(cl);

            Log.i(TAG, "防撤回 Hook 安装成功");
        } catch (Throwable t) {
            Log.e(TAG, "安装防撤回 Hook 失败", t);
        }
    }

    /**
     * Hook v9h 的撤回 RPC 方法，阻止撤回请求发出
     * v9h.K(String, long, Callback) — 单条撤回
     * v9h.J(String, List, int, Callback) — 批量撤回
     */
    private static void hookRecallRpc(ClassLoader cl) {
        try {
            Class<?> msgRpc = XposedHelpers.findClass("v9h", cl);

            // 单条撤回: v9h.K(String cid, long mid, Callback)
            XposedHelpers.findAndHookMethod(msgRpc, "K",
                String.class, long.class, "com.alibaba.wukong.Callback",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Log.d(TAG, "阻止单条撤回 RPC: cid=" + param.args[0] + " mid=" + param.args[1]);
                        // setResult 阻止方法执行
                        param.setResult(null);
                    }
                });

            // 批量撤回: v9h.J(String cid, List, int recallType, Callback)
            XposedHelpers.findAndHookMethod(msgRpc, "J",
                String.class, List.class, int.class, "com.alibaba.wukong.Callback",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Log.d(TAG, "阻止批量撤回 RPC: cid=" + param.args[0]);
                        param.setResult(null);
                    }
                });

            Log.i(TAG, "撤回 RPC Hook 成功");
        } catch (Throwable t) {
            Log.w(TAG, "撤回 RPC Hook 失败（可能不支持此版本）", t);
        }
    }

    /**
     * Hook MessageDs.handler (x6h.c) 来检测撤回通知消息
     * 当检测到 type=126 的撤回通知时，把被撤回的文本消息标记 "[已撤回]"
     */
    private static void hookMessageHandler(ClassLoader cl) {
        try {
            Class<?> messageDs = XposedHelpers.findClass("x6h", sClassLoader);

            // x6h.c(String cid, Collection<MessageImpl> messages, boolean)
            XposedHelpers.findAndHookMethod(messageDs, "c",
                String.class, Collection.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String cid = (String) param.args[0];
                        @SuppressWarnings("unchecked")
                        Collection<Object> messages = (Collection<Object>) param.args[1];
                        if (messages == null || messages.isEmpty()) return;

                        for (Object msg : messages) {
                            handleMessage(cid, msg, null);
                        }
                    }
                });

            Log.i(TAG, "消息处理 Hook 成功");
        } catch (Throwable t) {
            Log.w(TAG, "消息处理 Hook 失败", t);
        }
    }

    /**
     * 处理单条消息：如果是撤回通知(126)，标记被撤回的消息
     */
    private static void handleMessage(String cid, Object message, ClassLoader cl) {
        if (message == null) return;

        try {
            int msgType = getMessageType(message);
            if (msgType != MSG_TYPE_RECALL) return;

            // 获取会话
            Object conversation = getFieldValue(message, "mConversation");
            if (conversation == null) return;

            // 获取最新消息（就是被撤回的那条）
            Object latestMsg = XposedHelpers.callMethod(conversation, "latestMessage");
            if (latestMsg == null) return;

            int latestType = getMessageType(latestMsg);
            if (latestType != MSG_TYPE_TEXT) return;

            // 获取消息文本
            String text = getMessageText(latestMsg);
            if (text == null) return;

            // 追加 "[已撤回]" 标记
            setMessageText(latestMsg, text + " [已撤回]");

            // 更新到数据库: x6h.T(String dbName, String cid, List msgList)
            try {
                Class<?> imDatabase = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.base.IMDatabase", sClassLoader);
                String dbName = (String) XposedHelpers.callStaticMethod(
                    imDatabase, "getWritableDatabase");

                Class<?> msgDs = XposedHelpers.findClass("x6h", cl);
                Object result = XposedHelpers.findMethodBestMatch(msgDs, "T",
                    String.class, String.class, List.class)
                    .invoke(null, dbName, cid,
                        Collections.singletonList(latestMsg));

            } catch (Throwable t) {
                Log.w(TAG, "更新撤回消息到DB失败", t);
            }

        } catch (Throwable t) {
            Log.w(TAG, "处理撤回消息失败", t);
        }
    }

    /**
     * 获取消息类型（type 字段或 type() 方法）
     */
    private static int getMessageType(Object message) {
        try {
            // 先尝试 type() 方法
            Object content = XposedHelpers.callMethod(message, "messageContent");
            if (content != null) {
                try {
                    return (int) XposedHelpers.callMethod(content, "type");
                } catch (NoSuchMethodError e) {
                    // fallback: 尝试直接读 type 字段
                }
            }
            // fallback: type 字段
            return XposedHelpers.getIntField(message, "type");
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 获取消息文本内容
     */
    private static String getMessageText(Object message) {
        try {
            Object content = XposedHelpers.callMethod(message, "messageContent");
            if (content != null) {
                // TextContentImpl.text()
                return (String) XposedHelpers.callMethod(content, "text");
            }
        } catch (Throwable t) {
            Log.w(TAG, "获取消息文本失败", t);
        }
        return null;
    }

    /**
     * 设置消息文本内容
     */
    private static void setMessageText(Object message, String text) {
        try {
            Object content = XposedHelpers.callMethod(message, "messageContent");
            if (content != null) {
                // TextContentImpl.setText(String)
                XposedHelpers.callMethod(content, "setText", text);
            }
        } catch (Throwable t) {
            Log.w(TAG, "设置消息文本失败", t);
        }
    }

    /**
     * 反射获取对象字段值（尝试 m + 驼峰格式，或直接字段名）
     */
    private static Object getFieldValue(Object obj, String fieldName) {
        try {
            // 优先尝试 XposedHelpers
            return XposedHelpers.getObjectField(obj, fieldName);
        } catch (NoSuchFieldError e) {
            // 尝试 m 前缀版本
            try {
                return XposedHelpers.getObjectField(obj, "m" +
                    Character.toUpperCase(fieldName.charAt(0)) +
                    fieldName.substring(1));
            } catch (NoSuchFieldError e2) {
                // 尝试标准反射
                try {
                    Field f = obj.getClass().getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (Throwable t2) {
                    return null;
                }
            }
        }
    }
}
