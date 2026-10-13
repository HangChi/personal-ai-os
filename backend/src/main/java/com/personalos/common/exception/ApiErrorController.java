package com.personalos.common.exception;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 容器级错误端点的统一改造。
 *
 * <p>Spring MVC 中由 {@link GlobalExceptionHandler} 处理的异常不经过此处；
 * 本类负责那些绕过 Controller 直接派发到 {@code /error} 的情形
 * （如容器层拒绝的请求、未进入 DispatcherServlet 的错误），
 * 确保它们同样返回统一响应结构。
 *
 * <p>实现 {@link ErrorController} 标记接口后，Spring Boot 自带的
 * {@code BasicErrorController} 会自动退让。
 */
@RestController
public class ApiErrorController implements ErrorController {

    /**
     * 接管 {@code /error}，把 HTTP 状态映射为项目错误码。
     */
    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<ApiResponse<Void>> handleError(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request);
        ErrorCode errorCode = toErrorCode(status);
        return ResponseEntity.status(status).body(ApiResponse.error(errorCode));
    }

    private HttpStatus resolveStatus(HttpServletRequest request) {
        Object rawStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (rawStatus == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        try {
            return HttpStatus.valueOf(Integer.parseInt(rawStatus.toString()));
        } catch (IllegalArgumentException ex) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    private ErrorCode toErrorCode(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            case CONFLICT -> ErrorCode.BUSINESS_CONFLICT;
            case BAD_REQUEST -> ErrorCode.VALIDATION_FAILED;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_FAILED;
        };
    }
}
