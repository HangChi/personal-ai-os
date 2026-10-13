package com.personalos.task.dto;

import com.personalos.task.entity.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 创建任务请求。
 *
 * @param title       标题，必填
 * @param description 描述，可空
 * @param priority    优先级，可空（默认 MEDIUM）
 * @param dueAt       截止时间，可空
 */
public record CreateTaskRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        @Size(max = 2000, message = "描述长度不能超过 2000")
        String description,

        TaskPriority priority,

        Instant dueAt) {
}
