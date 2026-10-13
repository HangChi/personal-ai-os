package com.personalos.common.exception;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。
 *
 * <p>保证所有由 Controller 抛出的异常都被转换为统一响应结构（FR-009 / SC-007）。
 *
 * <p><b>安全约束</b>：兜底分支只把通用文案返回给调用方，堆栈与类名仅记录到服务端日志，
 * 绝不进入响应体。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常（含资源不存在）。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("业务异常: code={}, message={}", errorCode.getCode(), ex.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode, ex.getMessage()));
    }

    /**
     * 请求体/参数校验失败（{@code @Valid} 绑定失败）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        String message = ErrorCode.VALIDATION_FAILED.getDefaultMessage()
                + (detail.isBlank() ? "" : ": " + detail);
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, message));
    }

    /**
     * 方法参数约束校验失败（{@code @Validated} 于方法参数上）。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining("; "));
        String message = ErrorCode.VALIDATION_FAILED.getDefaultMessage()
                + (detail.isBlank() ? "" : ": " + detail);
        log.warn("约束校验失败: {}", message);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, message));
    }

    /**
     * 请求参数类型不匹配（例如把非法字符串传给枚举类型的查询参数）。
     *
     * <p>若不显式处理，这类错误会被兜底分支吞成 50000 —— 明明是客户端传错了，
     * 却报成服务器错误，会误导排错方向。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = ErrorCode.VALIDATION_FAILED.getDefaultMessage()
                + ": 参数 " + ex.getName() + " 取值非法";
        log.warn("参数类型不匹配: name={}, value={}", ex.getName(), ex.getValue());
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, message));
    }

    /**
     * 未匹配到任何处理器（Spring 7 对静态资源路径抛出的资源未找到）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("资源未找到: {}", ex.getResourcePath());
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 未匹配到任何处理器（显式要求抛异常时）。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandlerFound(NoHandlerFoundException ex) {
        log.warn("未找到处理器: {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 兜底：任何未预期异常。
     *
     * <p>响应体只包含通用文案，不包含异常类型、消息、堆栈或 SQL 片段。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        log.error("未捕获异常，已按内部错误处理", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }
}
