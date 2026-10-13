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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.Pageable;

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
    private final JpaChatMemory memory = new JpaChatMemory(messageRepository, sessionRepository,
            new AiProperties(new AiProperties.Memory(6), "系统提示"));

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
}
