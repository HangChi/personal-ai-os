package com.personalos.task.controller;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.idempotency.IdempotencyOutcome;
import com.personalos.common.idempotency.IdempotencyRequest;
import com.personalos.common.idempotency.IdempotencyService;
import com.personalos.common.idempotency.IdempotentResult;
import com.personalos.common.idempotency.OperationSource;
import com.personalos.common.api.PageDto;
import com.personalos.common.json.JsonCodec;
import com.personalos.task.dto.CreateTaskRequest;
import com.personalos.task.dto.DeletionAck;
import com.personalos.task.dto.TaskDto;
import com.personalos.task.dto.TaskQuery;
import com.personalos.task.dto.UpdateTaskRequest;
import com.personalos.task.entity.TaskStatus;
import com.personalos.task.service.TaskService;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务接口。
 *
 * <p>契约见 {@code specs/003-task-agent/contracts/task-api.md}。
 *
 * <p>本层的删除**不需要确认** —— 发起者就是人本身（FR-024）。由 Agent 发起的删除走
 * 确认流程，且确认途径不在此控制器，而在面向人类的确认接口中。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    /** 客户端提供幂等键的请求头名（FR-015） */
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    /** 键的格式约束：足够长以避免碰撞，且限制字符集以便日志与排错 */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{8,120}");

    private final TaskService taskService;
    private final IdempotencyService idempotencyService;
    private final JsonCodec jsonCodec;

    public TaskController(TaskService taskService,
                          IdempotencyService idempotencyService,
                          JsonCodec jsonCodec) {
        this.taskService = taskService;
        this.idempotencyService = idempotencyService;
        this.jsonCodec = jsonCodec;
    }

    /**
     * 创建任务。可选 {@code Idempotency-Key} 请求头。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskDto> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateTaskRequest request) {
        return idempotent(idempotencyKey, "POST /api/v1/tasks", request, TaskDto.class,
                () -> taskService.create(request));
    }

    /**
     * 查询任务列表（分页）。
     *
     * <p>2026-10-14 起 {@code limit} 参数改为 {@code page + size}，响应改为
     * {@link PageDto}（含 total）——原先超过 limit 的记录会静默消失。
     */
    @GetMapping
    public ApiResponse<PageDto<TaskDto>> list(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(taskService.search(new TaskQuery(status, keyword, page, size)));
    }

    /**
     * 查询单个任务。
     */
    @GetMapping("/{id}")
    public ApiResponse<TaskDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(taskService.get(id));
    }

    /**
     * 修改任务。可选 {@code Idempotency-Key} 请求头。
     */
    @PatchMapping("/{id}")
    public ApiResponse<TaskDto> update(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody UpdateTaskRequest request) {
        return idempotent(idempotencyKey, "PATCH /api/v1/tasks/" + id, request, TaskDto.class,
                () -> taskService.update(id, request));
    }

    /**
     * 标记完成。
     */
    @PostMapping("/{id}/complete")
    public ApiResponse<TaskDto> complete(@PathVariable UUID id) {
        return ApiResponse.ok(taskService.setCompleted(id, true));
    }

    /**
     * 取消完成。
     */
    @PostMapping("/{id}/reopen")
    public ApiResponse<TaskDto> reopen(@PathVariable UUID id) {
        return ApiResponse.ok(taskService.setCompleted(id, false));
    }

    /**
     * 删除任务。可选 {@code Idempotency-Key} 请求头。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<DeletionAck> delete(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        return idempotent(idempotencyKey, "DELETE /api/v1/tasks/" + id, id, DeletionAck.class, () -> {
            taskService.delete(id);
            return new DeletionAck(true);
        });
    }

    /**
     * 幂等包装：提供键时启用，未提供时按普通请求处理（契约已写明该差异）。
     */
    private <T> ApiResponse<T> idempotent(String rawKey, String operation, Object requestBody,
                                          Class<T> responseType, Supplier<T> action) {
        if (rawKey == null || rawKey.isBlank()) {
            return ApiResponse.ok(action.get());
        }

        String key = requireValidKey(rawKey);
        IdempotencyRequest idempotencyRequest = new IdempotencyRequest(
                key, OperationSource.HTTP, operation, jsonCodec.digestOf(requestBody), null, null);

        IdempotentResult result = idempotencyService.execute(idempotencyRequest, () -> {
            T value = action.get();
            return IdempotentResult.of(jsonCodec.toJson(value), IdempotencyOutcome.SUCCESS);
        });

        if (result.responsePayload() == null) {
            // 命中了并发窗口内的「执行中」占位记录：既没执行，也没有可回放的响应
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "同一请求正在处理中，请稍后重试");
        }
        return ApiResponse.ok(jsonCodec.fromJson(result.responsePayload(), responseType));
    }

    private String requireValidKey(String rawKey) {
        String key = rawKey.strip();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "幂等键格式非法：需为 8~120 位的字母、数字或 . _ : -");
        }
        return key;
    }
}
