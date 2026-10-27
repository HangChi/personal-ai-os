package com.personalos.chat.entity;

/**
 * 用量数字的来源。
 *
 * <h2>为什么必须区分</h2>
 * 两者的可信度完全不同：{@link #ACTUAL} 是上游计费口径的数字（可直接对账），
 * {@link #ESTIMATED} 是按「字符 ÷ 1.6」折算的估算。混在一起展示会让用户
 * 把估算当成账单——这正是「显示一个假数字」的典型形态。
 *
 * <p>什么时候拿不到真实值：非 OpenAI 兼容的 provider（ollama 原生接口、
 * 联调用的 mock server）不返回 usage；部分兼容实现忽略
 * {@code stream_options.include_usage}。这些情况都会降级到 {@link #ESTIMATED}，
 * 而不是「不记录」——不记录会让用量突然出现大段空白，用户更难判断。
 */
public enum TokenUsageSource {

    /** 上游响应里带了 usage，数字与计费口径一致 */
    ACTUAL,

    /** 上游没给 usage，按字符数折算（可能偏保守，仅用于趋势观察） */
    ESTIMATED
}
