package com.personalos.chat.service;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.chat.dto.ChatSessionDto;
import com.personalos.chat.dto.ChatSessionQuery;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.repository.ChatSessionRepository;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.api.PageDto;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.text.Texts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 会话写路径与查询。
 *
 * <p>所有写操作都在 Service 层（宪法原则 III）；Controller 不直接接触仓储。
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    /** 新建会话的默认标题 */
    public static final String DEFAULT_TITLE = "新会话";

    private static final int AUTO_TITLE_MAX_LENGTH = 30;

    /**
     * 会话标识格式：与数据库 {@code varchar(64)} 对齐。
     *
     * <p>限定字符集可避免不可见字符造成的「看起来相同却查不到」（research.md R-08）。
     */
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatAttachmentService attachmentService;

    public ChatSessionService(ChatSessionRepository sessionRepository,
                              ChatMessageRepository messageRepository,
                              ChatAttachmentService attachmentService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.attachmentService = attachmentService;
    }

    /**
     * 创建会话。
     *
     * @param title 可为空；为空时使用默认标题
     * @return 新建的会话
     */
    @Transactional
    public ChatSessionDto create(String title) {
        // 空标题回落默认值；非空则按「去除首尾空白后」的长度校验 ——
        // 与重命名共用 Texts 与同一错误文案，避免同一字段出现两种口径（research R-02 / R-08）
        String trimmed = Texts.trimToNull(title);
        String normalizedTitle = trimmed == null
                ? DEFAULT_TITLE
                : Texts.requireNotBlank(trimmed, "标题", Texts.TITLE_MAX_LENGTH);

        Instant now = Instant.now();
        ChatSessionEntity saved = sessionRepository.save(
                new ChatSessionEntity(UUID.randomUUID().toString(), normalizedTitle, now, now));
        log.info("创建会话 conversationId={}", saved.getConversationId());
        return toDto(saved, 0L);
    }

    /**
     * 重命名会话。
     *
     * <p><b>只写 {@code title}，刻意不更新 {@code updatedAt}</b>：该字段的语义是「最近一次对话活动」，
     * 改名不是活动。若在这里顺手刷新它，列表会按活动时间重排 —— 用户改一次名字，会话就跳到最前面，
     * 正在按时间翻找的上下文被打断（spec FR-004 / research R-03）。
     *
     * <p>判定顺序本身是契约（contracts C-4 / C-9）：格式 → 空 → 长度 → 存在性。
     * 标题非法时**不查库** —— 否则一个格式错误的标识会先打一次数据库，
     * 既浪费一次往返，又让「参数错误」的表现取决于库的状态。
     *
     * @param conversationId 会话标识
     * @param title          新标题；去除首尾空白后不得为空，长度不超过 200
     * @return 更新后的会话视图（含消息条数，供前端就地替换）
     */
    @Transactional
    public ChatSessionDto rename(String conversationId, String title) {
        validateConversationId(conversationId);
        String normalizedTitle = Texts.requireNotBlank(title, "标题", Texts.TITLE_MAX_LENGTH);

        ChatSessionEntity session = requireSession(conversationId);
        session.setTitle(normalizedTitle);
        ChatSessionEntity saved = sessionRepository.save(session);

        long messageCount = messageRepository.countByConversationId(conversationId);
        log.info("重命名会话 conversationId={} title={}", conversationId, normalizedTitle);
        return toDto(saved, messageCount);
    }

    /**
     * 会话列表，按最近活动时间倒序。
     *
     * <p>消息数用一次分组查询取得，避免逐个会话查询造成 N+1。
     */
    @Transactional(readOnly = true)
    public List<ChatSessionDto> list() {
        List<ChatSessionEntity> sessions = sessionRepository.findAllByOrderByUpdatedAtDesc();
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<String> conversationIds = sessions.stream()
                .map(ChatSessionEntity::getConversationId)
                .toList();
        Map<String, Long> counts = messageRepository.countGroupedByConversationId(conversationIds).stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).longValue()));
        return sessions.stream()
                .map(session -> toDto(session, counts.getOrDefault(session.getConversationId(), 0L)))
                .toList();
    }

    /**
     * 分页 + 关键词筛选的会话列表（阶段 16）。
     *
     * <p>与 {@link #list()} 的分工：{@code list()} 给导出等需要全量的内部用途，
     * 本方法给界面。删掉 {@code list()} 留给将来「明确不再有全量消费者」时再做。
     *
     * <p>消息条数只对**当前页**做一次分组查询 —— 既避免 N+1，也不会因为给全量会话
     * 统计消息数而退化。
     */
    @Transactional(readOnly = true)
    public PageDto<ChatSessionDto> search(ChatSessionQuery query) {
        Specification<ChatSessionEntity> spec = (root, criteriaQuery, builder) -> {
            if (query.keyword() == null) {
                return builder.conjunction();
            }
            String pattern = "%" + query.keyword().strip().toLowerCase() + "%";
            return builder.like(builder.lower(root.get("title")), pattern);
        };

        PageRequest pageRequest = PageRequest.of(query.page(), query.size(),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<ChatSessionEntity> page = sessionRepository.findAll(spec, pageRequest);
        List<ChatSessionEntity> items = page.getContent();
        if (items.isEmpty()) {
            return new PageDto<>(List.of(), query.page(), query.size(), page.getTotalElements());
        }

        List<String> conversationIds = items.stream()
                .map(ChatSessionEntity::getConversationId)
                .toList();
        Map<String, Long> counts = messageRepository.countGroupedByConversationId(conversationIds).stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).longValue()));

        List<ChatSessionDto> dtos = items.stream()
                .map(session -> toDto(session, counts.getOrDefault(session.getConversationId(), 0L)))
                .toList();
        return new PageDto<>(dtos, query.page(), query.size(), page.getTotalElements());
    }

    /**
     * 校验并取回会话实体；不存在时抛「资源不存在」。
     */
    @Transactional(readOnly = true)
    public ChatSessionEntity requireSession(String conversationId) {
        validateConversationId(conversationId);
        return sessionRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("会话不存在：" + conversationId));
    }

    /**
     * 删除会话及其全部消息，并清理该会话的附件文件。
     */
    @Transactional
    public void delete(String conversationId) {
        ChatSessionEntity session = requireSession(conversationId);
        // 附件快照必须在删记录之前取：删完就再也找不到这些 id，磁盘文件会永久残留
        List<ChatAttachmentDto> attachments = parseAttachments(
                messageRepository.findAttachmentJsonByConversationId(conversationId));
        // 数据库外键是 ON DELETE CASCADE，这里显式删除是为了让持久化上下文与库保持一致，
        // 避免后续查询命中已被级联删除的实体
        messageRepository.deleteByConversationId(conversationId);
        sessionRepository.delete(session);
        log.info("删除会话 conversationId={} 附件数={}", conversationId, attachments.size());

        // 文件删除放到事务提交之后：先删文件再回滚，会出现「记录还在、文件没了」的
        // 半损坏状态，比残留文件糟得多
        deleteFilesAfterCommit(attachments);
    }

    /** 把库里存的附件快照（JSON 串列表）解析成名片；坏数据按跳过处理 */
    private List<ChatAttachmentDto> parseAttachments(List<String> jsonList) {
        if (jsonList == null || jsonList.isEmpty()) {
            return List.of();
        }
        return jsonList.stream()
                .flatMap(json -> attachmentService.fromJson(json).stream())
                .toList();
    }

    /**
     * 事务提交后再删磁盘文件。
     *
     * <p>没有活跃事务时（例如被非事务上下文调用）直接删 —— 此时也不存在「回滚后该恢复」
     * 的语义。判断放在这里而不是靠调用约定，是为了让这个方法在任何入口下都安全。
     */
    private void deleteFilesAfterCommit(List<ChatAttachmentDto> attachments) {
        if (attachments.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            attachmentService.deleteAll(attachments);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                attachmentService.deleteAll(attachments);
            }
        });
    }

    /**
     * 若会话仍使用默认标题且尚无历史消息，则用首条用户消息生成标题（FR-017）。
     *
     * <p>必须在消息落库**之前**调用，否则会被「已有消息」的条件挡掉。
     */
    @Transactional
    public void initializeTitleFromFirstMessage(String conversationId, String firstUserContent) {
        if (firstUserContent == null || firstUserContent.isBlank()) {
            return;
        }
        sessionRepository.findById(conversationId).ifPresent(session -> {
            if (!DEFAULT_TITLE.equals(session.getTitle())) {
                return;
            }
            if (messageRepository.countByConversationId(conversationId) > 0) {
                return;
            }
            session.setTitle(deriveTitle(firstUserContent));
            sessionRepository.save(session);
        });
    }

    /**
     * 会话标识格式校验（FR-015）。
     */
    public void validateConversationId(String conversationId) {
        if (conversationId == null || !CONVERSATION_ID_PATTERN.matcher(conversationId).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "会话标识格式非法");
        }
    }

    private String deriveTitle(String content) {
        String oneLine = content.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= AUTO_TITLE_MAX_LENGTH
                ? oneLine
                : oneLine.substring(0, AUTO_TITLE_MAX_LENGTH);
    }

    private ChatSessionDto toDto(ChatSessionEntity entity, long messageCount) {
        return new ChatSessionDto(entity.getConversationId(), entity.getTitle(),
                entity.getCreatedAt(), entity.getUpdatedAt(), messageCount);
    }
}
