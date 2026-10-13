package com.personalos.common.exception;

import com.personalos.common.api.ErrorCode;

/**
 * 资源不存在异常（HTTP 404 语义）。
 *
 * <p>继承 {@link BusinessException} 以复用统一处理链路，同时提供明确的语义命名，
 * 便于后续阶段按资源类型细分。
 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }
}
