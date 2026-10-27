package com.personalos.chat.service;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.chat.entity.MessageRole;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.common.ai.PostTurnContributor;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 一轮对话结束时的落库收尾。
 *
 * <h2>为什么拆成两个方法而不是一个</h2>
 * 因为**时序是契约**，不是实现细节：
 * <ul>
 *   <li>{@link #attachCitations} 必须在 {@code done} 事件**之前**完成 ——
 *       客户端一收到 done 就可能立刻去查库，此时引用还没提交就会看到 0 条。
 *       这个顺序曾经改过一次（先 done 后挂引用），集成测试在 CI 上间歇性失败，
 *       本地因时序宽松看不出来。</li>
 *   <li>{@link #afterDone} 里的附件、摘要、轮末钩子都只影响**下一轮**或仅用于展示，
 *       放在 done 之后不拖延客户端。</li>
 * </ul>
 * 两个方法名分别带 «attach/after» 前缀，调用点一眼能看出先后关系。
 *
 * <h2>为什么失败只记日志</h2>
 * 收尾属于「锦上添花」：引用、附件、摘要任一失败都不该让已经产生的回答消失。
 * 每处都包 try/catch 并在消息里写明「不影响本轮回答」，是为了让排障的人
 * 一眼判断「要不要管」。
 *
 * <p>从 {@code ChatCompletionService} 抽出（P2 重构）：这个类原本要同时关心
 * 「怎么推流」和「流完了往库里写什么」，两件事的变更原因完全不同。
 */
@Service
public class ChatTurnFinisher {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnFinisher.class);

    private final ChatCitationService citationService;
    private final ChatAttachmentService attachmentService;
    private final ChatMessageRepository messageRepository;
    private final ChatSummaryService summaryService;
    private final ObjectProvider<PostTurnContributor> postTurnContributors;

    public ChatTurnFinisher(ChatCitationService citationService,
                            ChatAttachmentService attachmentService,
                            ChatMessageRepository messageRepository,
                            ChatSummaryService summaryService,
                            ObjectProvider<PostTurnContributor> postTurnContributors) {
        this.citationService = citationService;
        this.attachmentService = attachmentService;
        this.messageRepository = messageRepository;
        this.summaryService = summaryService;
        this.postTurnContributors = postTurnContributors;
    }

    /**
     * 把本轮引用挂到最新的助手消息上。
     *
     * <p><b>必须在发出 done 事件之前调用</b>（见类注释）。
     *
     * @param hits 本轮知识库命中；空表示不挂引用
     */
    public void attachCitations(String conversationId, List<KnowledgeHitDto> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        try {
            citationService.attachToLatestAssistant(conversationId, hits);
        } catch (RuntimeException ex) {
            log.warn("引用挂载失败（不影响本轮回答） conversationId={}", conversationId, ex);
        }
    }

    /**
     * done 之后的收尾：附件补写、摘要刷新、轮末钩子。
     *
     * <p>这些都在同一个线程里串行执行，因此任何一步慢都会推迟「流的完全收尾」
     * （但不会推迟 done 事件本身）。
     *
     * @param userContent 本轮用户消息，轮末钩子需要它做记忆沉淀
     * @param reply       本轮助手回复全文
     */
    public void afterDone(String conversationId, String userContent, String reply,
                          List<ChatAttachmentDto> requestAttachments) {
        attachRequestAttachments(conversationId, requestAttachments);
        // 摘要刷新与记忆沉淀都只影响**下一轮**，因此一律异步提交，
        // 绝不让它们拖住本轮的收尾
        summaryService.refreshAsync(conversationId);
        postTurnContributors.orderedStream().forEach(contributor -> {
            try {
                contributor.afterTurn(conversationId, userContent, reply);
            } catch (RuntimeException ex) {
                // SPI 实现若同步抛错，不能影响已经产生的回答
                log.warn("轮末钩子执行失败 contributor={} conversationId={}",
                        contributor.getClass().getSimpleName(), conversationId, ex);
            }
        });
    }

    /**
     * 附件挂载：附件属于用户发的那条消息，而它由 Advisor 落库，
     * 因此只能在此刻补写 attachments 列 —— 与引用挂载同一时机、同一些约束。
     */
    private void attachRequestAttachments(String conversationId, List<ChatAttachmentDto> requestAttachments) {
        List<ChatAttachmentDto> attachments = attachmentService.verify(requestAttachments);
        if (attachments.isEmpty()) {
            return;
        }
        try {
            messageRepository
                    .findFirstByConversationIdAndMessageTypeOrderBySequenceNoDesc(
                            conversationId, MessageRole.USER)
                    .ifPresent(message -> {
                        message.setAttachments(attachmentService.toJson(attachments));
                        messageRepository.save(message);
                    });
        } catch (RuntimeException ex) {
            log.warn("附件挂载失败（不影响本轮回答） conversationId={}", conversationId, ex);
        }
    }
}
