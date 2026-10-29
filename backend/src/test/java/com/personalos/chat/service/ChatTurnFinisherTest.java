package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.common.ai.PostTurnContributor;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.support.StaticObjectProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

/**
 * {@link ChatTurnFinisher} 的契约测试。
 *
 * <p>这个类值得单独测的原因不是「逻辑复杂」，而是**失败方式很特殊**：
 * 所有分支都以「记日志、继续」结束。写错了不会抛异常，只会静默地少挂一条引用 /
 * 少刷一次摘要 —— 而这类缺失要等用户发现「引用点不开」才会暴露。
 */
class ChatTurnFinisherTest {

    private static final String CONVERSATION = "c-1";

    private ChatCitationService citationService;
    private ChatAttachmentService attachmentService;
    private ChatMessageRepository messageRepository;
    private ChatSummaryService summaryService;
    private ChatMemory chatMemory;
    private PostTurnContributor firstHook;
    private PostTurnContributor secondHook;
    private ChatTurnFinisher finisher;

    @BeforeEach
    void setUp() {
        citationService = mock(ChatCitationService.class);
        attachmentService = mock(ChatAttachmentService.class);
        messageRepository = mock(ChatMessageRepository.class);
        summaryService = mock(ChatSummaryService.class);
        chatMemory = mock(ChatMemory.class);
        firstHook = mock(PostTurnContributor.class);
        secondHook = mock(PostTurnContributor.class);

        finisher = new ChatTurnFinisher(citationService, attachmentService, messageRepository,
                summaryService, chatMemory, new StaticObjectProvider<>(List.of(firstHook, secondHook)));

        // 默认无附件：大多数用例只关心别的分支
        when(attachmentService.verify(any())).thenReturn(List.of());
    }

    private KnowledgeHitDto hit() {
        return new KnowledgeHitDto(UUID.randomUUID(), "标题", "a.md", 0, "片段", 0.6);
    }

    @Test
    @DisplayName("有命中时把引用挂到最新助手消息上")
    void attachesCitations() {
        List<KnowledgeHitDto> hits = List.of(hit());

        finisher.attachCitations(CONVERSATION, hits);

        verify(citationService).attachToLatestAssistant(CONVERSATION, hits);
    }

    @Test
    @DisplayName("无命中时完全不碰引用服务（避免写入一条空记录）")
    void skipsCitationsWhenEmpty() {
        finisher.attachCitations(CONVERSATION, List.of());
        finisher.attachCitations(CONVERSATION, null);

        verifyNoInteractions(citationService);
    }

    @Test
    @DisplayName("引用挂载失败不向外抛 —— 回答已经产生，不能因为它消失")
    void swallowsCitationFailure() {
        doThrow(new IllegalStateException("库挂了"))
                .when(citationService).attachToLatestAssistant(anyString(), any());

        finisher.attachCitations(CONVERSATION, List.of(hit()));

        // 走到这里没抛就说明被吞掉了；再确认确实尝试过
        verify(citationService).attachToLatestAssistant(eq(CONVERSATION), any());
    }

    @Test
    @DisplayName("done 之后刷新摘要，并按注册顺序调用每个轮末钩子")
    void refreshesSummaryAndRunsHooksInOrder() {
        finisher.afterDone(CONVERSATION, "用户消息", "助手回复");

        InOrder order = inOrder(summaryService, firstHook, secondHook);
        order.verify(summaryService).refreshAsync(CONVERSATION);
        order.verify(firstHook).afterTurn(CONVERSATION, "用户消息", "助手回复");
        order.verify(secondHook).afterTurn(CONVERSATION, "用户消息", "助手回复");
    }

    @Test
    @DisplayName("某个钩子抛错时，后面的钩子照样执行（一个模块坏了不该拖垮别的）")
    void oneHookFailureDoesNotBlockOthers() {
        doThrow(new RuntimeException("记忆模块坏了"))
                .when(firstHook).afterTurn(anyString(), anyString(), anyString());

        finisher.afterDone(CONVERSATION, "问", "答");

        verify(secondHook).afterTurn(CONVERSATION, "问", "答");
    }

    @Test
    @DisplayName("没有附件时不查消息表（每轮都查一次是纯浪费）")
    void skipsAttachmentLookupWhenNone() {
        finisher.attachAttachments(CONVERSATION, List.of());

        verifyNoInteractions(messageRepository);
    }

    @Test
    @DisplayName("附件补写失败不影响摘要与钩子")
    void attachmentFailureDoesNotBlockRest() {
        ChatAttachmentDto attachment = mock(ChatAttachmentDto.class);
        when(attachmentService.verify(any())).thenReturn(List.of(attachment));
        when(attachmentService.toJson(any())).thenReturn("[]");
        when(messageRepository.findFirstByConversationIdAndMessageTypeOrderBySequenceNoDesc(anyString(), any()))
                .thenThrow(new IllegalStateException("库挂了"));

        finisher.attachAttachments(CONVERSATION, List.of(attachment));
        finisher.afterDone(CONVERSATION, "问", "答");

        verify(summaryService).refreshAsync(CONVERSATION);
        verify(firstHook).afterTurn(CONVERSATION, "问", "答");
    }

    @Test
    @DisplayName("附件已校验通过但消息不存在时，不抛异常（调用方无法补救）")
    void toleratesMissingMessage() {
        ChatAttachmentDto attachment = mock(ChatAttachmentDto.class);
        when(attachmentService.verify(any())).thenReturn(List.of(attachment));
        when(messageRepository.findFirstByConversationIdAndMessageTypeOrderBySequenceNoDesc(anyString(), any()))
                .thenReturn(java.util.Optional.empty());

        finisher.attachAttachments(CONVERSATION, List.of(attachment));

        assertThat(true).as("走到这里且没有异常即通过").isTrue();
        verify(messageRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ 中断回答落库

    @SuppressWarnings("unchecked")
    private List<Message> capturedMessages() {
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory).add(eq(CONVERSATION), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("中断时把半截回答落库并标注中断（否则历史里留下只有提问没有回答的记录）")
    void recordsPartialReplyOnInterruption() {
        finisher.recordInterruptedReply(CONVERSATION, "我正在为你整理");

        List<Message> saved = capturedMessages();
        assertThat(saved).hasSize(1);
        String content = saved.get(0).getText();
        assertThat(content).startsWith("我正在为你整理").contains("中断");
        assertThat(saved.get(0)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    @DisplayName("一个字都没生成时也写一条标记：否则悬空的提问仍会留在上下文里")
    void recordsMarkerWhenNothingGenerated() {
        finisher.recordInterruptedReply(CONVERSATION, "   ");

        String content = capturedMessages().get(0).getText();
        assertThat(content).isNotBlank().contains("中断");
    }

    @Test
    @DisplayName("落库失败不外抛：已经推给客户端的内容不能因此消失")
    void swallowsRecordFailure() {
        doThrow(new IllegalStateException("库挂了"))
                .when(chatMemory).add(anyString(), anyList());

        finisher.recordInterruptedReply(CONVERSATION, "半截");

        verify(chatMemory).add(eq(CONVERSATION), anyList());
    }
}
