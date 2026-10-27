package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.common.document.PlainTextExtractor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 附件 → 模型输入的转换。
 *
 * <p>这里最容易出的错不是「读不到内容」，而是**读到了却不告诉模型哪里缺**——
 * 那样模型会自信地用不完整的材料作答。所以断言的重点是 notes：读不到的附件
 * 必须在提示词里留下痕迹。
 */
class ChatAttachmentContextBuilderTest {

    private ChatAttachmentService attachmentService;
    private ChatAttachmentContextBuilder builder;

    @BeforeEach
    void setUp() {
        attachmentService = mock(ChatAttachmentService.class);
        builder = new ChatAttachmentContextBuilder(attachmentService, new PlainTextExtractor());
        // 默认：verify 原样返回（校验逻辑另有测试）
        when(attachmentService.verify(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ChatAttachmentDto dto(String id, String filename, String contentType) {
        return new ChatAttachmentDto(id, filename, contentType, 10);
    }

    @Test
    @DisplayName("无附件时返回空上下文（提示词里不该凭空多出一段）")
    void emptyWhenNoAttachments() {
        ChatAttachmentContext context = builder.build(List.of());

        assertThat(context.isEmpty()).isTrue();
        assertThat(context.textBlock()).isEmpty();
    }

    @Test
    @DisplayName("文本附件按文件名分块拼进文本区块")
    void buildsTextBlock() {
        when(attachmentService.verify(any())).thenReturn(List.of(dto("a", "note.txt", "text/plain")));
        when(attachmentService.readBytes(eq("a"))).thenReturn("第一份内容".getBytes(StandardCharsets.UTF_8));

        ChatAttachmentContext context = builder.build(List.of(dto("a", "note.txt", "text/plain")));

        assertThat(context.textBlock())
                .contains("【用户上传的附件】")
                .contains("note.txt")
                .contains("第一份内容");
        assertThat(context.images()).isEmpty();
    }

    @Test
    @DisplayName("读不到的格式必须写明「读不了」，否则模型会拿残缺材料自信作答")
    void notesUnreadableFormats() {
        when(attachmentService.verify(any())).thenReturn(List.of(dto("z", "data.zip", "application/zip")));

        ChatAttachmentContext context = builder.build(List.of(dto("z", "data.zip", "application/zip")));

        assertThat(context.textBlock()).contains("data.zip").contains("格式不支持");
        assertThat(context.isEmpty()).as("只有说明没有内容，但对模型仍有意义").isFalse();
    }

    @Test
    @DisplayName("图片走多模态：生成 Media，不落到文本区块")
    void imagesBecomeMedia() {
        when(attachmentService.verify(any()))
                .thenReturn(List.of(dto("img", "shot.png", "image/png")));
        when(attachmentService.readBytes(anyString())).thenReturn(new byte[] {1, 2, 3});

        ChatAttachmentContext context = builder.build(List.of(dto("img", "shot.png", "image/png")));

        assertThat(context.images()).hasSize(1);
        assertThat(context.textBlock()).doesNotContain("shot.png");
    }

    @Test
    @DisplayName("超过 4 张图时后续进说明，而不是静默丢弃")
    void capsImageCount() {
        List<ChatAttachmentDto> many = List.of(
                dto("1", "a.png", "image/png"), dto("2", "b.png", "image/png"),
                dto("3", "c.png", "image/png"), dto("4", "d.png", "image/png"),
                dto("5", "e.png", "image/png"));
        when(attachmentService.verify(any())).thenReturn(many);
        when(attachmentService.readBytes(anyString())).thenReturn(new byte[] {1});

        ChatAttachmentContext context = builder.build(many);

        assertThat(context.images()).hasSize(4);
        assertThat(context.textBlock()).contains("e.png").contains("上限");
    }

    @Test
    @DisplayName("读取抛异常时降级为说明，不让整轮对话失败")
    void toleratesReadFailure() {
        when(attachmentService.verify(any())).thenReturn(List.of(dto("x", "broken.txt", "text/plain")));
        when(attachmentService.readBytes(anyString())).thenThrow(new IllegalStateException("磁盘错误"));

        ChatAttachmentContext context = builder.build(List.of(dto("x", "broken.txt", "text/plain")));

        assertThat(context.textBlock()).contains("broken.txt").contains("读取失败");
    }
}
