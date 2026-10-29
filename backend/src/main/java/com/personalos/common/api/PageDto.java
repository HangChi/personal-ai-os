package com.personalos.common.api;

import java.util.List;

/**
 * 分页视图（技术债清偿：替代原先「limit 静默截断」的列表返回）。
 *
 * <p><b>为什么需要它</b>：阶段 2/4 的列表接口只有一个 {@code limit} 参数——
 * 数据量超过 limit 时，多出来的记录**静默消失**，用户以为「就这么多」。
 * 分页把「有多少」({@code total}) 与「当前这一页」({@code items}) 分开表达，
 * 截断从「无声」变成「可翻页」。
 *
 * <p><b>排序只有一处真相</b>：调用方先排好序再整体交给 {@link #of(List, int, int)} 切片，
 * 分页不参与排序。这样避免「SQL 排序」与「内存 comparator」两套顺序漂移
 * （个人量级下加载全量再切片的成本可忽略；真有规模问题时应改 SQL 排序并删除内存 comparator，
 * 而不是让两套顺序共存）。
 *
 * @param items 当前页数据
 * @param page  页码（从 0 开始）
 * @param size  每页条数
 * @param total 符合条件的总条数（不是本页条数）
 */
public record PageDto<T>(List<T> items, int page, int size, long total) {

    /**
     * 从**已排好序**的全量列表中切出指定页。
     *
     * @param all  已排序的全量数据
     * @param page 页码，负值按 0 处理
     * @param size 每页条数，非正值按 1 处理（防御性收敛，正常路径由调用方归一化）
     */
    public static <T> PageDto<T> of(List<T> all, int page, int size) {
        int effectivePage = Math.max(page, 0);
        int effectiveSize = Math.max(size, 1);
        // 用 long 计算起点：`page * size` 以 int 相乘时，page=21474837、size=100
        // 会溢出成负数，`from >= all.size()` 判不出来，subList 收到负下标直接抛
        // IndexOutOfBoundsException —— 一条构造出来的查询参数就能稳定打出 500。
        long from = (long) effectivePage * effectiveSize;
        if (from >= all.size()) {
            // 越界页返回空列表，而不是报错：翻到太后面是正常的用户行为
            return new PageDto<>(List.of(), effectivePage, effectiveSize, all.size());
        }
        // 此处 from < all.size()，必然落在 int 范围内，转换安全
        int start = (int) from;
        int end = (int) Math.min(from + effectiveSize, all.size());
        return new PageDto<>(List.copyOf(all.subList(start, end)), effectivePage, effectiveSize, all.size());
    }

    /**
     * 从「数据库已经分好页」的结果构造（排序与切片都在 SQL 完成）。
     *
     * <p>与 {@link #of(List, int, int)} 并存而不是替换它：两者适用于不同场景，
     * 且混用会立刻暴露问题——
     * <ul>
     *   <li>{@code of}：数据量小、顺序依赖 Java 语义（如自定义 comparator），把全量读进内存再切；</li>
     *   <li>{@code ofPage}：数据量可能很大、顺序能用 SQL 表达（ORDER BY + NULLS LAST）。</li>
     * </ul>
     * <b>同一个查询只能用一种</b>：先 SQL 排序再交给 {@code of} 切片，等于用两套顺序，
     * 翻页会出现重复或漏项。
     *
     * @param items 数据库返回的当前页
     * @param page  页码（从 0 开始）
     * @param size  每页条数
     * @param total 符合条件的总条数（由 {@code count} 查询得到，不是本页条数）
     */
    public static <T> PageDto<T> ofPage(List<T> items, int page, int size, long total) {
        return new PageDto<>(List.copyOf(items), Math.max(page, 0), Math.max(size, 1), total);
    }

    /**
     * 页码 × 每页条数是否会溢出 {@code int}（= 交给 SQL 做 offset 时会炸）。
     *
     * <p><b>为什么要单独判</b>：走 SQL 分页的接口把 {@code page}/{@code size} 直接交给
     * {@code PageRequest.of}，而 Spring Data 计算 offset 时用 {@code int}。
     * `page=21474837 & size=100` 会抛 {@code Page offset exceeds Integer.MAX_VALUE}，
     * 落到全局兜底就是 **500 + 一条假堆栈**（2026-10-29 端到端实测，会话搜索与工具审计
     * 两个接口同时中招）。页码是纯客户端输入，不该有能力制造 500。
     *
     * <p>为什么在 {@code PageDto} 而不在各自的 Query 里把 page 拍上限：拍上限会把
     * 「翻太深」伪装成「有数据但页号被改过」，前端手里的 page 与响应里的 page
     * 不一致；而 `TaskService` 已经确立了「算一次 long、越界就返回空页但仍查 total」
     * 的做法。这里只把那个判断抽出来共用，行为不变。
     */
    public static boolean offsetOverflows(int page, int size) {
        return (long) Math.max(page, 0) * Math.max(size, 0) > Integer.MAX_VALUE;
    }
}
