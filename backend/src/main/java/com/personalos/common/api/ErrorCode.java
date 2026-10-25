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

    /** 幂等键与请求内容不一致。 */
    IDEMPOTENCY_KEY_CONFLICT(40001, "幂等键与请求内容不一致，同一幂等键必须对应相同请求", HttpStatus.CONFLICT),

    /** 待确认操作已失效（已确认、已取消或已过期）。 */
    CONFIRMATION_NOT_ACTIVE(40002, "该确认已失效或已被使用", HttpStatus.CONFLICT),

    /** 不支持的文档：类型不允许、内容为空或超出大小上限。 */
    UNSUPPORTED_DOCUMENT(40003, "不支持的文档", HttpStatus.BAD_REQUEST),

    /** 资源不存在。 */
    RESOURCE_NOT_FOUND(40004, "资源不存在", HttpStatus.NOT_FOUND),

    /** 请求方法不被支持。 */
    METHOD_NOT_ALLOWED(40005, "请求方法不被支持", HttpStatus.METHOD_NOT_ALLOWED),

    /** 未登录或会话已过期（阶段 15）。 */
    UNAUTHORIZED(40100, "未登录或登录已过期", HttpStatus.UNAUTHORIZED),

    /** 业务规则冲突（如状态不允许的流转）。 */
    BUSINESS_CONFLICT(40900, "业务规则冲突", HttpStatus.CONFLICT),

    /** AI 服务调用失败（上游模型不可用或返回错误）。 */
    AI_UPSTREAM_ERROR(50200, "AI 服务调用失败，请稍后重试或检查模型配置", HttpStatus.BAD_GATEWAY),

    /** 嵌入维度与向量表结构不一致。 */
    EMBEDDING_DIMENSION_MISMATCH(50201,
            "嵌入模型输出维度与向量表结构不一致，请检查嵌入模型配置或新增迁移重建向量列",
            HttpStatus.INTERNAL_SERVER_ERROR),

    /** AI 模型未配置（未提供任何可用的 ChatModel）。 */
    AI_NOT_CONFIGURED(50300, "AI 模型未配置，请设置 AI_CHAT_PROVIDER 为 ollama 或 openai", HttpStatus.SERVICE_UNAVAILABLE),

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
