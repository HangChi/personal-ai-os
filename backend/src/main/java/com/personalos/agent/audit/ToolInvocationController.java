package com.personalos.agent.audit;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.idempotency.OperationSource;
import com.personalos.common.idempotency.OperationRecordEntity;
import com.personalos.common.idempotency.OperationRecordRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具执行日志查询（FR-017/018）。
 *
 * <p>对应「助手到底替我做了什么」这个自托管用户最关心的问题。
 * 日志同时包含被拒绝、被去重与失败的调用 —— 只记录成功会掩盖真实发生过的尝试。
 */
@RestController
@RequestMapping("/api/v1/agent/tool-invocations")
public class ToolInvocationController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final OperationRecordRepository repository;

    public ToolInvocationController(OperationRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 查询工具执行日志，按时间倒序。
     */
    @GetMapping
    public ApiResponse<List<ToolInvocationDto>> list(
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        PageRequest page = PageRequest.of(0, effectiveLimit);

        List<OperationRecordEntity> records = (conversationId == null || conversationId.isBlank())
                ? repository.findAllByOrderByCreatedAtDesc(page)
                : repository.findByConversationIdOrderByCreatedAtDesc(conversationId, page);

        return ApiResponse.ok(records.stream()
                .filter(record -> record.getSource() == OperationSource.TOOL)
                .map(record -> new ToolInvocationDto(record.getOperation(), record.getSource().name(),
                        record.getOutcome().name(), record.getDurationMs(), record.getConversationId(),
                        record.getCreatedAt()))
                .toList());
    }
}
