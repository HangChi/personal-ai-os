package com.personalos.common.exception;

import com.personalos.common.api.ErrorCode;

/**
 * 业务异常。
 *
 * <p>由 Service 层在业务规则不满足时抛出，由 {@link GlobalExceptionHandler} 统一转换为
 * 统一响应结构。Controller 不应吞掉该异常。
 */
public class BusinessException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
