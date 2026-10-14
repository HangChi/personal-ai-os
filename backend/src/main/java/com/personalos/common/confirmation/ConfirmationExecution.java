package com.personalos.common.confirmation;

/**
 * 确认执行的结果。
 *
 * @param executed      是否真的执行了变更
 * @param affectedCount 实际影响的行数
 * @param summary       面向人类的结果描述
 */
public record ConfirmationExecution(boolean executed, int affectedCount, String summary) {
}
