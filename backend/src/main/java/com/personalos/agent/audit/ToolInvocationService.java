package com.personalos.agent.audit;

import com.personalos.common.api.PageDto;
import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.confirmation.ConfirmationEntity;
import com.personalos.common.confirmation.ConfirmationRepository;
import com.personalos.common.confirmation.ConfirmationStatus;
import com.personalos.common.json.JsonCodec;
import com.personalos.common.idempotency.IdempotencyOutcome;
import com.personalos.common.idempotency.OperationRecordEntity;
import com.personalos.common.idempotency.OperationRecordRepository;
import com.personalos.common.idempotency.OperationSource;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * 工具执行日志查询。
 *
 * <p><b>阶段 11 修掉的一个缺陷</b>：原实现先用 {@code PageRequest} 取一页、再在内存里
 * 过滤 {@code source == TOOL}。两个后果——一页可能凑不满（HTTP 记录被取走又丢掉），
 * 且 {@code total} 无从得知（前端无法翻到「还有多少」）。
 * 现在过滤条件下推到 SQL，{@code total} 由数据库给。
 *
 * <p><b>排序必须唯一</b>：只按 {@code createdAt} 排序时，同一毫秒写入的多条记录顺序不稳定，
 * 翻页会出现「第 2 页重复第 1 页的条目」。所以追加 {@code id DESC} 作为决胜字段。
 */
@Service
public class ToolInvocationService {

    private final OperationRecordRepository repository;
    private final ConfirmationRepository confirmations;
    private final JsonCodec json;

    public ToolInvocationService(OperationRecordRepository repository, ConfirmationRepository confirmations, JsonCodec json) {
        this.repository = repository;
        this.confirmations = confirmations;
        this.json = json;
    }

    /**
     * 查询工具调用记录（已分页）。
     *
     * @param query 查询条件；{@code outcome} 与 {@code conversationId} 为空表示不限
     * @return 当前页数据与过滤后的总数
     */
    public PageDto<ToolInvocationDto> search(ToolInvocationQuery query) {
        // 恒定条件：审计只关心「助手经由工具做的事」，HTTP 幂等记录不属于这个范畴
        Specification<OperationRecordEntity> spec =
                (root, criteriaQuery, cb) -> cb.equal(root.get("source"), OperationSource.TOOL);

        IdempotencyOutcome outcome = query.outcome();
        if (outcome != null) {
            spec = spec.and((root, criteriaQuery, cb) -> cb.equal(root.get("outcome"), outcome));
        }

        String conversationId = query.conversationId();
        if (conversationId != null) {
            spec = spec.and((root, criteriaQuery, cb) -> cb.equal(root.get("conversationId"), conversationId));
        }

        // 同 ChatSessionService：页码溢出会让 offset 变负并抛异常（→ 500）。
        // 审计页的 total 也要真实，否则用户会以为「审计记录被清了」。
        if (PageDto.offsetOverflows(query.page(), query.size())) {
            return PageDto.ofPage(List.of(), query.page(), query.size(), repository.count(spec));
        }

        PageRequest pageRequest = PageRequest.of(query.page(), query.size(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<OperationRecordEntity> page = repository.findAll(spec, pageRequest);

        // 原始 outcome 与响应参与幂等回放，保持不变；批量关联确认的当前状态。
        // 直接读取关联记录也能修复已有历史，不依赖用户再次确认或重新生成。
        Map<Long, UUID> references = new HashMap<>();
        for (var record : page.getContent()) {
            UUID id = confirmationId(record);
            if (id != null) references.put(record.getId(), id);
        }
        Map<UUID, ConfirmationEntity> current = new HashMap<>();
        if (!references.isEmpty()) {
            confirmations.findAllById(references.values()).forEach(item -> current.put(item.getId(), item));
        }
        Instant now = Instant.now();

        List<ToolInvocationDto> items = page.getContent().stream()
                .map(record -> toDto(record, current.get(references.get(record.getId())), now))
                .toList();
        return new PageDto<>(items, query.page(), query.size(), page.getTotalElements());
    }

    private UUID confirmationId(OperationRecordEntity record) {
        if (record.getOutcome() != IdempotencyOutcome.REQUIRES_CONFIRMATION || record.getResponsePayload() == null) return null;
        try {
            var result = json.fromJson(record.getResponsePayload(), ToolExecutionResult.class);
            return result == null || result.confirmationId() == null ? null : UUID.fromString(result.confirmationId());
        } catch (IllegalStateException | IllegalArgumentException ex) {
            // 旧数据或异常响应不影响整页审计查询，无法关联时保留原始状态。
            return null;
        }
    }

    private static ToolInvocationDto toDto(OperationRecordEntity record, ConfirmationEntity confirmation, Instant now) {
        String confirmationStatus = null;
        if (confirmation != null && java.util.Objects.equals(record.getConversationId(), confirmation.getConversationId())) {
            var status = confirmation.getStatus();
            confirmationStatus = status == ConfirmationStatus.PENDING && !confirmation.getExpiresAt().isAfter(now)
                    ? ConfirmationStatus.EXPIRED.name() : status.name();
        }
        return new ToolInvocationDto(record.getId(), record.getOperation(), record.getSource().name(),
                record.getOutcome().name(), record.getDurationMs(), record.getConversationId(),
                record.getCreatedAt(), record.getTurnId(), record.getRequestDigest(), confirmationStatus);
    }
}
