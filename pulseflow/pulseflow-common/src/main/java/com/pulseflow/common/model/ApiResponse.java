package com.pulseflow.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 REST 响应，包含状态码、消息及泛型业务数据。
 *
 * Generic API response wrapper for consistent REST API responses.
 *
 * @param <T> the type of the response data payload
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    /**
     * 构造成功响应；带参数时附带业务数据，无参数时返回空数据。
     *
     * Creates a success response with data.
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    /**
     * 构造成功响应；带参数时附带业务数据，无参数时返回空数据。
     *
     * Creates a success response without data.
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200, "success", null);
    }

    /**
     * 构造失败响应，保留自定义状态与消息；仅传消息时使用默认失败码。
     *
     * Creates a failure response with a custom code and message.
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 构造失败响应，保留自定义状态与消息；仅传消息时使用默认失败码。
     *
     * Creates a failure response with the default 500 status code.
     */
    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(500, message, null);
    }
}
