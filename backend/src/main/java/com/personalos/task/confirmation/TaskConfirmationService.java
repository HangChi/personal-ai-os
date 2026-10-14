package com.personalos.task.confirmation;

import com.personalos.common.confirmation.ConfirmationAction;
import com.personalos.common.confirmation.ConfirmationDto;
import com.personalos.common.confirmation.ConfirmationService;
import com.personalos.task.confirmation.TaskConfirmationPayloads.DeleteTasks;
import com.personalos.task.confirmation.TaskConfirmationPayloads.ScheduleTask;
import com.personalos.task.confirmation.TaskConfirmationPayloads.SetPriority;
import com.personalos.task.entity.TaskPriority;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务侧的「待确认事项」构造器。
 *
 * <p>职责边界：本类只负责**把业务语义翻译成一段人类可读的 summary 与结构化的 payload**，
 * 令牌、有效期、单次性这些机制由 {@link ConfirmationService} 负责。
 *
 * <p>summary 必须在服务端生成（不采信模型输出的文本），否则模型可以写一个
 * 「删除 1 个任务：不重要的临时文件」的描述，而 payload 里却是别的任务。
 */
@Service
public class TaskConfirmationService {

    private final ConfirmationService confirmations;

    public TaskConfirmationService(ConfirmationService confirmations) {
        this.confirmations = confirmations;
    }

    /**
     * 申请删除任务。
     *
     * @param taskIds        目标任务
     * @param titles         标题快照（任务删除后仍可审计删了什么）
     * @param conversationId 发起会话，可空
     */
    @Transactional
    public ConfirmationDto requestDeleteTasks(List<UUID> taskIds, List<String> titles, String conversationId) {
        String summary = titles.size() == 1
                ? "删除任务：" + titles.get(0)
                : "删除 " + titles.size() + " 个任务：" + String.join("、", titles);
        return confirmations.create(ConfirmationAction.DELETE_TASK, summary,
                new DeleteTasks(taskIds, titles), taskIds.size(), conversationId);
    }

    /**
     * 申请修改任务截止时间。
     */
    @Transactional
    public ConfirmationDto requestScheduleTask(UUID taskId, String title, Instant dueAt, String conversationId) {
        String summary = dueAt == null
                ? "清除任务「" + title + "」的截止时间"
                : "把任务「" + title + "」的截止时间改为 " + dueAt;
        return confirmations.create(ConfirmationAction.SCHEDULE_TASK, summary,
                new ScheduleTask(taskId, title, dueAt), 1, conversationId);
    }

    /**
     * 申请修改任务优先级。
     */
    @Transactional
    public ConfirmationDto requestSetPriority(UUID taskId, String title, TaskPriority priority,
                                              String conversationId) {
        String summary = "把任务「" + title + "」的优先级改为 " + priority;
        return confirmations.create(ConfirmationAction.SET_PRIORITY, summary,
                new SetPriority(taskId, title, priority), 1, conversationId);
    }
}
