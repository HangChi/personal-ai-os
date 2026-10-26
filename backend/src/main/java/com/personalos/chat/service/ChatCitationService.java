package com.personalos.chat.service;

import com.personalos.chat.dto.ChatCitationDto;
import com.personalos.chat.entity.ChatMessageCitationEntity;
import com.personalos.chat.repository.ChatMessageCitationRepository;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息引用快照的读写（阶段 19）。
 *
 * <p>独立成 Service 而不是塞进 {@link ChatCompletionService}：落库是写路径，
 * 与「流式编排」的关注点不同；而且查询侧（{@link ChatMessageService}）也要用，
 * 两边共用一个出口，避免同一份数据出现两条读路径。
 */
@Service
public class ChatCitationService {

    private static final Logger log = LoggerFactory.getLogger(ChatCitationService.class);

    private final ChatMessageCitationRepository citationRepository;
    private final ChatMessageRepository messageRepository;

    public ChatCitationService(ChatMessageCitationRepository citationRepository,
                               ChatMessageRepository messageRepository) {
        this.citationRepository = citationRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 把本轮检索命中挂到**该会话最新的助手消息**上。
     *
     * <p>时序依据（Spring AI 2.0.1 实测）：{@code MessageChatMemoryAdvisor.adviseStream}
     * 用 {@code ChatClientMessageAggregator} 在流终止时调 {@code after()} →
     * {@code JpaChatMemory.add()}（{@code @Transactional} 提交），该链路位于调用方
     * 订阅的上游。因此终帧回调里「最新助手消息」就是本轮刚落库的那条。
     *
     * <p>查不到消息（ Advisor 实现变化 / 极端并发）只记 WARN 不抛：
     * 引用是增强信息，不该让一轮成功的对话失败。
     *
     * @param conversationId 会话标识
     * @param hits           检索命中（编号从 1 开始）
     */
    @Transactional
    public void attachToLatestAssistant(String conversationId, List<KnowledgeHitDto> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        var latest = messageRepository.findRecentByConversationId(conversationId,
                PageRequest.of(0, 50)).stream()
                .filter(message -> message.getMessageType().name().equals("ASSISTANT"))
                .findFirst();
        if (latest.isEmpty()) {
            log.warn("终帧未找到可挂载引用的助手消息 conversationId={}", conversationId);
            return;
        }
        Long messageId = latest.get().getId();
        Instant now = Instant.now();
        List<ChatMessageCitationEntity> entities = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeHitDto hit = hits.get(i);
            entities.add(new ChatMessageCitationEntity(
                    messageId,
                    i + 1,
                    hit.documentId().toString(),
                    hit.chunkIndex(),
                    hit.documentTitle(),
                    hit.filename(),
                    hit.content(),
                    now));
        }
        citationRepository.saveAll(entities);
        log.debug("引用挂载完成 conversationId={} messageId={} count={}",
                conversationId, messageId, entities.size());
    }

    /**
     * 按消息 id 批量取引用，按消息分组。
     *
     * <p>一次 in 查询，不 N+1（消息列表可能几百条）。
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ChatCitationDto>> findByMessageIds(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        return citationRepository
                .findByMessageIdInOrderByMessageIdAscCitationIndexAsc(messageIds).stream()
                .collect(Collectors.groupingBy(
                        ChatMessageCitationEntity::getMessageId,
                        Collectors.mapping(
                                entity -> new ChatCitationDto(
                                        entity.getCitationIndex(),
                                        entity.getDocumentId(),
                                        entity.getDocumentTitle(),
                                        entity.getFilename(),
                                        entity.getChunkIndex(),
                                        entity.getContent()),
                                Collectors.toList())));
    }
}
