package com.sky.xposed.rimet;

import java.util.HashMap;
import java.util.Map;

/** Builds the shared location file consumed by LocationHook. */
final class LocationConfig {
    private LocationConfig() {}

    static String render(String existing, double wgsLat, double wgsLng,
            double gcjLat, double gcjLng, String address) {
        Map<String, String> values = parse(existing);
        String enabled = values.containsKey("enabled") ? values.get("enabled") : "1";
        String safeAddress = sanitize(address);

        StringBuilder result = new StringBuilder();
        append(result, "enabled", enabled);
        append(result, "lat", Double.toString(wgsLat));
        append(result, "lng", Double.toString(wgsLng));
        append(result, "lat_gcj", Double.toString(gcjLat));
        append(result, "lng_gcj", Double.toString(gcjLng));
        append(result, "addr", safeAddress);
        return result.toString();
    }

    static String setEnabled(String existing, boolean enabled) {
        Map<String, String> values = parse(existing);
        values.put("enabled", enabled ? "1" : "0");

        StringBuilder result = new StringBuilder();
        append(result, "enabled", values.get("enabled"));
        appendIfPresent(result, values, "lat");
        appendIfPresent(result, values, "lng");
        appendIfPresent(result, values, "lat_gcj");
        appendIfPresent(result, values, "lng_gcj");
        appendIfPresent(result, values, "addr");
        return result.toString();
    }

    static boolean enabledFrom(String content, boolean defaultValue) {
        Map<String, String> values = parse(content);
        String value = values.get("enabled");
        if (value == null) return defaultValue;
        return !"0".equals(value) && !"false".equalsIgnoreCase(value);
    }

    private static Map<String, String> parse(String content) {
        Map<String, String> values = new HashMap<>();
        if (content == null) return values;
        for (String line : content.split("\\r?\\n")) {
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            String key = line.substring(0, separator).trim();
            if (key.length() == 0) continue;
            values.put(key, line.substring(separator + 1).trim());
        }
        return values;
    }

    private static void append(StringBuilder result, String key, String value) {
        result.append(key).append('=').append(value == null ? "" : value).append('\n');
    }

    private static void appendIfPresent(StringBuilder result, Map<String, String> values, String key) {
        if (values.containsKey(key)) append(result, key, values.get(key));
    }

    private static String sanitize(String address) {
        if (address == null) return "";
        return address.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
