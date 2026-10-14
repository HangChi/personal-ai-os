package com.personalos.task.confirmation;

import com.personalos.common.confirmation.ConfirmationAction;
import com.personalos.common.confirmation.ConfirmationActionHandler;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.json.JsonCodec;
import com.personalos.task.confirmation.TaskConfirmationPayloads.DeleteTasks;
import com.personalos.task.confirmation.TaskConfirmationPayloads.ScheduleTask;
import com.personalos.task.confirmation.TaskConfirmationPayloads.SetPriority;
import com.personalos.task.service.TaskService;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 任务类待确认动作的执行处理器。
 *
 * <p>阶段 2 里这段逻辑曾是通用服务中的一个 {@code switch}；现在它归位到任务模块 ——
 * 通用服务不该知道「任务」这个概念。
 */
@Component
public class TaskConfirmationHandler implements ConfirmationActionHandler {

    private final TaskService taskService;
    private final JsonCodec jsonCodec;

    public TaskConfirmationHandler(TaskService taskService, JsonCodec jsonCodec) {
        this.taskService = taskService;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Set<ConfirmationAction> supportedActions() {
        return Set.of(ConfirmationAction.DELETE_TASK,
                ConfirmationAction.SCHEDULE_TASK,
                ConfirmationAction.SET_PRIORITY);
    }

    @Override
    public int execute(ConfirmationAction action, String payloadJson) {
        // 刻意不写 default 分支：这样一旦 ConfirmationAction 新增取值，
        // 编译器会立刻指出「这里有没覆盖到的动作」，而不是等到运行期才发现某个动作无人处理。
        return switch (action) {
            case DELETE_TASK -> deleteTasks(payloadJson);
            case SCHEDULE_TASK -> scheduleTask(payloadJson);
            case SET_PRIORITY -> setPriority(payloadJson);
            case DELETE_CALENDAR_EVENT -> throw new IllegalStateException(
                    "任务确认处理器收到了不属于自己的动作：" + action);
        };
    }

    private int deleteTasks(String payloadJson) {
        DeleteTasks payload = jsonCodec.fromJson(payloadJson, DeleteTasks.class);
        int affected = taskService.deleteAll(payload.taskIds());
        if (affected == 0) {
            // 目标任务在确认之前已被删除：这不是「执行成功但没影响任何行」，
            // 而是「已经没有可执行的目标」。区分二者，用户才知道到底发生了什么
            throw new ResourceNotFoundException("目标任务已不存在");
        }
        return affected;
    }

    private int scheduleTask(String payloadJson) {
        ScheduleTask payload = jsonCodec.fromJson(payloadJson, ScheduleTask.class);
        taskService.reschedule(payload.taskId(), payload.dueAt());
        return 1;
    }

    private int setPriority(String payloadJson) {
        SetPriority payload = jsonCodec.fromJson(payloadJson, SetPriority.class);
        taskService.changePriority(payload.taskId(), payload.priority());
        return 1;
    }
}
