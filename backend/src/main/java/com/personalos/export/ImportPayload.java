package com.personalos.export;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 导出文件的解析载体（导入用）。
 *
 * <h2>为什么单独定义一套 record，而不复用导出的 DTO</h2>
 * 导出侧的 DTO 为「展示」服务：带 {@code messageCount}、{@code overdue} 这类派生字段，
 * 它们在导入时没有意义也不能反写。导入只关心「哪些列需要还原」，
 * 两边字段集一旦混用，将来导出加字段就会牵连导入。
 *
 * <p>全部标了 {@code ignoreUnknown}：导出格式只会向后兼容地加字段，
 * 老版本应用读新文件时应当忽略不认识的键，而不是整份拒绝。
 *
 * <p>字段与 {@code V2/V3/V7/V9/V14} 迁移里的列一一对应 —— 这是**唯一**需要跟迁移同步的地方。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportPayload(
        String formatVersion,
        List<SessionPayload> sessions,
        List<TaskPayload> tasks,
        List<CalendarPayload> calendarEvents,
        List<MemoryPayload> memories,
        List<DocumentPayload> knowledgeDocuments) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionPayload(SessionMeta session, List<MessagePayload> messages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionMeta(String conversationId, String title, Instant createdAt, Instant updatedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessagePayload(String role, String content, Integer sequenceNo, Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskPayload(UUID id, String title, String description, String status, String priority,
                              List<String> tags, Instant dueAt, Instant completedAt,
                              Instant createdAt, Instant updatedAt,
                              // 旧版导出文件里没有这一列，Jackson 会给 null —— 与「历史数据」语义一致
                              String previousStatus) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CalendarPayload(UUID id, String title, String description, String location, Boolean allDay,
                                  Instant startsAt, Instant endsAt, String recurrenceType,
                                  Instant recurrenceUntil, Integer reminderLeadMinutes, Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoryPayload(UUID id, String content, String source, Instant createdAt, Instant updatedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentPayload(DocumentMeta document, List<String> chunks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentMeta(UUID id, String title, String filename, String contentType,
                               Long sizeBytes, Integer charCount, Integer chunkCount, Instant createdAt) {
    }
}
