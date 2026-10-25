package com.personalos.chat;

import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.repository.ChatSessionRepository;
import com.personalos.chat.service.ChatSessionService;
import com.personalos.common.search.SearchContributor;
import com.personalos.common.search.SearchHit;
import com.personalos.common.search.SearchPatterns;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话搜索（阶段 17）。
 *
 * <p>搜标题与消息内容，**标题命中优先**：
 * <ul>
 *   <li>标题命中的会话排在前，标注「标题」；</li>
 *   <li>消息内容命中的会话作为补充，标注「消息内容」，且排除已由标题命中的会话
 *       （同一会话只出现一次）。</li>
 * </ul>
 *
 * <p>消息命中返回的是**会话**而不是单条消息：消息量级比元数据大 1~2 个数量级，
 * 直接作为结果会让一次搜索返回几百条，分组就失去意义。
 */
@Component
public class ChatSearchContributor implements SearchContributor {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatSessionService sessionService;

    public ChatSearchContributor(ChatSessionRepository sessionRepository,
                                 ChatMessageRepository messageRepository,
                                 ChatSessionService sessionService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.sessionService = sessionService;
    }

    @Override
    public String moduleName() {
        return "对话";
    }

    @Override
    public String kind() {
        return "session";
    }

    @Override
    @Transactional(readOnly = true)
    public List<SearchHit> search(String keyword, int limit) {
        String pattern = SearchPatterns.likePattern(keyword);
        List<SearchHit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        sessionRepository.findAll(
                (root, query, builder) ->
                        builder.like(builder.lower(root.get("title")), pattern, SearchPatterns.ESCAPE),
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "updatedAt")))
                .getContent()
                .forEach(session -> {
                    seen.add(session.getConversationId());
                    hits.add(toHit(session, "标题"));
                });

        // 标题没填满 limit 时，才用消息内容补齐。
        // 注意传的是 likePattern（已转义通配符），不是原始关键词 —— 少这一步，
        // 用户搜「100%」就会命中「100 之后任意字符」。
        if (hits.size() < limit) {
            List<String> byContent = messageRepository.findConversationIdsByContent(
                    SearchPatterns.likePattern(keyword), PageRequest.of(0, limit * 2));
            for (String conversationId : byContent) {
                if (hits.size() >= limit || seen.contains(conversationId)) {
                    continue;
                }
                sessionRepository.findById(conversationId).ifPresent(session -> {
                    seen.add(session.getConversationId());
                    hits.add(toHit(session, "消息内容"));
                });
            }
        }
        return hits;
    }

    private SearchHit toHit(ChatSessionEntity session, String matchedField) {
        // 消息数单独查：会话实体上没有这个字段，而为它加一个需要在写路径上维护的冗余列
        long messageCount = messageRepository.countByConversationId(session.getConversationId());
        return new SearchHit("session",
                session.getConversationId(),
                session.getTitle(),
                messageCount + " 条消息",
                matchedField);
    }
}
