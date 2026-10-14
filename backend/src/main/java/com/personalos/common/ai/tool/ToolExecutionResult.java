package com.personalos.common.ai.tool;

/**
 * 工具的统一返回信封。
 *
 * <p>序列化为 JSON 文本交给模型。之所以用**类型化记录而不是裸文本**，
 * 是因为模型需要能明确区分「已执行」「待确认」「被拒绝」——
 * 如果都只是自然语言，模型很容易把「需要确认」复述成「已经帮你删掉了」。
 *
 * @param status         见 {@link ToolStatus}
 * @param message        面向模型与人类的中文说明
 * @param data           结果数据，可为空
 * @param confirmationId 需确认时才有；**不含令牌**
 */
public record ToolExecutionResult(String status, String message, Object data, String confirmationId) {

    public static ToolExecutionResult ok(String message, Object data) {
        return new ToolExecutionResult(ToolStatus.OK.name(), message, data, null);
    }

    /**
     * 已生成待确认事项，**尚未执行**。
     */
    public static ToolExecutionResult requiresConfirmation(String message, String confirmationId) {
        return new ToolExecutionResult(ToolStatus.REQUIRES_CONFIRMATION.name(), message, null, confirmationId);
    }

    /**
     * 入参非法，未执行任何变更。
     */
    public static ToolExecutionResult rejected(String message) {
        return new ToolExecutionResult(ToolStatus.REJECTED.name(), message, null, null);
    }

    /**
     * 本轮内该操作已执行过，未重复执行。
     */
    public static ToolExecutionResult duplicate(String message, Object data) {
        return new ToolExecutionResult(ToolStatus.DUPLICATE.name(), message, data, null);
    }

    public static ToolExecutionResult error(String message) {
        return new ToolExecutionResult(ToolStatus.ERROR.name(), message, null, null);
    }

    /** 工具结果状态 */
    public enum ToolStatus {
        /** 执行成功 */
        OK,
        /** 已生成待确认事项，尚未执行 */
        REQUIRES_CONFIRMATION,
        /** 本轮内已执行过，未重复执行 */
        DUPLICATE,
        /** 入参非法，未执行 */
        REJECTED,
        /** 执行出错 */
        ERROR
    }
}
