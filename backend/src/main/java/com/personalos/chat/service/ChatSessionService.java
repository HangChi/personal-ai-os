package com.personalos.chat.service;

import com.personalos.chat.dto.ChatSessionDto;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.repository.ChatSessionRepository;
import com.personalos.common.api.ErrorCode;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ChatSessionService(ChatSessionRepository sessionRepository,
                              ChatMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
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
     * 校验并取回会话实体；不存在时抛「资源不存在」。
     */
    @Transactional(readOnly = true)
    public ChatSessionEntity requireSession(String conversationId) {
        validateConversationId(conversationId);
        return sessionRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("会话不存在：" + conversationId));
    }

    /**
     * 删除会话及其全部消息。
     */
    @Transactional
    public void delete(String conversationId) {
        ChatSessionEntity session = requireSession(conversationId);
        // 数据库外键是 ON DELETE CASCADE，这里显式删除是为了让持久化上下文与库保持一致，
        // 避免后续查询命中已被级联删除的实体
        messageRepository.deleteByConversationId(conversationId);
        sessionRepository.delete(session);
        log.info("删除会话 conversationId={}", conversationId);
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
