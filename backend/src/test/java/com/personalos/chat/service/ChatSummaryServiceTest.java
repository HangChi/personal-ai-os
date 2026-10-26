package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.entity.MessageRole;
import com.personalos.chat.memory.JpaChatMemory;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.repository.ChatSessionRepository;
import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.ai.ContextProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * {@link ChatSummaryService} 单元测试。
 *
 * <p>重点是三条**不该发生的事**（它们比「能生成摘要」更重要）：
 * <ol>
 *   <li>窗口边界没推进时，不调模型（否则每轮都白烧一次请求）；</li>
 *   <li>新增消息不足阈值时，不调模型；</li>
 *   <li>功能关闭时，既不渲染也不生成。</li>
 * </ol>
 * 「生成成功」这条路径由集成测试覆盖（那里有真实的 stub 模型）。
 */
class ChatSummaryServiceTest {

    private static final String CID = "44444444-4444-4444-4444-444444444444";

    private final ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
    private final JpaChatMemory chatMemory = mock(JpaChatMemory.class);
    private final AiChatClientProvider aiProvider = mock(AiChatClientProvider.class);
    private final ExecutorService executor = mock(ExecutorService.class);

    /** 无资源的假事务管理器：本类只验证「是否调用模型」，不需要真事务 */
    private final PlatformTransactionManager transactionManager = new AbstractPlatformTransactionManager() {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    };

    private ChatSummaryService service(ContextProperties properties) {
        return new ChatSummaryService(sessionRepository, messageRepository, chatMemory, aiProvider,
                properties, executor, transactionManager);
    }

    private static ContextProperties enabled(int minMessages) {
        return new ContextProperties(
                new ContextProperties.Summary(true, minMessages, 4000, 1200),
                new ContextProperties.AutoMemory(false, 3));
    }

    @Test
    @DisplayName("renderForConversation(): 有摘要时带上标题块")
    void rendersSummaryBlock() {
        ChatSessionEntity session = new ChatSessionEntity(CID, "标题", Instant.EPOCH, Instant.EPOCH);
        session.applySummary("用户在做个人知识库项目", 8);
        given(sessionRepository.findById(CID)).willReturn(Optional.of(session));

        String block = service(enabled(4)).renderForConversation(CID);

        assertThat(block).startsWith("## 早前对话摘要").contains("用户在做个人知识库项目");
    }

    @Test
    @DisplayName("renderForConversation(): 无摘要返回空串（调用方据此完全不追加）")
    void rendersEmptyWhenNoSummary() {
        given(sessionRepository.findById(CID))
                .willReturn(Optional.of(new ChatSessionEntity(CID, "标题", Instant.EPOCH, Instant.EPOCH)));

        assertThat(service(enabled(4)).renderForConversation(CID)).isEmpty();
    }

    @Test
    @DisplayName("功能关闭时：不渲染、也不提交后台任务")
    void disabledMeansNoOp() {
        ContextProperties disabled = new ContextProperties(
                new ContextProperties.Summary(false, 4, 4000, 1200),
                new ContextProperties.AutoMemory(false, 3));
        ChatSummaryService service = service(disabled);

        assertThat(service.renderForConversation(CID)).isEmpty();
        service.refreshAsync(CID);

        verifyNoInteractions(sessionRepository, executor, aiProvider);
    }

    @Test
    @DisplayName("refresh(): 窗口边界没推进 → 不调模型")
    void skipsWhenBoundaryNotAdvanced() {
        ChatSessionEntity session = new ChatSessionEntity(CID, "标题", Instant.EPOCH, Instant.EPOCH);
        session.applySummary("已经覆盖到第 8 条", 8);
        given(sessionRepository.findById(CID)).willReturn(Optional.of(session));
        // 窗口起点 9 ⇒ 边界 8，与已覆盖边界相同
        given(chatMemory.contextStartSequence(CID)).willReturn(9);

        service(enabled(1)).refresh(CID);

        verifyNoInteractions(aiProvider);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("refresh(): 新增消息不足阈值 → 不调模型")
    void skipsBelowMinMessages() {
        ChatSessionEntity session = new ChatSessionEntity(CID, "标题", Instant.EPOCH, Instant.EPOCH);
        given(sessionRepository.findById(CID)).willReturn(Optional.of(session));
        given(chatMemory.contextStartSequence(CID)).willReturn(9);
        given(messageRepository.findRangeBySequenceNo(eq(CID), anyInt(), anyInt()))
                .willReturn(List.of(message(1, "只有一条")));

        service(enabled(4)).refresh(CID);

        verifyNoInteractions(aiProvider);
    }

    @Test
    @DisplayName("buildTranscript(): 从新往旧截断，且最新一条即使超上限也保留")
    void transcriptTruncatesFromNewest() {
        List<ChatMessageEntity> messages = new ArrayList<>();
        messages.add(message(1, "x".repeat(100)));
        messages.add(message(2, "y".repeat(100)));
        messages.add(message(3, "z".repeat(100)));

        String transcript = ChatSummaryService.buildTranscript(messages, 120);

        // 最新一条（z）必留；向上只能再放一条，最早那条被丢掉
        assertThat(transcript).contains("z".repeat(100));
        assertThat(transcript).doesNotContain("x".repeat(100));
        assertThat(transcript.indexOf("y")).isLessThan(transcript.indexOf("z"));
    }

    @Test
    @DisplayName("buildTranscript(): 角色标签区分用户与助手")
    void transcriptLabelsRoles() {
        String transcript = ChatSummaryService.buildTranscript(List.of(
                new ChatMessageEntity(CID, MessageRole.USER, "问题", 1, Instant.now()),
                new ChatMessageEntity(CID, MessageRole.ASSISTANT, "回答", 2, Instant.now())), 4000);

        assertThat(transcript).isEqualTo("用户：问题\n助手：回答");
    }

    private static ChatMessageEntity message(int sequence, String content) {
        return new ChatMessageEntity(CID, MessageRole.USER, content, sequence, Instant.now());
    }
}
