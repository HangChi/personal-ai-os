package com.personalos.task.dto;

import com.personalos.task.entity.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * 创建任务请求。
 *
 * @param title       标题，必填
 * @param description 描述，可空
 * @param priority    优先级，可空（默认 MEDIUM）
 * @param dueAt       截止时间，可空
 * @param tags        标签，0–5 个；归一化与上限校验在 Service 层（DTO 不加 {@code @Size}：
 *                    注解校验的是原始长度，而业务要求去空白后的长度）
 */
public record CreateTaskRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        @Size(max = 2000, message = "描述长度不能超过 2000")
        String description,

        TaskPriority priority,

        Instant dueAt,

        List<String> tags) {
}
