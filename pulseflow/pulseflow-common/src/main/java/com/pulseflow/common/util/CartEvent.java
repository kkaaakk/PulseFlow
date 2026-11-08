package com.pulseflow.common.util;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Matches the JSON cart identity indexed by MySQL. */
public final class CartEvent {
    private CartEvent() {}

    public static String itemId(Map<String, Object> properties) {
        Object value = properties == null ? null : properties.get("cartItemId");
        if (!(value instanceof String || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger)) return null;
        String itemId = value.toString();
        return itemId.isBlank() || itemId.getBytes(StandardCharsets.UTF_8).length > 256 ? null : itemId;
    }
}
