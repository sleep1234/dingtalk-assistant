package com.sky.xposed.rimet;

public final class SafetyCheckPolicyTest {
    public static void main(String[] args) {
        testAdbSecurePropertyIsNotClassifiedAsDebugFlag();
        testDebugPropertiesReturnDisabledValues();
        testUnrelatedPropertiesAreNotChanged();
        System.out.println("SafetyCheckPolicyTest: PASS");
    }

    private static void testAdbSecurePropertyIsNotClassifiedAsDebugFlag() {
        assertEquals("1", SafetyCheckPolicy.valueFor("ro.adb.secure"));
    }

    private static void testDebugPropertiesReturnDisabledValues() {
        assertEquals("0", SafetyCheckPolicy.valueFor("ro.debuggable"));
        assertEquals("0", SafetyCheckPolicy.valueFor("ro.adb_enabled"));
        assertEquals("0", SafetyCheckPolicy.valueFor("ro.allow.mock.location"));
        assertEquals("stopped", SafetyCheckPolicy.valueFor("init.svc.adbd"));
        assertEquals("none", SafetyCheckPolicy.valueFor("sys.usb.state"));
    }

    private static void testUnrelatedPropertiesAreNotChanged() {
        if (SafetyCheckPolicy.valueFor("ro.product.model") != null) {
            throw new AssertionError("Unrelated property should not be changed");
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
