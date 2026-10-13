package com.personalos.task.dto;

import com.personalos.task.entity.TaskPriority;
import com.personalos.task.entity.TaskStatus;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 修改任务请求。
 *
 * <p>所有字段可选，仅更新**出现**的字段。
 *
 * <p>注意语义边界：{@code dueAt} 与 {@code priority} 属于「关键字段」，
 * 由 Agent 通道修改时需要人工确认（见 specs/003-task-agent/spec.md FR-008）；
 * 本 DTO 同时被 REST 直连使用 —— 人类直连修改无需确认。
 *
 * <p><b>为什么需要 clearDueAt</b>：record 的 null 无法同时表达「字段未提供」与
 * 「显式置空」这两件事。若把 {@code dueAt == null} 解释成「清除」，
 * 那么客户端只想改标题时（不传 dueAt）会意外把截止时间清掉。因此清除意图必须显式声明。
 *
 * @param title       新标题
 * @param description 新描述
 * @param priority    新优先级
 * @param dueAt       新截止时间
 * @param clearDueAt  显式要求清除截止时间
 * @param status      新状态
 */
public record UpdateTaskRequest(
        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        @Size(max = 2000, message = "描述长度不能超过 2000")
        String description,

        TaskPriority priority,

        Instant dueAt,

        Boolean clearDueAt,

        TaskStatus status) {
}
