package com.personalos.agent.tool;

import com.personalos.common.ai.tool.ToolArguments;
import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.common.confirmation.ConfirmationDto;
import com.personalos.task.confirmation.TaskConfirmationService;
import com.personalos.task.dto.CreateTaskRequest;
import com.personalos.task.dto.TaskDto;
import com.personalos.task.dto.TaskQuery;
import com.personalos.task.dto.UpdateTaskRequest;
import com.personalos.task.entity.TaskPriority;
import com.personalos.task.entity.TaskStatus;
import com.personalos.task.service.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 供 Agent 调用的任务工具。
 *
 * <h2>这是模型唯一能触碰数据的入口</h2>
 * 工具内部一律调用 {@link TaskService}，不直接访问数据库（宪法原则 II / FR-005）。
 *
 * <h2>哪三个工具「不执行」</h2>
 * {@link #deleteTask}、{@link #scheduleTask}、{@link #setTaskPriority} 只**申请**执行：
 * 它们创建一条待人类确认的记录，返回 {@code REQUIRES_CONFIRMATION}。
 * 真正执行它们的是面向人类的确认接口 —— 工具集合里没有任何东西能完成确认。
 */
@Component
public class TaskTools {

    private static final int FIND_DEFAULT_LIMIT = 20;

    private final ToolExecutor toolExecutor;
    private final TaskService taskService;
    private final TaskConfirmationService confirmationService;

    public TaskTools(ToolExecutor toolExecutor, TaskService taskService, TaskConfirmationService confirmationService) {
        this.toolExecutor = toolExecutor;
        this.taskService = taskService;
        this.confirmationService = confirmationService;
    }

    // ------------------------------------------------------------------ 创建

    @Tool(name = "createTask",
            description = "创建一个新的个人任务。当用户要求新增待办、或提到「帮我记一下」「提醒我做某事」时调用。")
    public String createTask(
            @ToolParam(description = "任务标题，1~200 字，例如：交周报") String title,
            @ToolParam(required = false, description = "任务描述，可省略") String description,
            @ToolParam(required = false,
                    description = "优先级，取值 LOW / MEDIUM / HIGH，省略则默认 MEDIUM") String priority,
            @ToolParam(required = false,
                    description = "截止时间，ISO-8601，例如 2026-10-17T10:00:00Z 或 2026-10-17，可省略") String dueAt,
            @ToolParam(required = false,
                    description = "标签，最多 5 个，例如 [\"工作\",\"本周\"]，可省略") List<String> tags,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("title", title);
            args.put("description", description);
            args.put("priority", priority);
            args.put("dueAt", dueAt);
            args.put("tags", tags);
        });

        return toolExecutor.write("createTask", arguments, toolContext, () -> {
            TaskDto created = taskService.create(new CreateTaskRequest(
                    title, description,
                    ToolArguments.parseEnum(TaskPriority.class, priority, "优先级", "LOW / MEDIUM / HIGH", false),
                    ToolArguments.parseInstant(dueAt, "dueAt"),
                    tags));
            return ToolExecutionResult.ok("已创建任务「" + created.title() + "」", created);
        });
    }

    // ------------------------------------------------------------------ 查询

    @Tool(name = "findTasks",
            description = "按关键词或状态查询任务列表。用户问「我有哪些待办」「找一下周报相关的任务」时调用。"
                    + "返回结果包含任务 id，后续需要按 id 操作时必须先调用本工具获取 id。")
    public String findTasks(
            @ToolParam(required = false, description = "关键词，匹配标题或描述，可省略") String keyword,
            @ToolParam(required = false, description = "状态筛选：TODO / DOING / DONE，可省略") String status,
            @ToolParam(required = false, description = "标签精确筛选，例如「工作」，可省略") String tag,
            @ToolParam(required = false, description = "返回条数上限，默认 20，最大 50") Integer limit,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("keyword", keyword);
            args.put("status", status);
            args.put("tag", tag);
            args.put("limit", limit);
        });

        return toolExecutor.read("findTasks", arguments, toolContext, () -> {
            TaskQuery query = new TaskQuery(
                    ToolArguments.parseEnum(TaskStatus.class, status, "状态", "TODO / DOING / DONE", false),
                    keyword, tag, 0,
                    limit == null ? FIND_DEFAULT_LIMIT : Math.min(limit, TaskQuery.MAX_SIZE));
            List<TaskDto> tasks = taskService.search(query).items();
            String message = tasks.isEmpty() ? "没有符合条件的任务" : "找到 " + tasks.size() + " 个任务";
            return ToolExecutionResult.ok(message, tasks);
        });
    }

    @Tool(name = "getTask",
            description = "按 id 查看单个任务的详细信息。")
    public String getTask(
            @ToolParam(description = "任务 id") String taskId,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> args.put("taskId", taskId));

        return toolExecutor.read("getTask", arguments, toolContext, () -> {
            TaskDto task = taskService.get(ToolExecutor.parseUuid(taskId, "taskId"));
            return ToolExecutionResult.ok("任务「" + task.title() + "」详情", task);
        });
    }

    // ------------------------------------------------------------------ 直接修改（非关键字段 / 可逆）

    @Tool(name = "updateTaskDetails",
            description = "修改任务的标题或描述。这两个字段可逆、影响局部，因此无需用户确认。"
                    + "修改截止时间请用 scheduleTask，修改优先级请用 setTaskPriority。")
    public String updateTaskDetails(
            @ToolParam(description = "任务 id") String taskId,
            @ToolParam(required = false, description = "新的标题，可省略") String title,
            @ToolParam(required = false, description = "新的描述，可省略") String description,
            @ToolParam(required = false,
                    description = "新的标签列表（整体替换，最多 5 个），可省略；传空数组表示清空") List<String> tags,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("taskId", taskId);
            args.put("title", title);
            args.put("description", description);
            args.put("tags", tags);
        });

        return toolExecutor.write("updateTaskDetails", arguments, toolContext, () -> {
            if (title == null && description == null && tags == null) {
                return ToolExecutionResult.rejected("至少需要提供新的标题、描述或标签");
            }
            TaskDto updated = taskService.update(
                    ToolExecutor.parseUuid(taskId, "taskId"),
                    new UpdateTaskRequest(title, description, null, null, null, null, tags));
            return ToolExecutionResult.ok("已更新任务「" + updated.title() + "」", updated);
        });
    }

    @Tool(name = "setTaskCompleted",
            description = "把任务标记为已完成，或重新打开已完成的任务。该操作可逆且不破坏数据，因此无需用户确认。")
    public String setTaskCompleted(
            @ToolParam(description = "任务 id") String taskId,
            @ToolParam(description = "true 表示标记完成，false 表示重新打开") Boolean completed,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("taskId", taskId);
            args.put("completed", completed);
        });

        return toolExecutor.write("setTaskCompleted", arguments, toolContext, () -> {
            if (completed == null) {
                return ToolExecutionResult.rejected("必须明确指示是标记完成还是重新打开");
            }
            TaskDto updated = taskService.setCompleted(
                    ToolExecutor.parseUuid(taskId, "taskId"), completed);
            String action = completed ? "已完成" : "已重新打开";
            return ToolExecutionResult.ok("任务「" + updated.title() + "」" + action, updated);
        });
    }

    // ------------------------------------------------------------------ 需人工确认

    @Tool(name = "deleteTask",
            description = "申请删除一个或多个任务。这是不可逆操作：调用后不会立即删除，"
                    + "而是生成一条待确认事项，必须由用户在界面上确认后才会真正删除。"
                    + "调用后请告知用户「需要在界面上确认」，不要声称已经删除。")
    public String deleteTask(
            @ToolParam(description = "要删除的任务 id 列表，1~50 个。可先调用 findTasks 获取 id。")
            List<String> taskIds,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> args.put("taskIds", taskIds));

        return toolExecutor.write("deleteTask", arguments, toolContext, () -> {
            if (taskIds == null || taskIds.isEmpty()) {
                return ToolExecutionResult.rejected("至少需要一个任务标识");
            }
            if (taskIds.size() > TaskService.MAX_BULK_SIZE) {
                return ToolExecutionResult.rejected(
                        "一次最多处理 " + TaskService.MAX_BULK_SIZE + " 个任务，请拆分后重试");
            }
            List<UUID> ids = taskIds.stream().map(id -> ToolExecutor.parseUuid(id, "taskIds")).toList();
            List<TaskDto> found = taskService.findByIds(ids);
            if (found.isEmpty()) {
                return ToolExecutionResult.rejected("指定的任务都不存在，未生成确认事项");
            }
            ConfirmationDto confirmation = confirmationService.requestDeleteTasks(
                    found.stream().map(TaskDto::id).toList(),
                    found.stream().map(TaskDto::title).toList(),
                    ToolExecutor.conversationId(toolContext));
            return ToolExecutionResult.requiresConfirmation(
                    "删除操作需要你确认。已生成待确认事项：" + confirmation.summary()
                            + "。请用户在界面上确认后再执行。",
                    confirmation.id().toString());
        });
    }

    @Tool(name = "scheduleTask",
            description = "申请修改任务的截止时间。截止时间属于关键字段，调用后不会立即生效，"
                    + "而是生成待确认事项，需用户在界面上确认。省略 dueAt 表示清除截止时间。")
    public String scheduleTask(
            @ToolParam(description = "任务 id") String taskId,
            @ToolParam(required = false,
                    description = "新的截止时间，ISO-8601；省略或留空表示清除截止时间") String dueAt,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("taskId", taskId);
            args.put("dueAt", dueAt);
        });

        return toolExecutor.write("scheduleTask", arguments, toolContext, () -> {
            TaskDto task = taskService.get(ToolExecutor.parseUuid(taskId, "taskId"));
            Instant newDueAt = ToolArguments.parseInstant(dueAt, "dueAt");
            ConfirmationDto confirmation = confirmationService.requestScheduleTask(
                    task.id(), task.title(), newDueAt, ToolExecutor.conversationId(toolContext));
            return ToolExecutionResult.requiresConfirmation(
                    "修改截止时间需要你确认。已生成待确认事项：" + confirmation.summary()
                            + "。请用户在界面上确认后再执行。",
                    confirmation.id().toString());
        });
    }

    @Tool(name = "setTaskPriority",
            description = "申请修改任务的优先级。优先级属于关键字段，调用后不会立即生效，"
                    + "而是生成待确认事项，需用户在界面上确认。")
    public String setTaskPriority(
            @ToolParam(description = "任务 id") String taskId,
            @ToolParam(description = "新的优先级，取值 LOW / MEDIUM / HIGH") String priority,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("taskId", taskId);
            args.put("priority", priority);
        });

        return toolExecutor.write("setTaskPriority", arguments, toolContext, () -> {
            TaskPriority newPriority = ToolArguments.parseEnum(
                    TaskPriority.class, priority, "优先级", "LOW / MEDIUM / HIGH", true);
            TaskDto task = taskService.get(ToolExecutor.parseUuid(taskId, "taskId"));
            ConfirmationDto confirmation = confirmationService.requestSetPriority(
                    task.id(), task.title(), newPriority, ToolExecutor.conversationId(toolContext));
            return ToolExecutionResult.requiresConfirmation(
                    "修改优先级需要你确认。已生成待确认事项：" + confirmation.summary()
                            + "。请用户在界面上确认后再执行。",
                    confirmation.id().toString());
        });
    }

}
