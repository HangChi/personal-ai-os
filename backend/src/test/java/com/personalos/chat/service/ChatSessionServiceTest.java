package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.repository.ChatSessionRepository;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatSessionService} 单元测试：只 mock 仓储，不启动 Spring。
 */
class ChatSessionServiceTest {

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        service = new ChatSessionService(sessionRepository, messageRepository);
    }

    @Test
    @DisplayName("create(): 未给标题时使用默认标题，并生成非空会话标识")
    void createShouldUseDefaultTitle() {
        given(sessionRepository.save(any(ChatSessionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        var created = service.create(null);

        assertThat(created.conversationId()).isNotBlank();
        assertThat(created.title()).isEqualTo(ChatSessionService.DEFAULT_TITLE);
        assertThat(created.messageCount()).isZero();
        assertThat(created.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("create(): 标题首尾空白被裁剪；空串回落默认标题")
    void createShouldNormalizeTitle() {
        given(sessionRepository.save(any(ChatSessionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.create("  我的会话  ").title()).isEqualTo("我的会话");
        assertThat(service.create("   ").title()).isEqualTo(ChatSessionService.DEFAULT_TITLE);
    }

    @Test
    @DisplayName("create(): 标题超过 200 字被拒绝（VALIDATION_FAILED）")
    void createShouldRejectTooLongTitle() {
        assertThatThrownBy(() -> service.create("x".repeat(201)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("200");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("list(): 无会话时返回空列表而不是 null")
    void listShouldReturnEmptyList() {
        given(sessionRepository.findAllByOrderByUpdatedAtDesc()).willReturn(List.of());

        assertThat(service.list()).isEmpty();
        // 无会话时不应发起统计查询
        verify(messageRepository, never()).countGroupedByConversationId(any());
    }

    @Test
    @DisplayName("requireSession(): 不存在的会话抛出「资源不存在」")
    void requireSessionShouldThrowWhenMissing() {
        given(sessionRepository.findById("00000000-0000-0000-0000-000000000000")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireSession("00000000-0000-0000-0000-000000000000"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("requireSession(): 非法会话标识被拒绝，且不查库（边界校验）")
    void requireSessionShouldRejectInvalidId() {
        assertThatThrownBy(() -> service.requireSession("bad id!"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.requireSession("a".repeat(65)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.requireSession(null))
                .isInstanceOf(BusinessException.class);
        verify(sessionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("initializeTitleFromFirstMessage(): 仅在默认标题且无历史时改写，取前 30 字")
    void shouldDeriveTitleFromFirstMessageOnlyOnce() {
        String id = "11111111-1111-1111-1111-111111111111";
        ChatSessionEntity session = new ChatSessionEntity(id, ChatSessionService.DEFAULT_TITLE, Instant.now(), Instant.now());
        given(sessionRepository.findById(id)).willReturn(Optional.of(session));
        given(messageRepository.countByConversationId(id)).willReturn(0L);
        given(sessionRepository.save(any(ChatSessionEntity.class))).willAnswer(i -> i.getArgument(0));

        String longMessage = "这是一条很长的首条消息，用来验证标题会被截断到三十个字符以内，后面还有很多字。";
        service.initializeTitleFromFirstMessage(id, longMessage);

        assertThat(session.getTitle()).hasSize(30).isEqualTo(longMessage.substring(0, 30));
    }

    @Test
    @DisplayName("initializeTitleFromFirstMessage(): 用户自定义标题不被覆盖")
    void shouldNotOverrideCustomTitle() {
        String id = "22222222-2222-2222-2222-222222222222";
        ChatSessionEntity session = new ChatSessionEntity(id, "我起的名字", Instant.now(), Instant.now());
        given(sessionRepository.findById(id)).willReturn(Optional.of(session));

        service.initializeTitleFromFirstMessage(id, "随便说点什么");

        assertThat(session.getTitle()).isEqualTo("我起的名字");
        verify(sessionRepository, never()).save(any());
    }

    // ------------------------------------------------------------ 重命名（阶段 7）

    @Test
    @DisplayName("rename(): 只改标题，不刷新最近活动时间，并返回消息条数")
    void renameShouldUpdateTitleOnly() {
        String id = "33333333-3333-3333-3333-333333333333";
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T00:00:00Z");
        ChatSessionEntity session = new ChatSessionEntity(id, "旧标题", createdAt, updatedAt);
        given(sessionRepository.findById(id)).willReturn(Optional.of(session));
        given(sessionRepository.save(any(ChatSessionEntity.class))).willAnswer(i -> i.getArgument(0));
        given(messageRepository.countByConversationId(id)).willReturn(4L);

        var dto = service.rename(id, "  新标题  ");

        assertThat(dto.title()).isEqualTo("新标题");
        // 关键断言：改名不是「对话活动」，updated_at 必须原样保留（FR-004）
        assertThat(dto.updatedAt()).isEqualTo(updatedAt);
        assertThat(dto.messageCount()).isEqualTo(4L);
        assertThat(session.getTitle()).isEqualTo("新标题");
    }

    @Test
    @DisplayName("rename(): 标题为空/全空白被拒绝，且不查库（判定顺序：空 -> 存在性）")
    void renameShouldRejectBlankTitleBeforeQuery() {
        assertThatThrownBy(() -> service.rename("44444444-4444-4444-4444-444444444444", "   "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标题不能为空");
        assertThatThrownBy(() -> service.rename("44444444-4444-4444-4444-444444444444", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标题不能为空");

        verify(sessionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("rename(): 标题超长被拒绝，且不查库")
    void renameShouldRejectTooLongTitle() {
        assertThatThrownBy(() -> service.rename("55555555-5555-5555-5555-555555555555", "x".repeat(201)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("200");

        verify(sessionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("rename(): 长度按去空白后计算 —— 原始 230 字但归一后 190 字应接受")
    void renameShouldMeasureLengthAfterTrim() {
        String id = "66666666-6666-6666-6666-666666666666";
        ChatSessionEntity session = new ChatSessionEntity(id, "旧标题", Instant.now(), Instant.now());
        given(sessionRepository.findById(id)).willReturn(Optional.of(session));
        given(sessionRepository.save(any(ChatSessionEntity.class))).willAnswer(i -> i.getArgument(0));
        given(messageRepository.countByConversationId(id)).willReturn(0L);

        String padded = "  " + "x".repeat(190) + "  ".repeat(19);

        assertThat(service.rename(id, padded).title()).hasSize(190);
    }

    @Test
    @DisplayName("rename(): 会话不存在 → 资源不存在（不是静默创建）")
    void renameShouldFailWhenSessionMissing() {
        String id = "77777777-7777-7777-7777-777777777777";
        given(sessionRepository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename(id, "新标题"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("rename(): 非法会话标识被拒绝，且不查库")
    void renameShouldRejectInvalidIdBeforeQuery() {
        assertThatThrownBy(() -> service.rename("bad id!", "新标题"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会话标识");

        verify(sessionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("create(): 长度按去空白后计算 —— 原始超 200 但归一后合法应接受（与重命名同口径）")
    void createShouldMeasureLengthAfterTrim() {
        given(sessionRepository.save(any(ChatSessionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        String padded = "  " + "x".repeat(190) + "  ".repeat(19);

        assertThat(service.create(padded).title()).hasSize(190);
    }
}
