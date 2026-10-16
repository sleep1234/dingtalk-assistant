package com.sky.xposed.rimet;

public final class LocationConfigTest {
    public static void main(String[] args) {
        testEmptyConfigGetsAllKeys();
        testExistingEnabledValueIsPreserved();
        testAddressCannotBreakTheLineFormat();
        testTogglePreservesExistingCoordinates();
        testEnabledReadsPublicConfigValue();
        testMissingEnabledUsesDefault();
        System.out.println("LocationConfigTest: PASS");
    }

    private static void testEmptyConfigGetsAllKeys() {
        String result = LocationConfig.render("", 28.1001, 121.2002, 28.1011, 121.2012, "Office");

        assertContains(result, "enabled=1\n");
        assertContains(result, "lat=28.1001\n");
        assertContains(result, "lng=121.2002\n");
        assertContains(result, "lat_gcj=28.1011\n");
        assertContains(result, "lng_gcj=121.2012\n");
        assertContains(result, "addr=Office\n");
    }

    private static void testExistingEnabledValueIsPreserved() {
        String result = LocationConfig.render("enabled=0\nlat=1\n", 28.1001, 121.2002, 28.1011, 121.2012, "Office");

        assertContains(result, "enabled=0\n");
        assertNotContains(result, "enabled=1\n");
    }

    private static void testAddressCannotBreakTheLineFormat() {
        String result = LocationConfig.render("enabled=1\n", 28.1001, 121.2002, 28.1011, 121.2012, "A\nB\rC");

        assertContains(result, "addr=A B C\n");
        assertNotContains(result, "\nB\n");
    }

    private static void testTogglePreservesExistingCoordinates() {
        String existing = "enabled=1\nlat=28.1001\nlng=121.2002\nlat_gcj=28.1011\nlng_gcj=121.2012\naddr=Office\n";
        String result = LocationConfig.setEnabled(existing, false);

        assertContains(result, "enabled=0\n");
        assertContains(result, "lat=28.1001\n");
        assertContains(result, "lng_gcj=121.2012\n");
        assertContains(result, "addr=Office\n");
    }

    private static void testEnabledReadsPublicConfigValue() {
        if (LocationConfig.enabledFrom("enabled=0\n", true)) {
            throw new AssertionError("enabled=0 must disable virtual location");
        }
        if (!LocationConfig.enabledFrom("enabled=yes\n", false)) {
            throw new AssertionError("non-zero enabled value must enable virtual location");
        }
    }

    private static void testMissingEnabledUsesDefault() {
        if (!LocationConfig.enabledFrom("lat=1\n", true)) {
            throw new AssertionError("missing enabled must use true default");
        }
        if (LocationConfig.enabledFrom("lat=1\n", false)) {
            throw new AssertionError("missing enabled must use false default");
        }
    }

    private static void assertContains(String value, String expected) {
        if (!value.contains(expected)) {
            throw new AssertionError("Expected to find " + quote(expected) + " in " + quote(value));
        }
    }

    private static void assertNotContains(String value, String unexpected) {
        if (value.contains(unexpected)) {
            throw new AssertionError("Did not expect to find " + quote(unexpected) + " in " + quote(value));
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
