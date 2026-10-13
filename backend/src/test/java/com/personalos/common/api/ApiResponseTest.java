package com.personalos.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiResponse} 单元测试：锁定对外响应契约。
 */
class ApiResponseTest {

    @Test
    @DisplayName("ok(): code 为 0、message 为 OK、data 原样透传、timestamp 非空")
    void okShouldWrapData() {
        ApiResponse<String> response = ApiResponse.ok("hello");

        assertThat(response.code()).isZero();
        assertThat(response.message()).isEqualTo("OK");
        assertThat(response.data()).isEqualTo("hello");
        assertThat(response.timestamp()).isNotNull();
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("ok(): data 允许为 null（无返回体的成功操作）")
    void okShouldAllowNullData() {
        ApiResponse<Void> response = ApiResponse.ok(null);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.data()).isNull();
    }

    @Test
    @DisplayName("error(): data 为 null、携带指定错误码与自定义文案")
    void errorShouldCarryErrorCodeAndCustomMessage() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.VALIDATION_FAILED, "title 不能为空");

        assertThat(response.code()).isEqualTo(40000);
        assertThat(response.message()).isEqualTo("title 不能为空");
        assertThat(response.data()).isNull();
        assertThat(response.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("error(): 未提供文案时回落到错误码默认文案")
    void errorShouldFallbackToDefaultMessage() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.INTERNAL_ERROR);

        assertThat(response.code()).isEqualTo(50000);
        assertThat(response.message()).isEqualTo(ErrorCode.INTERNAL_ERROR.getDefaultMessage());
    }
}
