package com.sky.xposed.rimet;

public final class RecallHookPolicyTest {
    public static void main(String[] args) {
        assertEquals("com.alibaba.wukong.im.message.MessageImpl", RecallHookPolicy.MESSAGE_IMPL);
        assertEquals("com.alibaba.wukong.idl.im.client.IDLMessageService", RecallHookPolicy.IDL_MESSAGE_SERVICE);
        assertEquals("com.alibaba.wukong.im.MessageService", RecallHookPolicy.MESSAGE_SERVICE);
        assertEquals("com.alibaba.wukong.im.IMEngine", RecallHookPolicy.IM_ENGINE);
        assertTrue(RecallHookPolicy.isRecallServiceTypeName(RecallHookPolicy.MESSAGE_SERVICE));
        assertTrue(RecallHookPolicy.isRecallServiceTypeName(RecallHookPolicy.IDL_MESSAGE_SERVICE));
        assertFalse(RecallHookPolicy.isRecallServiceTypeName("java.lang.String"));
        assertEquals("recallMessage", RecallHookPolicy.RECALL_METHOD);
        assertEquals("updateLocalRecallStatus", RecallHookPolicy.LOCAL_STATUS_METHOD);
        System.out.println("RecallHookPolicyTest: PASS");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) throw new AssertionError("Expected true");
    }

    private static void assertFalse(boolean value) {
        if (value) throw new AssertionError("Expected false");
    }
}
