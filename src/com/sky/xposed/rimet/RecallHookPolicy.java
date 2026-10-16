package com.sky.xposed.rimet;

final class RecallHookPolicy {
    static final String IM_ENGINE = "com.alibaba.wukong.im.IMEngine";
    static final String IM_MODULE = "com.alibaba.wukong.im.context.IMModule";
    static final String MESSAGE_IMPL = "com.alibaba.wukong.im.message.MessageImpl";
    static final String MESSAGE_SERVICE = "com.alibaba.wukong.im.MessageService";
    static final String IDL_MESSAGE_SERVICE = "com.alibaba.wukong.idl.im.client.IDLMessageService";
    static final String RECALL_METHOD = "recallMessage";
    static final String LOCAL_STATUS_METHOD = "updateLocalRecallStatus";
    static final String BATCH_RECALL_METHOD = "recallMessageBatch";
    static final String GET_IM_SERVICE = "getIMService";
    static final String GET_SERVICE = "getService";

    static boolean isRecallServiceTypeName(String name) {
        return MESSAGE_SERVICE.equals(name) || IDL_MESSAGE_SERVICE.equals(name);
    }

    private RecallHookPolicy() {}
}
