package com.personalos.common.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话上下文治理配置（前缀 {@code app.context}）。
 *
 * <p>刻意与 {@code app.ai} 分开：那边是「用哪个模型、带多少窗口」，
 * 这里是「窗口之外的历史怎么处理」。两者的调整频率与风险完全不同 ——
 * 关掉摘要不影响对话本身，关掉模型配置则整个功能不可用。
 *
 * <p><b>注意</b>：配置记录只能有**一个**构造器。给它加便捷重载会让 Spring Boot 的
 * 构造器绑定挑错那个，导致字段静默不绑定（2026-10-26 在 AiProperties.Memory 上踩过）。
 *
 * @param summary    滚动摘要
 * @param autoMemory 记忆自动沉淀
 */
@ConfigurationProperties(prefix = "app.context")
public record ContextProperties(Summary summary, AutoMemory autoMemory) {

    public ContextProperties {
        if (summary == null) {
            summary = new Summary(true, 0, 0, 0, null);
        }
        if (autoMemory == null) {
            autoMemory = new AutoMemory(false, 0);
        }
    }

    /**
     * 滚动摘要：把已滑出上下文窗口的历史压成一段文字，注入系统提示。
     *
     * @param enabled         总开关。关掉后既不生成也不注入，行为回到「只有窗口」
     * @param minMessages     触发一次摘要所需的最少新增消息条数。
     *                        太小会让每轮都调一次模型；太大则摘要长期滞后
     * @param maxExcerptChars 送给摘要模型的原文上限（截断到最近这么多字符）
     * @param maxSummaryChars 摘要本身的长度上限（超出截断）
     * @param timeout         单次摘要调用的等待上限。超时即放弃本次、保留旧摘要 ——
     *                        模型卡住时不能无限占着后台线程（那里只有 1~2 个）
     */
    public record Summary(boolean enabled, int minMessages, int maxExcerptChars, int maxSummaryChars,
                          Duration timeout) {

        private static final int DEFAULT_MIN_MESSAGES = 4;
        private static final int DEFAULT_MAX_EXCERPT_CHARS = 4000;
        private static final int DEFAULT_MAX_SUMMARY_CHARS = 1200;
        private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

        public Summary {
            if (minMessages <= 0) {
                minMessages = DEFAULT_MIN_MESSAGES;
            }
            if (maxExcerptChars <= 0) {
                maxExcerptChars = DEFAULT_MAX_EXCERPT_CHARS;
            }
            if (maxSummaryChars <= 0) {
                maxSummaryChars = DEFAULT_MAX_SUMMARY_CHARS;
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                timeout = DEFAULT_TIMEOUT;
            }
        }
    }

    /**
     * 记忆自动沉淀：每轮结束后让模型从对话里抽取值得长期记住的陈述。
     *
     * @param enabled  总开关，**默认关闭**。开启前请先接受「模型可能把一次性偏好
     *                 固化成永久约束」这一风险 —— 这是本功能唯一需要人工判断的地方
     * @param maxItems 每轮最多沉淀条数
     */
    public record AutoMemory(boolean enabled, int maxItems) {

        private static final int DEFAULT_MAX_ITEMS = 3;

        public AutoMemory {
            if (maxItems <= 0) {
                maxItems = DEFAULT_MAX_ITEMS;
            }
        }
    }
}
