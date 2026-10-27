package com.personalos.chat.service;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.chat.dto.ChatCitationDto;
import com.personalos.chat.dto.ChatMessageDto;
import com.personalos.chat.entity.ChatSessionEntity;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把单个会话渲染成 Markdown。
 *
 * <h2>为什么是 Markdown 而不是又一个 JSON</h2>
 * 全量导出（{@code /export}）解决的是「迁移与恢复」，那份 JSON 是给机器读的。
 * 单会话导出解决的是「把这段对话发出去、存进笔记、贴进文档」——
 * 这类用途下 JSON 完全不可读，而 Markdown 粘到哪里都能看。
 * 两者的受众不同，所以格式也不同，不复用同一套渲染。
 *
 * <h2>保留什么、省略什么</h2>
 * 保留：标题、时间、每轮问答、知识库引用来源、附件文件名。
 * 省略：工具调用轨迹（过程信息，导出后没有上下文）、引用片段的正文
 * （导出物会进入笔记/邮件，把整段原文带上会让文件膨胀数倍）。
 */
@Service
public class ChatMarkdownExporter {

    /** 文件名里的时间戳用本地时区：用户看的是「几号几点」，不是 UTC */
    private static final DateTimeFormatter FILENAME_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private static final DateTimeFormatter HUMAN_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 标题里不能进文件名的字符，统一换成下划线 */
    private static final String ILLEGAL_FILENAME_CHARS = "[\\\\/:*?\"<>|\\r\\n]";

    private final ChatSessionService sessionService;
    private final ChatMessageService messageService;
    private final ZoneId zoneId;

    public ChatMarkdownExporter(ChatSessionService sessionService,
                                ChatMessageService messageService,
                                ZoneId zoneId) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.zoneId = zoneId;
    }

    /**
     * 导出结果。
     *
     * @param filename 建议的下载文件名（已去除非法字符）
     * @param markdown 正文
     */
    public record ExportDocument(String filename, String markdown) {
    }

    @Transactional(readOnly = true)
    public ExportDocument export(String conversationId) {
        ChatSessionEntity session = sessionService.requireSession(conversationId);
        List<ChatMessageDto> messages = messageService.list(conversationId);

        String title = session.getTitle() == null || session.getTitle().isBlank()
                ? "未命名会话"
                : session.getTitle();

        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(title).append("\n\n");
        markdown.append("> 创建于 ").append(HUMAN_TIME.format(session.getCreatedAt().atZone(zoneId)))
                .append(" · 共 ").append(messages.size()).append(" 条消息\n")
                .append("> 导出自 Personal AI OS\n\n---\n\n");

        for (ChatMessageDto message : messages) {
            appendMessage(markdown, message);
        }

        if (messages.isEmpty()) {
            markdown.append("_（这个会话还没有消息）_\n");
        }

        return new ExportDocument(buildFilename(title, session.getCreatedAt()), markdown.toString());
    }

    private void appendMessage(StringBuilder markdown, ChatMessageDto message) {
        boolean user = "USER".equalsIgnoreCase(message.role());
        markdown.append("### ").append(user ? "你" : "助手").append("\n\n");
        // 正文原样保留：它本身就是 Markdown（对话里的代码块、列表都要活下来），
        // 再做一次转义反而会毁掉格式
        markdown.append(message.content() == null ? "" : message.content().strip()).append("\n\n");

        appendAttachments(markdown, message.attachments());
        appendCitations(markdown, message.citations());
        markdown.append("---\n\n");
    }

    private void appendAttachments(StringBuilder markdown, List<ChatAttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        markdown.append("**附件**：")
                .append(attachments.stream()
                        .map(ChatAttachmentDto::filename)
                        .reduce((left, right) -> left + "、" + right)
                        .orElse(""))
                .append("\n\n");
    }

    private void appendCitations(StringBuilder markdown, List<ChatCitationDto> citations) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        markdown.append("**引用来源**\n\n");
        for (ChatCitationDto citation : citations) {
            markdown.append(citation.index()).append(". 《")
                    .append(nullToPlaceholder(citation.documentTitle())).append("》（")
                    .append(nullToPlaceholder(citation.filename())).append("，第 ")
                    .append(citation.chunkIndex() + 1).append(" 段）\n");
        }
        markdown.append('\n');
    }

    private String nullToPlaceholder(String value) {
        return value == null || value.isBlank() ? "未命名" : value;
    }

    /**
     * 文件名：{标题}-{创建时间}.md。
     *
     * <p>去掉路径分隔符等非法字符：导出物多半会被保存到「下载」目录，
     * 带 {@code /} 的标题在部分系统上会让浏览器退回一个随机文件名。
     * 长度限 60：多数文件系统的文件名上限是 255 字节，中文按 3 字节算，60 字仍安全。
     */
    private String buildFilename(String title, Instant createdAt) {
        String safe = title.replaceAll(ILLEGAL_FILENAME_CHARS, "_").strip();
        if (safe.length() > 60) {
            safe = safe.substring(0, 60);
        }
        String stamp = FILENAME_STAMP.format(createdAt.atZone(zoneId));
        return String.format(Locale.ROOT, "%s-%s.md", safe, stamp);
    }
}
