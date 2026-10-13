package com.personalos.task.dto;

import com.personalos.task.entity.TaskStatus;

/**
 * 任务查询条件。
 *
 * @param status  状态筛选，可空表示不限
 * @param keyword 关键词，匹配标题或描述，可空
 * @param limit   返回条数上限
 */
public record TaskQuery(TaskStatus status, String keyword, int limit) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public TaskQuery {
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
    }

    public static TaskQuery all() {
        return new TaskQuery(null, null, DEFAULT_LIMIT);
    }
}
