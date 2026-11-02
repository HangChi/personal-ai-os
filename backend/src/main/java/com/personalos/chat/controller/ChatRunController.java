package com.personalos.chat.controller;

import com.personalos.chat.dto.ChatRunDto;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.chat.service.ChatRunService;
import com.personalos.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chat/sessions/{conversationId}/runs")
public class ChatRunController {
    private final ChatRunService service;
    private final com.personalos.chat.service.RunEventDelivery delivery;
    public ChatRunController(ChatRunService service, com.personalos.chat.service.RunEventDelivery delivery) {
        this.service = service;
        this.delivery = delivery;
    }
    @GetMapping(value = "/{id}/events", produces = "text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(
            @PathVariable String conversationId, @PathVariable String id,
            @RequestParam(defaultValue = "0") long after) {
        service.get(conversationId, id, after); // 在响应切换成 SSE 前校验归属，错误仍返回统一信封。
        return delivery.open(id, after, cursor -> service.get(conversationId, id, cursor));
    }
    @PostMapping
    public ApiResponse<ChatRunDto> start(@PathVariable String conversationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody StreamChatRequest request) {
        return ApiResponse.ok(service.start(conversationId, key, request));
    }
    @GetMapping("/latest")
    public ApiResponse<ChatRunDto> latest(@PathVariable String conversationId) {
        return ApiResponse.ok(service.latest(conversationId));
    }
    @GetMapping("/{id}")
    public ApiResponse<ChatRunDto> get(@PathVariable String conversationId, @PathVariable String id,
            @RequestParam(defaultValue = "0") long after) {
        return ApiResponse.ok(service.get(conversationId, id, after));
    }
    @PostMapping("/{id}/resume")
    public ApiResponse<ChatRunDto> resume(@PathVariable String conversationId, @PathVariable String id) {
        return ApiResponse.ok(service.resume(conversationId, id));
    }
    @PostMapping("/{id}/stop")
    public ApiResponse<Void> stop(@PathVariable String conversationId, @PathVariable String id) {
        service.stop(conversationId, id);
        return ApiResponse.ok(null);
    }
    @PostMapping("/{id}/confirmations/finish")
    public ApiResponse<ChatRunDto> finishConfirmations(@PathVariable String conversationId, @PathVariable String id) {
        return ApiResponse.ok(service.finishConfirmations(conversationId, id));
    }
    public record Answer(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=4000) String answer) { }
    public record Followup(@jakarta.validation.constraints.Pattern(regexp="QUEUE|STEER") @jakarta.validation.constraints.NotNull String mode,
                           @Valid @jakarta.validation.constraints.NotNull StreamChatRequest request) { }
    @PostMapping("/{id}/questions/{questionId}/answer")
    public ApiResponse<ChatRunDto> answer(@PathVariable String conversationId, @PathVariable String id,
            @PathVariable String questionId, @Valid @RequestBody Answer request) {
        return ApiResponse.ok(service.answer(conversationId, id, questionId, request.answer()));
    }
    @PostMapping("/{id}/followups")
    public ApiResponse<ChatRunDto> followup(@PathVariable String conversationId, @PathVariable String id,
            @RequestHeader(value="Idempotency-Key", required=false) String key, @Valid @RequestBody Followup request) {
        return ApiResponse.ok(service.followup(conversationId, id, key, request.mode(), request.request()));
    }
    @DeleteMapping("/{id}/followups/{inputId}")
    public ApiResponse<ChatRunDto> cancelFollowup(@PathVariable String conversationId, @PathVariable String id,
            @PathVariable String inputId) {
        return ApiResponse.ok(service.cancelFollowup(conversationId, id, inputId));
    }

    public record EditFollowup(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=4000) String content) { }
    @PatchMapping("/{id}/followups/{inputId}")
    public ApiResponse<ChatRunDto> editFollowup(@PathVariable String conversationId, @PathVariable String id,
            @PathVariable String inputId, @Valid @RequestBody EditFollowup request) {
        return ApiResponse.ok(service.editFollowup(conversationId, id, inputId, request.content()));
    }
    @PostMapping("/{id}/followups/{inputId}/steer")
    public ApiResponse<ChatRunDto> steerFollowup(@PathVariable String conversationId, @PathVariable String id,
            @PathVariable String inputId) {
        return ApiResponse.ok(service.steerFollowup(conversationId, id, inputId));
    }

}
