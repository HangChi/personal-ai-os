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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /** 摘要调用的等待上限；本类不验证超时本身（那条在下面的超时用例里单独给短值） */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

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
                new ContextProperties.Summary(true, minMessages, 4000, 1200, DEFAULT_TIMEOUT),
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
                new ContextProperties.Summary(false, 4, 4000, 1200, DEFAULT_TIMEOUT),
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

    // ------------------------------------------------------------------ 三段事务

    /** 搭好 ChatClient 的流式调用链，返回最后那段可用于打桩/校验的 CallResponseSpec */
    private ChatClient.CallResponseSpec stubModel(String content) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        given(aiProvider.requirePlainChatClient()).willReturn(client);
        given(client.prompt()).willReturn(request);
        given(request.system(any(String.class))).willReturn(request);
        given(request.user(any(String.class))).willReturn(request);
        given(request.call()).willReturn(call);
        given(call.content()).willReturn(content);
        return call;
    }

    @Test
    @DisplayName("模型调用发生在事务之外：否则一次慢摘要会钉住一条数据库连接（池只有 10 条）")
    void modelCallRunsOutsideTransaction() {
        ChatSessionEntity session = new ChatSessionEntity(CID, "标题", Instant.EPOCH, Instant.EPOCH);
        given(sessionRepository.findById(CID)).willReturn(Optional.of(session));
        given(chatMemory.contextStartSequence(CID)).willReturn(9);
        given(messageRepository.findRangeBySequenceNo(eq(CID), anyInt(), anyInt()))
                .willReturn(List.of(message(1, "第一条"), message(2, "第二条")));

        ChatClient.CallResponseSpec call = stubModel("新摘要");
        AtomicBoolean transactionActiveDuringCall = new AtomicBoolean(true);
        given(call.content()).willAnswer(invocation -> {
            transactionActiveDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "新摘要";
        });

        service(enabled(1)).refresh(CID);

        assertThat(transactionActiveDuringCall)
                .as("模型调用必须在两个短事务之间；若为 true，说明又回到「事务内调模型」")
                .isFalse();
        verify(sessionRepository).save(any());
    }

    @Test
    @DisplayName("模型超时 → 保留旧摘要，不写入")
    void timeoutKeepsExistingSummary() {
        ChatSessionEntity session = new ChatSessionEntity(CID, "标题", Instant.EPOCH, Instant.EPOCH);
        session.applySummary("旧摘要", 3);
        given(sessionRepository.findById(CID)).willReturn(Optional.of(session));
        given(chatMemory.contextStartSequence(CID)).willReturn(9);
        given(messageRepository.findRangeBySequenceNo(eq(CID), anyInt(), anyInt()))
                .willReturn(List.of(message(4, "新内容")));

        ChatClient.CallResponseSpec call = stubModel("不会被用到");
        given(call.content()).willAnswer(invocation -> {
            Thread.sleep(500); // 超过下面配置的 50ms 超时
            return "迟到的摘要";
        });

        ContextProperties tightTimeout = new ContextProperties(
                new ContextProperties.Summary(true, 1, 4000, 1200, Duration.ofMillis(50)),
                new ContextProperties.AutoMemory(false, 3));
        service(tightTimeout).refresh(CID);

        verify(sessionRepository, never()).save(any());
        assertThat(session.getContextSummary()).isEqualTo("旧摘要");
    }

    @Test
    @DisplayName("模型调用期间边界已被推进 → 丢弃本次结果，不覆盖更新的摘要")
    void discardsResultWhenBoundaryAdvancedMeanwhile() {
        // 第一次读到的快照：已覆盖 3
        ChatSessionEntity stale = new ChatSessionEntity(CID, "标题", Instant.EPOCH, Instant.EPOCH);
        stale.applySummary("旧摘要", 3);
        // 第二次读（写入前校验）：已被并发推进到 8
        ChatSessionEntity advanced = new ChatSessionEntity(CID, "标题", Instant.EPOCH, Instant.EPOCH);
        advanced.applySummary("并发写入的新摘要", 8);

        // loadSnapshot 读一次，applySnapshot 再读一次
        given(sessionRepository.findById(CID)).willReturn(Optional.of(stale), Optional.of(advanced));
        given(chatMemory.contextStartSequence(CID)).willReturn(9);
        given(messageRepository.findRangeBySequenceNo(eq(CID), anyInt(), anyInt()))
                .willReturn(List.of(message(4, "新内容")));
        stubModel("基于旧输入生成的摘要");

        service(enabled(1)).refresh(CID);

        verify(sessionRepository, never()).save(any());
        assertThat(advanced.getContextSummary()).as("并发写入的摘要不能被覆盖").isEqualTo("并发写入的新摘要");
    }
    @Test
    void preflightSummarizesBeforeSendingEvenBelowAsyncThreshold() {
        var session = new ChatSessionEntity(CID, "title", Instant.EPOCH, Instant.EPOCH);
        given(sessionRepository.findById(CID)).willReturn(Optional.of(session));
        given(messageRepository.findRangeBySequenceNo(eq(CID), anyInt(), anyInt())).willAnswer(invocation -> {
            int from = invocation.getArgument(1);
            return from <= 1 ? List.of(message(1, "important earlier decision")) : List.of();
        });
        stubModel("decision summary");
        service(enabled(4)).refreshBeforeRequest(CID, 1);
        assertThat(session.getSummaryUptoSeq()).isEqualTo(1);
        assertThat(session.getContextSummary()).isEqualTo("decision summary");
        verifyNoInteractions(chatMemory);
    }

    @Test
    void boundedPreflightOnlyMarksOldestBatchesActuallySummarized() {
        var session = new ChatSessionEntity(CID, "title", Instant.EPOCH, Instant.EPOCH);
        given(sessionRepository.findById(CID)).willReturn(Optional.of(session));
        List<ChatMessageEntity> history = List.of(message(1, "a".repeat(60)), message(2, "b".repeat(60)), message(3, "c".repeat(60)));
        given(messageRepository.findRangeBySequenceNo(eq(CID), anyInt(), anyInt())).willAnswer(invocation -> {
            int from = invocation.getArgument(1);
            return history.stream().filter(m -> m.getSequenceNo() >= from).toList();
        });
        var call = stubModel("summary");
        var properties = new ContextProperties(new ContextProperties.Summary(true, 4, 80, 1200, DEFAULT_TIMEOUT), null);
        service(properties).refreshBeforeRequest(CID, 3);
        assertThat(session.getSummaryUptoSeq()).isEqualTo(2);
        verify(call, org.mockito.Mockito.times(2)).content();
    }
    @Test
    void aSingleHugeHistoryMessageCannotOverflowTheSummaryRequest() {
        String transcript = ChatSummaryService.buildTranscript(List.of(message(1, "begin " + "x".repeat(10000) + " end")), 200);
        assertThat(transcript).hasSize(200).contains("仅保留首尾供摘要").startsWith("用户：begin").endsWith(" end");
    }
}
