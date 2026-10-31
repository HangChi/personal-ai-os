package com.personalos.agent.audit;

import com.personalos.common.api.PageDto;
import com.personalos.common.idempotency.IdempotencyOutcome;
import com.personalos.common.idempotency.OperationRecordEntity;
import com.personalos.common.idempotency.OperationRecordRepository;
import com.personalos.common.idempotency.OperationSource;
import java.util.List;
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

    public ToolInvocationService(OperationRecordRepository repository) {
        this.repository = repository;
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

        List<ToolInvocationDto> items = page.getContent().stream()
                .map(ToolInvocationService::toDto)
                .toList();
        return new PageDto<>(items, query.page(), query.size(), page.getTotalElements());
    }

    private static ToolInvocationDto toDto(OperationRecordEntity record) {
        return new ToolInvocationDto(record.getId(), record.getOperation(), record.getSource().name(),
                record.getOutcome().name(), record.getDurationMs(), record.getConversationId(),
                record.getCreatedAt(), record.getTurnId(), record.getRequestDigest());
    }
}
