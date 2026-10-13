package com.personalos.chat.service;

import com.personalos.chat.dto.ChatSessionDto;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.repository.ChatSessionRepository;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
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

    private static final int TITLE_MAX_LENGTH = 200;
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
        String normalizedTitle = (title == null || title.isBlank()) ? DEFAULT_TITLE : title.strip();
        if (normalizedTitle.length() > TITLE_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "标题长度不能超过 " + TITLE_MAX_LENGTH);
        }

        Instant now = Instant.now();
        ChatSessionEntity saved = sessionRepository.save(
                new ChatSessionEntity(UUID.randomUUID().toString(), normalizedTitle, now, now));
        log.info("创建会话 conversationId={}", saved.getConversationId());
        return toDto(saved, 0L);
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
