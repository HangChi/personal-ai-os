package com.personalos.task.confirmation;

import com.personalos.task.entity.TaskPriority;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 任务类待确认动作的参数体。
 *
 * <p>这些记录被序列化为 JSON 存入 {@code confirmation.payload}，确认时原样回放。
 *
 * <p><b>为什么把标题也存进来</b>：删除执行后任务就不存在了，若 payload 里只有 id，
 * 事后审计就只能看到一串 UUID，无法回答「当时到底删了什么」。存标题快照使操作记录自解释。
 */
public final class TaskConfirmationPayloads {

    private TaskConfirmationPayloads() {
    }

    /** 删除任务的参数。 */
    public record DeleteTasks(List<UUID> taskIds, List<String> titles) {
    }

    /** 修改截止时间的参数。{@code dueAt} 为 null 表示清除。 */
    public record ScheduleTask(UUID taskId, String title, Instant dueAt) {
    }

    /** 修改优先级的参数。 */
    public record SetPriority(UUID taskId, String title, TaskPriority priority) {
    }
}
