package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.chat.dto.ChatCitationDto;
import com.personalos.chat.dto.ChatMessageDto;
import com.personalos.chat.entity.ChatSessionEntity;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 单会话 Markdown 导出。
 *
 * <p>固定时区为 Asia/Shanghai：导出的时间戳是给人看的，必须可断言。
 * 用系统默认时区会让同一个断言在 CI（UTC）和本机（+08:00）得出不同结果。
 */
class ChatMarkdownExporterTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CONVERSATION = "c-1";

    private ChatSessionService sessionService;
    private ChatMessageService messageService;
    private ChatMarkdownExporter exporter;

    @BeforeEach
    void setUp() {
        sessionService = mock(ChatSessionService.class);
        messageService = mock(ChatMessageService.class);
        exporter = new ChatMarkdownExporter(sessionService, messageService, ZONE);
    }

    private void givenSession(String title, Instant createdAt) {
        ChatSessionEntity session = mock(ChatSessionEntity.class);
        when(session.getTitle()).thenReturn(title);
        when(session.getCreatedAt()).thenReturn(createdAt);
        when(sessionService.requireSession(CONVERSATION)).thenReturn(session);
    }

    private ChatMessageDto message(String role, String content,
                                   List<ChatCitationDto> citations, List<ChatAttachmentDto> attachments) {
        return new ChatMessageDto(1L, role, content, 1, Instant.parse("2026-10-27T02:00:00Z"),
                citations, attachments);
    }

    @Test
    @DisplayName("标题、时间、每轮问答都在，且角色用「你 / 助手」而不是 USER / ASSISTANT")
    void rendersConversation() {
        givenSession("排课讨论", Instant.parse("2026-10-27T02:00:00Z"));
        when(messageService.list(CONVERSATION)).thenReturn(List.of(
                message("USER", "下周三有空吗", List.of(), List.of()),
                message("ASSISTANT", "周三下午有课。", List.of(), List.of())));

        ChatMarkdownExporter.ExportDocument document = exporter.export(CONVERSATION);
        String markdown = document.markdown();

        assertThat(markdown).startsWith("# 排课讨论");
        // UTC 02:00 → 上海 10:00
        assertThat(markdown).contains("创建于 2026-10-27 10:00");
        assertThat(markdown).contains("### 你").contains("### 助手");
        assertThat(markdown).contains("下周三有空吗").contains("周三下午有课。");
        assertThat(markdown).doesNotContain("USER").doesNotContain("ASSISTANT");
    }

    @Test
    @DisplayName("引用来源带编号与段号，但不复制片段正文（导出物会进笔记，正文会撑大它）")
    void rendersCitationsWithoutChunkBody() {
        givenSession("问答", Instant.parse("2026-10-27T02:00:00Z"));
        ChatCitationDto citation = new ChatCitationDto(1, "doc-1", "排课说明", "schedule.md", 2, "很长的片段正文");
        when(messageService.list(CONVERSATION))
                .thenReturn(List.of(message("ASSISTANT", "见文档。", List.of(citation), List.of())));

        String markdown = exporter.export(CONVERSATION).markdown();

        assertThat(markdown).contains("**引用来源**").contains("1. 《排课说明》（schedule.md，第 3 段）");
        assertThat(markdown).doesNotContain("很长的片段正文");
    }

    @Test
    @DisplayName("附件只写文件名（文件本体不在导出物里，写路径反而误导）")
    void rendersAttachmentNames() {
        givenSession("带附件", Instant.parse("2026-10-27T02:00:00Z"));
        ChatAttachmentDto attachment = new ChatAttachmentDto("a1", "需求.txt", "text/plain", 10);
        when(messageService.list(CONVERSATION))
                .thenReturn(List.of(message("USER", "看下这个", List.of(), List.of(attachment))));

        assertThat(exporter.export(CONVERSATION).markdown()).contains("**附件**：需求.txt");
    }

    @Test
    @DisplayName("空会话给出明确说明，而不是留一个空文件")
    void notesEmptyConversation() {
        givenSession("新会话", Instant.parse("2026-10-27T02:00:00Z"));
        when(messageService.list(CONVERSATION)).thenReturn(List.of());

        assertThat(exporter.export(CONVERSATION).markdown()).contains("这个会话还没有消息");
    }

    @Test
    @DisplayName("文件名去掉路径分隔符：带 / 的标题会让浏览器退回随机文件名")
    void sanitizesFilename() {
        givenSession("Docker/K8s 对比", Instant.parse("2026-10-27T02:00:00Z"));
        when(messageService.list(CONVERSATION)).thenReturn(List.of());

        assertThat(exporter.export(CONVERSATION).filename())
                .isEqualTo("Docker_K8s 对比-20261027-1000.md");
    }

    @Test
    @DisplayName("标题缺失时回落「未命名会话」，不产出以时间开头的怪文件")
    void fallsBackWhenTitleBlank() {
        givenSession("   ", Instant.parse("2026-10-27T02:00:00Z"));
        when(messageService.list(CONVERSATION)).thenReturn(List.of());

        ChatMarkdownExporter.ExportDocument document = exporter.export(CONVERSATION);

        assertThat(document.markdown()).startsWith("# 未命名会话");
        assertThat(document.filename()).startsWith("未命名会话-");
    }

    @Test
    @DisplayName("超长标题截到 60 字：中文按 3 字节算，再长会撞文件名上限")
    void truncatesLongFilename() {
        givenSession("标".repeat(80), Instant.parse("2026-10-27T02:00:00Z"));
        when(messageService.list(CONVERSATION)).thenReturn(List.of());

        String filename = exporter.export(CONVERSATION).filename();

        assertThat(filename).startsWith("标".repeat(60) + "-").endsWith(".md");
        assertThat(filename.length()).isLessThan(80);
    }

    @Test
    @DisplayName("未闭合的代码围栏会被配平：否则后面所有消息都被吞进代码块（审查 P3）")
    void balancesUnclosedFences() {
        givenSession("围栏", Instant.parse("2026-10-27T02:00:00Z"));
        // 第一条消息带一条未闭合的 ``` 围栏，后面还有一条正常消息
        when(messageService.list(CONVERSATION)).thenReturn(List.of(
                message("ASSISTANT", "看这段代码：\n```python\nprint(1)", List.of(), List.of()),
                message("USER", "再问一句", List.of(), List.of())));

        String markdown = exporter.export(CONVERSATION).markdown();

        // 配平后围栏数量是偶数（2 个：开 + 补的闭），第二条消息不在代码块里
        int fences = countFences(markdown);
        assertThat(fences).as("围栏应配平成偶数").isEven();
        assertThat(markdown).contains("再问一句");
    }

    private static int countFences(String markdown) {
        int count = 0;
        int index = 0;
        while ((index = markdown.indexOf("```", index)) >= 0) {
            count++;
            index += 3;
        }
        return count;
    }
}
