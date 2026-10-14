package com.personalos.task.dto;

import com.personalos.task.entity.TaskStatus;

/**
 * 任务查询条件。
 *
 * <p>2026-10-14 技术债清偿：{@code limit}（静默截断）改为 {@code page + size} 分页，
 * 响应同步改为 {@code PageDto}（含 total）。参数名从 limit 改为 size 是与响应形状
 * 一起做的刻意契约变更——留着 limit 这个名字，使用者无法分清它是「每页大小」还是「硬上限」。
 *
 * @param status  状态筛选，可空表示不限
 * @param keyword 关键词，匹配标题或描述，可空
 * @param page    页码，从 0 开始；负值收敛为 0
 * @param size    每页条数；非正收敛为默认值，超过硬上限收敛为上限
 */
public record TaskQuery(TaskStatus status, String keyword, int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public TaskQuery {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = DEFAULT_SIZE;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
    }

    public static TaskQuery all() {
        return new TaskQuery(null, null, 0, DEFAULT_SIZE);
    }
}
