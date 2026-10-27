package com.personalos.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.entity.MessageRole;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.repository.ChatSessionRepository;
import com.personalos.common.ai.AiProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * {@link JpaChatMemory} 单元测试。
 *
 * <p>重点锁定两件事：
 * <ol>
 *   <li>{@code add} 是**追加**语义（顺序号续接、不删除任何记录）；</li>
 *   <li>{@code get} 只取最近 N 条并转成正序。</li>
 * </ol>
 * 这两条是「历史不被窗口裁剪」的实现保证。
 */
class JpaChatMemoryTest {

    private static final String CID = "33333333-3333-3333-3333-333333333333";

    private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
    private final ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
    /** 预算服务：显式设成 12000 字符等价的 token 数，与旧行为一致 */
    private final com.personalos.common.ai.ContextBudgetService budget = budgetWithChars(12000);
    /**
     * 事务管理器替身：让 TransactionTemplate 直接执行回调、不真开事务。
     * 这里测的是「顺序号续接 / 角色映射」等纯逻辑，事务语义由集成测试覆盖。
     */
    private final PlatformTransactionManager transactionManager = noOpTransactionManager();

    private final JpaChatMemory memory = new JpaChatMemory(messageRepository, sessionRepository,
            new AiProperties(new AiProperties.Memory(6, 0), "系统提示"), budget, transactionManager);

    private static PlatformTransactionManager noOpTransactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        given(manager.getTransaction(any())).willReturn(mock(TransactionStatus.class));
        return manager;
    }

    @Test
    @DisplayName("add(): 顺序号从当前最大值续接，且不触发任何删除")
    void addShouldAppendWithContinuingSequence() {
        given(messageRepository.findMaxSequenceNo(CID)).willReturn(4);
        given(sessionRepository.findById(CID)).willReturn(Optional.empty());

        memory.add(CID, List.of(new UserMessage("你好"), new AssistantMessage("你好！")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(captor.capture());

        List<ChatMessageEntity> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getSequenceNo()).isEqualTo(5);
        assertThat(saved.get(1).getSequenceNo()).isEqualTo(6);
        assertThat(saved.get(0).getMessageType()).isEqualTo(MessageRole.USER);
        assertThat(saved.get(1).getMessageType()).isEqualTo(MessageRole.ASSISTANT);
        // 追加语义的关键断言：绝不能出现删除
        verify(messageRepository, never()).deleteByConversationId(anyString());
    }

    @Test
    @DisplayName("add(): 同时刷新会话的最近活动时间")
    void addShouldTouchSession() {
        given(messageRepository.findMaxSequenceNo(CID)).willReturn(0);
        ChatSessionEntity session = new ChatSessionEntity(CID, "标题", Instant.EPOCH, Instant.EPOCH);
        given(sessionRepository.findById(CID)).willReturn(Optional.of(session));
        given(sessionRepository.save(any(ChatSessionEntity.class))).willAnswer(i -> i.getArgument(0));

        memory.add(CID, List.of(new UserMessage("hi")));

        assertThat(session.getUpdatedAt()).isAfter(Instant.EPOCH);
    }

    @Test
    @DisplayName("add(): 空列表是安全空操作")
    void addShouldIgnoreEmptyList() {
        memory.add(CID, List.of());
        memory.add(CID, (List<Message>) null);

        verify(messageRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("get(): 只取最近 N 条、并按正序返回（仓储倒序取出的结果被反转）")
    void getShouldReturnWindowInAscendingOrder() {
        int limit = 6;
        List<ChatMessageEntity> descending = new ArrayList<>();
        // 模拟仓储按 sequenceNo 倒序返回最近 6 条：seq 9..4
        // 注意角色按奇偶交替，seq=4 为 USER，确保反转后首条是 USER
        for (int sequence = 9; sequence >= 4; sequence--) {
            descending.add(new ChatMessageEntity(CID,
                    sequence % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT,
                    "msg-" + sequence, sequence, Instant.now()));
        }
        given(messageRepository.findRecentByConversationId(eq(CID), any(Pageable.class))).willReturn(descending);

        List<Message> messages = memory.get(CID);

        assertThat(messages).hasSize(limit);
        assertThat(messages.get(0).getText()).isEqualTo("msg-4");
        assertThat(messages.get(5).getText()).isEqualTo("msg-9");
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    @DisplayName("get(): 按配置的 max-messages 请求分页，而不是全量")
    void getShouldRespectConfiguredLimit() {
        given(messageRepository.findRecentByConversationId(eq(CID), any(Pageable.class))).willReturn(List.of());

        memory.get(CID);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findRecentByConversationId(eq(CID), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(6);
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("get(): 字符预算先用尽时，更早的消息整体丢弃（条数没到上限也一样）")
    void getShouldTrimByCharacterBudget() {
        JpaChatMemory budgeted = new JpaChatMemory(messageRepository, sessionRepository,
                new AiProperties(new AiProperties.Memory(10, 0), "系统提示"),
                budgetWithChars(250), transactionManager);
        given(messageRepository.findRecentByConversationId(eq(CID), any(Pageable.class)))
                .willReturn(threeMessages(100));

        List<Message> messages = budgeted.get(CID);

        // 100 + 100 = 200 ≤ 250，再加第三条就是 300 > 250 → 只保留最新两条
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText()).startsWith("msg-2-");
        assertThat(messages.get(1).getText()).startsWith("msg-3-");
    }

    @Test
    @DisplayName("get(): 最新一条即使单条超预算也必须保留（不能送空上下文）")
    void getShouldAlwaysKeepNewestMessage() {
        JpaChatMemory tight = new JpaChatMemory(messageRepository, sessionRepository,
                new AiProperties(new AiProperties.Memory(10, 0), "系统提示"),
                budgetWithChars(250), transactionManager);
        given(messageRepository.findRecentByConversationId(eq(CID), any(Pageable.class)))
                .willReturn(threeMessages(200));

        List<Message> messages = tight.get(CID);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getText()).startsWith("msg-3-");
    }

    /** 构造三条各 length 字的「新 → 旧」消息（seq 3/2/1），与仓储返回顺序一致；内容带序号便于断言 */
    private static List<ChatMessageEntity> threeMessages(int length) {
        return List.of(
                sizedMessage(3, length),
                sizedMessage(2, length),
                sizedMessage(1, length));
    }

    /** 造一个「字符预算 = chars」的预算服务（内部按 1.6 折算成 token） */
    private static com.personalos.common.ai.ContextBudgetService budgetWithChars(int chars) {
        com.personalos.common.ai.ContextBudgetService service =
                new com.personalos.common.ai.ContextBudgetService(
                        new AiProperties(new AiProperties.Memory(10, 0), "系统提示"));
        service.setBudgetTokens(Math.max(1, (int) Math.round(chars / com.personalos.common.ai.ContextBudgetService.CHARS_PER_TOKEN)));
        return service;
    }

    private static ChatMessageEntity sizedMessage(int sequence, int length) {
        String prefix = "msg-" + sequence + "-";
        return new ChatMessageEntity(CID, MessageRole.ASSISTANT,
                prefix + "x".repeat(Math.max(0, length - prefix.length())), sequence, Instant.now());
    }

    @Test
    @DisplayName("get(): 三种角色都能正确还原为 Spring AI 消息类型")
    void getShouldMapRoles() {
        given(messageRepository.findRecentByConversationId(eq(CID), any(Pageable.class))).willReturn(List.of(
                new ChatMessageEntity(CID, MessageRole.SYSTEM, "s", 3, Instant.now()),
                new ChatMessageEntity(CID, MessageRole.ASSISTANT, "a", 2, Instant.now()),
                new ChatMessageEntity(CID, MessageRole.USER, "u", 1, Instant.now())));

        List<Message> messages = memory.get(CID);

        assertThat(messages.get(0).getText()).isEqualTo("u");
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(2)).isInstanceOf(SystemMessage.class);
    }

    @Test
    @DisplayName("clear(): 删除该会话的全部消息")
    void clearShouldDeleteAllMessages() {
        memory.clear(CID);

        verify(messageRepository).deleteByConversationId(CID);
    }

    @Test
    @DisplayName("add(): TOOL 消息不再抛异常，内容序列化为可读文本（审查 #13）")
    void addShouldAcceptToolMessage() {
        given(messageRepository.findMaxSequenceNo(CID)).willReturn(0);
        given(sessionRepository.findById(CID)).willReturn(Optional.empty());

        ToolResponseMessage toolMessage = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("1", "createTask", "任务已创建")))
                .build();

        memory.add(CID, List.of(new UserMessage("创建一个任务"), toolMessage));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(captor.capture());

        List<ChatMessageEntity> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(1).getMessageType()).isEqualTo(MessageRole.TOOL);
        // ToolResponseMessage.getText() 恒为 null，必须序列化 responses 而不是存空串
        assertThat(saved.get(1).getContent()).contains("createTask").contains("任务已创建");
    }

    @Test
    @DisplayName("get(): TOOL 消息读回时降级为 AssistantMessage（无法重建 id/name 结构）")
    void getShouldMapToolToAssistant() {
        given(messageRepository.findRecentByConversationId(eq(CID), any(Pageable.class))).willReturn(List.of(
                new ChatMessageEntity(CID, MessageRole.TOOL, "createTask: 任务已创建", 1, Instant.now())));

        List<Message> messages = memory.get(CID);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("createTask: 任务已创建");
    }
}
