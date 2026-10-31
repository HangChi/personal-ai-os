package com.personalos.chat.controller;

import com.personalos.chat.dto.ChatMessageDto;
import com.personalos.chat.dto.ChatContextUsageDto;
import com.personalos.chat.dto.ChatSessionDto;
import com.personalos.chat.dto.ChatSessionQuery;
import com.personalos.chat.dto.CreateSessionRequest;
import com.personalos.chat.dto.RenameSessionRequest;
import com.personalos.chat.dto.SetSessionModelRequest;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.chat.service.ChatCompletionService;
import com.personalos.chat.service.ChatMarkdownExporter;
import com.personalos.chat.service.ChatMessageService;
import com.personalos.chat.service.ChatSessionService;
import com.personalos.common.ai.AiProperties;
import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.PageDto;
import com.personalos.common.json.JsonCodec;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final AiProperties aiProperties;
    private final com.personalos.common.ai.ContextBudgetService contextBudget;
    private final ChatMarkdownExporter markdownExporter;
    private final com.personalos.chat.service.UsageStatsService usageStats;

    public ChatSessionController(ChatSessionService sessionService,
                                 ChatMessageService messageService,
                                 ChatCompletionService completionService,
                                 AiProperties aiProperties,
                                 com.personalos.common.ai.ContextBudgetService contextBudget,
                                 ChatMarkdownExporter markdownExporter,
                                 com.personalos.chat.service.UsageStatsService usageStats) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.completionService = completionService;
        this.aiProperties = aiProperties;
        this.contextBudget = contextBudget;
        this.markdownExporter = markdownExporter;
        this.usageStats = usageStats;
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
     * 分页 + 关键词筛选的会话列表（阶段 16）。
     *
     * <p>声明在 {@code /{conversationId}} 之前：路径字面量本就优先于变量段，
     * 放前面是为了读代码时不会先疑惑「`search` 会不会被当成 conversationId」。
     *
     * <p>与全量的 {@code GET /sessions} 并存：后者被导出链路与既有测试依赖，
     * 改造成分页会是一次波及 6 个测试的契约变更（见 specs/016 的 plan R-01）。
     */
    @GetMapping("/sessions/search")
    public ApiResponse<PageDto<ChatSessionDto>> searchSessions(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(sessionService.search(new ChatSessionQuery(keyword, page, size)));
    }

    /**
     * 查询会话消息，按顺序正序。
     *
     * <p>不带 {@code limit} 时返回**全部**消息（既有契约不变，导出与老客户端依赖它）。
     * 带 {@code limit} 时返回**最近 limit 条**；再用返回结果中最小顺序号作为
     * {@code beforeSeq} 可以继续向前翻页（阶段 21，长会话增量加载）。
     */
    @GetMapping("/sessions/{conversationId}/messages")
    public ApiResponse<List<ChatMessageDto>> listMessages(
            @PathVariable String conversationId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer beforeSeq) {
        return ApiResponse.ok(messageService.list(conversationId, limit, beforeSeq));
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
     *
     * <p>阶段 19：请求体的 {@code useKnowledge} / {@code topK} 控制知识库增强——
     * 开启时先检索再回答，事件序列多一个 {@code citations}。
     */
    @PostMapping("/sessions/{conversationId}/stream")
    public SseEmitter stream(
            @PathVariable String conversationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StreamChatRequest request) {
        return completionService.stream(conversationId, request.content(), toTurnId(idempotencyKey),
                request.knowledgeEnabled(), request.resolvedTopK(), request.resolvedThinkingLevel(),
                request.resolvedAttachments());
    }

    /**
     * 查询会话的上下文用量（阶段 22 仪表盘）。
     *
     * <p>单独端点而不是塞进消息列表：消息列表是高频读路径，
     * 而仪表盘数据只在选中会话时需要一次，且计算要扫消息表。
     *
     * <p>V25 起窗口与预算都按**该会话所用模型**给：仪表盘的分母必须是
     * 「这个模型能吃多长」，而不是「配置里某个全局默认值」。否则用 32K 窗口的
     * 模型时界面显示「/ 128K」，用户以为还有余量、实际请求已被端点拒绝。
     */
    @GetMapping("/sessions/{conversationId}/context")
    public ApiResponse<ChatContextUsageDto> contextUsage(@PathVariable String conversationId) {
        return ApiResponse.ok(contextFor(conversationId));
    }

    /** 空白对话也能显示实际默认模型的窗口。 */
    @GetMapping("/context")
    public ApiResponse<ChatContextUsageDto> defaultContextUsage() {
        return ApiResponse.ok(contextFor(null));
    }

    private ChatContextUsageDto contextFor(String conversationId) {
        String model = conversationId == null ? null : sessionService.requireSession(conversationId).getChatModel();
        var window = contextBudget.modelWindow(model);
        java.util.OptionalInt actual = conversationId == null ? java.util.OptionalInt.empty()
                : usageStats.latestActualPromptTokens(conversationId);
        int promptTokens = actual.isPresent() ? actual.getAsInt()
                : conversationId == null ? 0 : completionService.estimatePromptTokens(conversationId);
        return new ChatContextUsageDto(promptTokens, contextBudget.budgetTokensForWindow(window.tokens()),
                window.tokens(), actual.isEmpty(), window.modelName(), window.source());
    }

    public record ContextBudgetRequest(Integer tokens, String conversationId) {
    }

    /** 返回同一会话的生效预算与窗口，避免全局窗口覆盖当前模型。 */
    @PutMapping("/context-budget")
    public ApiResponse<ChatContextUsageDto> setContextBudget(@RequestBody ContextBudgetRequest body) {
        Integer tokens = body.tokens();
        if (tokens == null || tokens <= 0) {
            throw new com.personalos.common.exception.BusinessException(
                    com.personalos.common.api.ErrorCode.VALIDATION_FAILED, "预算必须是正整数");
        }
        String id = body.conversationId();
        if (id != null && id.isBlank()) {
            id = null;
        }
        if (id != null) {
            sessionService.requireSession(id);
        }
        contextBudget.setBudgetTokens(tokens);
        return ApiResponse.ok(contextFor(id));
    }

    /**
     * 重命名会话。
     *
     * <p>用 {@code PATCH} 而非 {@code PUT}：这是**部分更新**，只改标题一个字段。
     * {@code PUT} 的契约是「用请求体整体替换资源」，将来给会话加字段时，
     * 它会隐含「没传的字段要清空」这种危险的解读。
     *
     * <p>请求体省略或 {@code title} 为 null 时按「未提供标题」处理并被拒绝，
     * 而不是「保持不变」—— 静默成功会让前端以为改名生效了。
     *
     * <p>契约见 {@code specs/007-chat-session-rename/contracts/session-rename-api.md}。
     */
    @PatchMapping("/sessions/{conversationId}")
    public ApiResponse<ChatSessionDto> renameSession(
            @PathVariable String conversationId,
            @RequestBody(required = false) RenameSessionRequest request) {
        return ApiResponse.ok(
                sessionService.rename(conversationId, request == null ? null : request.title()));
    }

    /**
     * 设置该会话使用的模型（V23）。
     *
     * <p>用 {@code PUT /model} 而不是 {@code PATCH} 整个会话：这是**一个字段的
     * 幂等赋值**，语义正好是「把模型设成这个值」，且 {@code null} 有明确含义
     * （恢复为沿用生效配置的默认模型），不会与「没传该字段」混淆。
     *
     * <p>不校验模型名是否在配置的清单里 —— 清单是收藏夹不是白名单，
     * 理由见 {@code ChatSessionService#setModel}。
     */
    @PutMapping("/sessions/{conversationId}/model")
    public ApiResponse<ChatSessionDto> setModel(
            @PathVariable String conversationId,
            @RequestBody(required = false) SetSessionModelRequest request) {
        return ApiResponse.ok(
                sessionService.setModel(conversationId, request == null ? null : request.model()));
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

    /**
     * 回退到某条消息之前（重新生成 / 编辑重发的前置动作）。
     *
     * <p>只做删除，不发流：调用方随后用原内容或编辑后的内容调 {@code /stream}，
     * 复用的仍是同一条经过验证的流式链路。
     *
     * @param fromSequenceNo 从该顺序号起（含）删除
     * @return 删除条数
     */
    @PostMapping("/sessions/{conversationId}/rewind")
    public ApiResponse<java.util.Map<String, Integer>> rewind(
            @PathVariable String conversationId,
            @RequestParam("fromSequenceNo") int fromSequenceNo) {
        int deleted = messageService.rewind(conversationId, fromSequenceNo);
        return ApiResponse.ok(java.util.Map.of("deleted", deleted));
    }

    /**
     * 导出单个会话为 Markdown。
     *
     * <p>与全量导出（{@code /export}）受众不同：那个是给机器读的迁移格式，
     * 这个是给人读的 —— 粘进笔记、发出去、存档。所以不套统一信封，
     * 响应体就是文件本身。
     *
     * <p>文件名用 RFC 5987 的 {@code filename*} 形式：标题里几乎必有中文，
     * 直接塞进 {@code filename=} 会在部分浏览器上变成乱码。
     */
    @GetMapping(value = "/sessions/{conversationId}/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> exportMarkdown(@PathVariable String conversationId) {
        ChatMarkdownExporter.ExportDocument document = markdownExporter.export(conversationId);
        String encodedName = URLEncoder.encode(document.filename(), StandardCharsets.UTF_8)
                // URLEncoder 把空格编成 +，而 filename* 语法里 + 就是字面量
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(document.markdown());
    }
}
