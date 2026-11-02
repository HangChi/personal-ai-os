package com.personalos.common.ai.tool;

/** 只发布可核对的执行状态，不发布模型隐藏推理或工具原始敏感参数。 */
@FunctionalInterface
public interface ToolProgress {
    void publish(String id, String tool, String status, long durationMs);
}
