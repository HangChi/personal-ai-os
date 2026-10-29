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

    /**
     * 无法按客户端要求的 {@code Accept} 生成响应。
     *
     * <p>典型场景：导出接口只产出 {@code text/markdown} 或文件流，而客户端声明
     * 只接受 {@code application/json}。这是**内容协商失败**，不是服务端故障 ——
     * 没有这个码时它会落到兜底分支变成 500 + 一条假堆栈（2026-10-29 实测）。
     */
    NOT_ACCEPTABLE(40600, "响应格式不被支持", HttpStatus.NOT_ACCEPTABLE),

    /** 未登录或会话已过期（阶段 15）。 */
    UNAUTHORIZED(40100, "未登录或登录已过期", HttpStatus.UNAUTHORIZED),

    /** 业务规则冲突（如状态不允许的流转）。 */
    BUSINESS_CONFLICT(40900, "业务规则冲突", HttpStatus.CONFLICT),

    /** 模型调用被限流（短时间内请求过多）。 */
    RATE_LIMITED(42900, "请求过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),

    /** AI 服务调用失败（上游模型不可用或返回错误）。 */
    AI_UPSTREAM_ERROR(50200, "AI 服务调用失败，请稍后重试或检查模型配置", HttpStatus.BAD_GATEWAY),

    /** 嵌入维度与向量表结构不一致。 */
    EMBEDDING_DIMENSION_MISMATCH(50201,
            "嵌入模型输出维度与向量表结构不一致，请检查嵌入模型配置或新增迁移重建向量列",
            HttpStatus.INTERNAL_SERVER_ERROR),

    /**
     * 流式回答超时（整条流超过存活上限仍未结束）。
     *
     * <p>单独一个码而不是复用 {@link #AI_UPSTREAM_ERROR}：超时与「上游报错」的重试策略不同 ——
     * 超时通常是思考预算过大或模型过载，用户降低思考深度就能改善；
     * 而上游报错多半是配置或网络问题。
     */
    AI_TIMEOUT(50202, "模型响应超时，请稍后重试或降低思考深度", HttpStatus.GATEWAY_TIMEOUT),

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
