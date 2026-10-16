package com.sky.xposed.rimet;

public final class LocationHookPolicyTest {
    public static void main(String[] args) {
        assertContains(LocationHookPolicy.DIRECT_SERVICE_METHODS, "b");
        assertContains(LocationHookPolicy.DIRECT_SERVICE_METHODS, "i");
        assertContains(LocationHookPolicy.IMPLEMENTATION_METHODS, "getCurrentLocation");
        assertContains(LocationHookPolicy.IMPLEMENTATION_METHODS, "startPersistentLocation");
        System.out.println("LocationHookPolicyTest: PASS");
    }

    private static void assertContains(String[] values, String expected) {
        for (String value : values) {
            if (expected.equals(value)) return;
        }
        throw new AssertionError("Missing " + expected);
    }
}
