package com.personalos.agent.plan;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 计划控制层配置（前缀 {@code app.plan}）。
 *
 * <p>这里的 {@code enabled} 不是「计划功能开关」—— 计划工具与表面永远在。
 * 它关掉的是**引擎对计划的强制注入**：关掉之后计划退回纯记录层
 * （模型要看就自己调 getPlan），行为与阶段 22 一致。
 *
 * @param enabled        是否把进行中的计划注入每一轮系统提示
 * @param maxInjectChars 注入块的字符上限。计划可能有 12 步，全量渲染会挤占上下文
 */
@ConfigurationProperties(prefix = "app.plan")
public record PlanProperties(boolean enabled, int maxInjectChars) {

    private static final int DEFAULT_MAX_INJECT_CHARS = 1200;

    public PlanProperties {
        if (maxInjectChars <= 0) {
            maxInjectChars = DEFAULT_MAX_INJECT_CHARS;
        }
    }
}
