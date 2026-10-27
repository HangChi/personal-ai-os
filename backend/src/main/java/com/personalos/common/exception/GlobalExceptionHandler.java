package com.personalos.common.exception;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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
     *
     * <p>显式设 {@code Content-Type: application/json}：SSE 端点（{@code Accept: text/event-stream}）
     * 的前置校验失败走这里时，若不做内容协商的例外，Jackson 转换器会因 Accept 不匹配而被拒绝，
     * 响应变成 406 —— 客户端拿不到错误码，只看到「无法接受的响应」。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("业务异常: code={}, message={}", errorCode.getCode(), ex.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
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
                .contentType(MediaType.APPLICATION_JSON)
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
                .contentType(MediaType.APPLICATION_JSON)
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
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, message));
    }

    /**
     * 未匹配到任何处理器（Spring 7 对静态资源路径抛出的资源未找到）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("资源未找到: {}", ex.getResourcePath());
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 未匹配到任何处理器（显式要求抛异常时）。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandlerFound(NoHandlerFoundException ex) {
        log.warn("未找到处理器: {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 上传文件超过容器限制。
     *
     * <p>应用层已有更严格且更可读的大小校验；这一条是兜底 —— 若没有它，
     * 超限文件会走兜底分支变成 500，用户完全看不出是"文件太大"。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超过容器限制: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.UNSUPPORTED_DOCUMENT.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.UNSUPPORTED_DOCUMENT, "文件过大，请压缩或拆分后重新上传"));
    }

    /**
     * 请求体无法解析（畸形 JSON、类型不匹配的字段）。
     *
     * <p>不显式处理会落到兜底分支：客户端发错内容却收到 500 + 一行 error 级堆栈，
     * 前端 5xx 拦截器还会把它当服务端故障提示。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        // 只记异常类型的摘要，不回显请求体：请求体可能含用户隐私
        log.warn("请求体无法解析: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, "请求体格式错误，请检查 JSON 结构与字段类型"));
    }

    /**
     * 缺少必需的请求参数或 multipart 分片。
     *
     * <p>典型场景：{@code POST /chat/uploads} 不带 {@code file} 字段。缺参数是客户端问题，
     * 不该报成 500。
     */
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(Exception ex) {
        String name = ex instanceof MissingServletRequestPartException part
                ? part.getRequestPartName()
                : ((MissingServletRequestParameterException) ex).getParameterName();
        String message = ErrorCode.VALIDATION_FAILED.getDefaultMessage() + ": 缺少参数 " + name;
        log.warn("缺少必需的请求参数: {}", name);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, message));
    }

    /**
     * 请求方法不被支持（如对只收 POST 的端点发 GET）。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不被支持: method={}, supported={}", ex.getMethod(), ex.getSupportedHttpMethods());
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED,
                        "请求方法 " + ex.getMethod() + " 不被支持"));
    }

    /**
     * 数据库约束冲突（超出列宽、唯一键冲突等）。
     *
     * <p>以前这类异常落到兜底分支，用户看到"服务器内部错误"，完全无法定位是哪条数据坏了
     * ——导入功能里最常见（标题超过 200 字符、文件名超过 300 字符）。
     * 对外只给方向性提示，具体的约束名与 SQL 只进服务端日志。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("数据库约束冲突: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(ErrorCode.BUSINESS_CONFLICT.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.BUSINESS_CONFLICT,
                        "数据不符合服务端约束（可能超出长度限制或与已有记录冲突），请检查后重试"));
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
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }
}
