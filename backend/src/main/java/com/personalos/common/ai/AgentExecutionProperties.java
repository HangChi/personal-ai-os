package com.personalos.common.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 每轮模型请求与连续失败的硬预算；不会随历史窗口裁剪而重置。 */
@ConfigurationProperties(prefix = "app.agent")
public record AgentExecutionProperties(@DefaultValue("12") int maxModelCalls,
                                       @DefaultValue("2") int maxConsecutiveFailures) {
    public AgentExecutionProperties {
        if (maxModelCalls < 1 || maxConsecutiveFailures < 1) {
            throw new IllegalArgumentException("Agent 执行预算必须为正数");
        }
    }
}
