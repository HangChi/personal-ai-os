package com.personalos.common.api;

import org.springframework.http.HttpStatus;

/**
 * 全局错误码。
 *
 * <p>对外契约见 {@code specs/001-project-skeleton/contracts/http-api.md}：
 * {@code code == 0} 表示成功，非 0 为业务错误码。
 */
public enum ErrorCode {

    /** 成功。 */
    SUCCESS(0, "OK", HttpStatus.OK),

    /** 参数校验失败（Bean Validation）。 */
    VALIDATION_FAILED(40000, "参数校验失败", HttpStatus.BAD_REQUEST),

    /** 资源不存在。 */
    RESOURCE_NOT_FOUND(40004, "资源不存在", HttpStatus.NOT_FOUND),

    /** 请求方法不被支持。 */
    METHOD_NOT_ALLOWED(40005, "请求方法不被支持", HttpStatus.METHOD_NOT_ALLOWED),

    /** 业务规则冲突（如状态不允许的流转）。 */
    BUSINESS_CONFLICT(40900, "业务规则冲突", HttpStatus.CONFLICT),

    /** 服务器内部错误。对外只返回通用文案，不泄漏内部细节。 */
    INTERNAL_ERROR(50000, "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
