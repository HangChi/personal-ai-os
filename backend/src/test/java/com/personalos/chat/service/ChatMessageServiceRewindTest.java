package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.repository.ChatMessageRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatMessageService#rewind} 的附件清理契约（审查 P3）。
 *
 * <p>rewind 删「某序号及之后」的消息，被删消息的附件文件也要一并清理 ——
 * 只删记录不删文件，磁盘上会永久残留孤儿文件（此前只能靠每天一次的 janitor 兜底）。
 * 这里锁住两件事：附件快照**在删除前**取、且按「fromSeq 起」的范围取（不误删前面的）。
 */
class ChatMessageServiceRewindTest {

    private static final String CONVERSATION = "c-rewind";

    private ChatMessageRepository messageRepository;
    private ChatSessionService sessionService;
    private ChatCitationService citationService;
    private ChatAttachmentService attachmentService;
    private ChatMessageService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(ChatMessageRepository.class);
        sessionService = mock(ChatSessionService.class);
        citationService = mock(ChatCitationService.class);
        attachmentService = mock(ChatAttachmentService.class);
        service = new ChatMessageService(messageRepository, sessionService, citationService, attachmentService);

        ChatSessionEntity session = mock(ChatSessionEntity.class);
        when(sessionService.requireSession(CONVERSATION)).thenReturn(session);
    }

    @Test
    @DisplayName("rewind 按「fromSeq 起」的范围取附件快照，而不是取整个会话")
    void rewindsWithAttachmentsFromSequence() {
        when(messageRepository.findAttachmentJsonFromSequence(CONVERSATION, 5)).thenReturn(List.of());
        when(messageRepository.deleteFromSequence(CONVERSATION, 5)).thenReturn(2);

        int deleted = service.rewind(CONVERSATION, 5);

        assertThat(deleted).isEqualTo(2);
        // 关键：用的是范围查询，不是 findAttachmentJsonByConversationId（那会误删前面的附件）
        verify(messageRepository).findAttachmentJsonFromSequence(CONVERSATION, 5);
        verify(messageRepository, never()).findAttachmentJsonByConversationId(CONVERSATION);
    }

    @Test
    @DisplayName("被删消息的附件文件在删除后清理")
    void rewindsDeletesAttachmentFiles() {
        ChatAttachmentDto attachment = new ChatAttachmentDto("att-1", "notes.txt", "text/plain", 12L);
        when(messageRepository.findAttachmentJsonFromSequence(CONVERSATION, 3))
                .thenReturn(List.of("[{\"id\":\"att-1\",\"filename\":\"notes.txt\",\"contentType\":\"text/plain\",\"size\":12}]"));
        when(attachmentService.fromJson(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(attachment));
        when(messageRepository.deleteFromSequence(CONVERSATION, 3)).thenReturn(1);

        service.rewind(CONVERSATION, 3);

        // 无活跃事务时直接删文件（单测里没有事务同步，走的是直接删除分支）
        verify(attachmentService).deleteAll(List.of(attachment));
    }

    @Test
    @DisplayName("没有附件时不做任何文件删除")
    void rewindsWithoutAttachmentsSkipsFileDeletion() {
        when(messageRepository.findAttachmentJsonFromSequence(CONVERSATION, 2)).thenReturn(List.of());
        when(messageRepository.deleteFromSequence(CONVERSATION, 2)).thenReturn(1);

        service.rewind(CONVERSATION, 2);

        verify(attachmentService, never()).deleteAll(anyList());
    }

    @Test
    @DisplayName("坏附件快照跳过，不拖垮整次回退")
    void rewindsToleratesMalformedAttachmentJson() {
        when(messageRepository.findAttachmentJsonFromSequence(CONVERSATION, 4))
                .thenReturn(List.of("not-valid-json"));
        when(attachmentService.fromJson("not-valid-json")).thenReturn(List.of());
        when(messageRepository.deleteFromSequence(CONVERSATION, 4)).thenReturn(2);

        int deleted = service.rewind(CONVERSATION, 4);

        assertThat(deleted).isEqualTo(2);
        verify(attachmentService, never()).deleteAll(anyList());
    }
}
