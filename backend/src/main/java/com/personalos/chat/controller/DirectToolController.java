package com.personalos.chat.controller;

import com.personalos.agent.tool.DirectToolService;
import com.personalos.chat.service.ChatSessionService;
import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.web.DatabaseCoordination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/chat", produces = MediaType.APPLICATION_JSON_VALUE)
public class DirectToolController {
    private final DirectToolService tools;
    private final ChatSessionService sessions;
    private final DatabaseCoordination coordination;

    public record Request(@NotBlank @Size(max = 120) String tool, @NotNull Map<String, Object> arguments) {}

    public DirectToolController(DirectToolService tools, ChatSessionService sessions, DatabaseCoordination coordination) {
        this.tools = tools;
        this.sessions = sessions;
        this.coordination = coordination;
    }

    @GetMapping("/tools")
    public ApiResponse<List<DirectToolService.Description>> catalog() { return ApiResponse.ok(tools.catalog()); }

    @PostMapping("/sessions/{conversationId}/tools")
    public ApiResponse<DirectToolService.Execution> execute(@PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Request request) {
        sessions.assertWritable(conversationId);
        try (var lease = coordination.acquire("chat-session:" + conversationId, 1)) {
            if (lease == null) throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "该会话正在执行，请先停止任务再运行快捷操作");
            return ApiResponse.ok(tools.execute(conversationId, key, request.tool(), request.arguments(), () -> {
                if (!lease.valid()) throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "执行租约已失效，请核对活动记录");
            }));
        }
    }
}
