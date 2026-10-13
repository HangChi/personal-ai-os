package com.personalos.common.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/**
 * 统一响应信封。
 *
 * <p>所有 HTTP 接口（成功与失败）均返回该结构，前端只需判断 {@code code}：
 *
 * <pre>
 * { "code": 0, "message": "OK", "data": {...}, "timestamp": "2026-10-14T00:20:00Z" }
 * </pre>
 *
 * @param code      业务码，0 表示成功
 * @param message   提示信息
 * @param data      数据体，失败时为 {@code null}
 * @param timestamp 生成时间（UTC）
 * @param <T>       数据体类型
 */
public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {

    /**
     * 构造成功响应。
     *
     * @param data 数据体，允许为 {@code null}
     * @return 成功的统一响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getDefaultMessage(), data, Instant.now());
    }

    /**
     * 构造失败响应，使用错误码的默认文案。
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.getDefaultMessage());
    }

    /**
     * 构造失败响应，使用自定义文案。
     *
     * <p>注意：自定义文案不得包含堆栈、类名或 SQL 片段（契约 SC-007）。
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, Instant.now());
    }

    /**
     * @return 是否为成功响应
     */
    @JsonIgnore
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }
}
