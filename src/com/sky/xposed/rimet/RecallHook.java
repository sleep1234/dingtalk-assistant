package com.sky.xposed.rimet;

import android.util.Log;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

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

    // 钉钉 8.x 混淆类名（随钉钉版本变化，升级后需重新分析）
    private static final String CLS_RECALL_RPC = "defpackage.v9h";
    private static final String CLS_MESSAGE_DS = "defpackage.x6h";
    private static final String CLS_WUKONG_CALLBACK = "com.alibaba.wukong.Callback";
    private static final String CLS_IM_DATABASE = "com.alibaba.wukong.im.base.IMDatabase";

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
            Class<?> msgRpc = XposedHelpers.findClass(CLS_RECALL_RPC, cl);

            // 单条撤回: v9h.K(String cid, long mid, Callback)
            XposedHelpers.findAndHookMethod(msgRpc, "K",
                String.class, long.class, CLS_WUKONG_CALLBACK,
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
                String.class, List.class, int.class, CLS_WUKONG_CALLBACK,
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
            Class<?> messageDs = XposedHelpers.findClass(CLS_MESSAGE_DS, sClassLoader);

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
                            handleMessage(cid, msg, sClassLoader);
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
                Class<?> imDatabase = XposedHelpers.findClass(CLS_IM_DATABASE, sClassLoader);
// 获取数据库名字符串（IMDatabase.getWritableDatabase() 返回数据库路径/名称）
                    Object dbName = XposedHelpers.callStaticMethod(
                        imDatabase, "getWritableDatabase");
                    if (dbName == null) return;

// x6h.T(String dbName, String cid, List msgList) 是实例方法
                    Class<?> x6hCls = XposedHelpers.findClass(CLS_MESSAGE_DS, sClassLoader);
                    // 需要获取 x6h 的实例。MessageDs 是单例或全局对象，
                    // 用 getDefaultInstance() 或通过调用 static getInstance() 获取。
                    // 退一步：先用 callStaticMethod 尝试，失败则打日志。
                    try {
                        XposedHelpers.callStaticMethod(x6hCls, "T",
                            dbName, cid, Collections.singletonList(latestMsg));
                    } catch (NoSuchMethodError e) {
                        // T 方法是实例方法，尝试通过静态 getInstance() 获取实例
                        Object instance = XposedHelpers.callStaticMethod(x6hCls, "getInstance");
                        XposedHelpers.callMethod(instance, "T",
                            dbName, cid, Collections.singletonList(latestMsg));
                    }

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
