package com.personalos.chat.dto;

import java.util.List;

/**
 * 用量统计（清单第 7 项）。
 *
 * <h2>为什么带 {@code hasData} 与 {@code estimatedTokens}</h2>
 * 两者都是为了让界面**不撒谎**：
 * <ul>
 *   <li>{@code hasData=false} 时前端显示「还没有用量记录」，而不是画一张
 *       全零的趋势图 —— 后者看起来像统计坏了；</li>
 *   <li>{@code estimatedTokens} 把这部分单独列出，用户才知道总数里有多少
 *       是「字符 ÷ 1.6」折算的估算，而不是模型账单上的数字。
 *       真实与估算混在一起显示一个总和，是把估算冒充成计费口径。</li>
 * </ul>
 *
 * @param days            覆盖天数（含今天）
 * @param totalPrompt     窗口内提示 token 合计
 * @param totalCompletion 窗口内生成 token 合计
 * @param totalTokens     两者之和
 * @param turns           窗口内对话轮次（写入行数）
 * @param actualTokens    其中来自上游真实 usage 的 token 数
 * @param estimatedTokens 其中属于估算的 token 数
 * @param hasData         窗口内是否有任何记录
 * @param daily           按日明细（升序，只含**有数据的日子**，由前端补零）
 */
public record UsageStatsDto(int days,
                            long totalPrompt,
                            long totalCompletion,
                            long totalTokens,
                            long turns,
                            long actualTokens,
                            long estimatedTokens,
                            boolean hasData,
                            List<DailyUsageDto> daily) {

    /**
     * 单日用量。
     *
     * @param date            本地日期 {@code yyyy-MM-dd}
     * @param promptTokens    提示 token
     * @param completionTokens 生成 token
     * @param totalTokens     两者之和
     * @param turns           轮次
     * @param estimated       true 表示这一天**全部**数字都是估算（部分估算无法按日拆分时取保守值）
     */
    public record DailyUsageDto(String date,
                                long promptTokens,
                                long completionTokens,
                                long totalTokens,
                                long turns,
                                boolean estimated) {
    }
}
