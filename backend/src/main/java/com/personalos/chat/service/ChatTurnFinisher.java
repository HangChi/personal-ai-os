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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
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

    /** 中断标记。措辞要让人和模型都能读懂「这条回答没说完」 */
    private static final String INTERRUPTED_MARKER = "（本条回答因连接中断未说完）";

    private final ChatCitationService citationService;
    private final ChatAttachmentService attachmentService;
    private final ChatMessageRepository messageRepository;
    private final ChatSummaryService summaryService;
    private final ChatMemory chatMemory;
    private final ObjectProvider<PostTurnContributor> postTurnContributors;

    public ChatTurnFinisher(ChatCitationService citationService,
                            ChatAttachmentService attachmentService,
                            ChatMessageRepository messageRepository,
                            ChatSummaryService summaryService,
                            ChatMemory chatMemory,
                            ObjectProvider<PostTurnContributor> postTurnContributors) {
        this.citationService = citationService;
        this.attachmentService = attachmentService;
        this.messageRepository = messageRepository;
        this.summaryService = summaryService;
        this.chatMemory = chatMemory;
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
     * 流没有正常结束时，把已经产生的半截回答落库并标注中断。
     *
     * <h2>为什么这条记录不能省</h2>
     * 用户消息由记忆 Advisor 在**订阅时**就写入，而助手消息要等流正常终止才写。
     * 于是超时/上游报错时，历史里会留下一条「只有提问、没有回答」的记录：
     * 下一轮模型会把它读成一个悬空的问题，摘要也会把它当作既有事实压缩进去 ——
     * 用户看到的是「助手忘了我刚才问了什么」，而代码看起来毫无问题。
     *
     * <p>标注「回答中断」而不是假装完整：模型据此知道这条回答不完整，
     * 不会把半句话当成结论。
     *
     * @param partialReply 已经推给客户端的增量；为空时写入纯标记文案，
     *                     否则悬空的提问仍在上下文里
     */
    public void recordInterruptedReply(String conversationId, String partialReply) {
        String body = partialReply == null ? "" : partialReply.strip();
        String content = body.isEmpty() ? INTERRUPTED_MARKER : body + "\n\n" + INTERRUPTED_MARKER;
        try {
            chatMemory.add(conversationId, List.of(new AssistantMessage(content)));
        } catch (RuntimeException ex) {
            log.warn("中断回答落库失败（不影响已推送的内容） conversationId={}", conversationId, ex);
        }
    }

    /**
     * done 之后的收尾：摘要刷新、轮末钩子。
     *
     * <p>这些都在同一个线程里串行执行，因此任何一步慢都会推迟「流的完全收尾」
     * （但不会推迟 done 事件本身）。
     *
     * <p><b>附件已从这里移走</b>（2026-10-29）：附件名片是用户消息的一部分，
     * 必须与引用一样在 done **之前**落库。留在这里的后果是——客户端收到 done
     * 立刻拉消息，看到的是没有附件的用户消息，几十毫秒后才补上；
     * 而 api.md 写的是「done 表示该落的库都落完了」，两者直接矛盾。
     *
     * @param userContent 本轮用户消息，轮末钩子需要它做记忆沉淀
     * @param reply       本轮助手回复全文
     */
    public void afterDone(String conversationId, String userContent, String reply) {
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
     * 因此只能在此刻补写 {@code attachments} 列 —— 与引用挂载同一时机、同一些约束。
     *
     * <p>**必须在 done 之前调用**：{@code done} 的契约是「该落的库都落完了」，
     * 客户端收到它之后会立刻拉消息列表。放在 done 之后会让附件短暂「消失」，
     * 而这种几十毫秒的窗口在慢机器上足以被用户当成 bug 报上来。
     */
    public void attachAttachments(String conversationId, List<ChatAttachmentDto> requestAttachments) {
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
