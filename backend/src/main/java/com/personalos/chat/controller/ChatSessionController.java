package com.personalos.chat.controller;

import com.personalos.chat.dto.ChatMessageDto;
import com.personalos.chat.dto.ChatSessionDto;
import com.personalos.chat.dto.CreateSessionRequest;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.chat.service.ChatCompletionService;
import com.personalos.chat.service.ChatMessageService;
import com.personalos.chat.service.ChatSessionService;
import com.personalos.common.api.ApiResponse;
import com.personalos.common.json.JsonCodec;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天接口。
 *
 * <p>契约见 {@code specs/002-ai-chat/contracts/chat-api.md}。
 * 本层只做「解析请求 → 调用服务 → 包装响应」，不含业务逻辑（宪法原则 III）。
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatSessionController {

    private final ChatSessionService sessionService;
    private final ChatMessageService messageService;
    private final ChatCompletionService completionService;

    public ChatSessionController(ChatSessionService sessionService,
                                 ChatMessageService messageService,
                                 ChatCompletionService completionService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.completionService = completionService;
    }

    /**
     * 创建会话。请求体可省略。
     */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChatSessionDto> createSession(
            @Valid @RequestBody(required = false) CreateSessionRequest request) {
        return ApiResponse.ok(sessionService.create(request == null ? null : request.title()));
    }

    /**
     * 会话列表，按最近活动时间倒序。无会话时返回空数组。
     */
    @GetMapping("/sessions")
    public ApiResponse<List<ChatSessionDto>> listSessions() {
        return ApiResponse.ok(sessionService.list());
    }

    /**
     * 查询会话的全部消息，按顺序正序。
     */
    @GetMapping("/sessions/{conversationId}/messages")
    public ApiResponse<List<ChatMessageDto>> listMessages(@PathVariable String conversationId) {
        return ApiResponse.ok(messageService.list(conversationId));
    }

    /**
     * 流式对话。
     *
     * <p>刻意不声明 {@code produces = text/event-stream}：Spring 会依据
     * {@code SseEmitter} 返回类型自动设置该 Content-Type。若在这里写死，
     * 前置校验失败时返回的 JSON 错误也会被标成事件流类型，让客户端难以处理。
     *
     * <p>可选的 {@code Idempotency-Key} 头会被用作**轮次标识**：客户端重试整个对话请求时
     * 复用同一个键，模型在本轮内重复发出的工具调用就不会二次执行（双层幂等的衔接点）。
     */
    @PostMapping("/sessions/{conversationId}/stream")
    public SseEmitter stream(
            @PathVariable String conversationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StreamChatRequest request) {
        return completionService.stream(conversationId, request.content(), toTurnId(idempotencyKey));
    }

    /**
     * 删除会话及其全部消息。
     */
    @DeleteMapping("/sessions/{conversationId}")
    public ApiResponse<Void> deleteSession(@PathVariable String conversationId) {
        sessionService.delete(conversationId);
        return ApiResponse.ok(null);
    }

    /**
     * 轮次标识：列宽为 64，过长时取摘要而非截断 —— 截断会让不同的键碰撞成同一个轮次。
     */
    private String toTurnId(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = idempotencyKey.strip();
        return trimmed.length() <= 64 ? trimmed : JsonCodec.sha256Hex(trimmed);
    }
}
