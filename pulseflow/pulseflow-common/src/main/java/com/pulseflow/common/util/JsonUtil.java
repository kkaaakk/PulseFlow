package com.pulseflow.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 基于 Jackson 的公共 JSON 序列化与解析工具。
 *
 * Jackson-based JSON utility class for serialization and deserialization.
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /** 隐藏工具类构造入口，避免创建没有状态的工具类实例。 */
    private JsonUtil() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * 把对象序列化为 JSON，失败时包装异常交由调用方处理。
     *
     * Serializes an object to JSON string.
     *
     * @throws RuntimeException if serialization fails
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    /**
     * 按目标类型反序列化 JSON，解析失败时抛出包装异常。
     *
     * Deserializes a JSON string to the specified type.
     *
     * @throws RuntimeException if deserialization fails
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON deserialization failed", e);
        }
    }
}
