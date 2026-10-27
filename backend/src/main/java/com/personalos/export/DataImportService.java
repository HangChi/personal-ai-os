package com.personalos.export;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据导入：把导出的 JSON 写回一个**空库**。
 *
 * <h2>为什么只支持空库</h2>
 * 「目标库已有数据时怎么办」才是导入的真难点：按 id 覆盖？按内容合并？冲突谁优先？
 * 这些策略做错就是**静默数据损坏** —— 用户以为恢复了备份，实际得到两套半残数据。
 * 与其猜，不如明确拒绝：
 * <pre>
 *   目标库已有数据（sessions=13）。导入只支持空库 ——
 *   请先切换到空数据库，或清空后重试。
 * </pre>
 *
 * <h2>为什么用原生 SQL 而不是走各个 Service</h2>
 * 导入要还原的是**列**，不是业务动作：{@code created_at} 必须是当初的时间，
 * id 必须是当初的 id（引用快照里存着 document_id）。而各 Service 的
 * {@code create()} 全都由服务端生成 id 与时间 —— 那正是备份恢复最不能丢的两样东西。
 *
 * <p>代价是这里的 SQL 与迁移脚本耦合：**改表结构时要同步改这里**。
 * 集中在一个类里，至少只需要看一个地方。
 *
 * <h2>不还原的东西</h2>
 * <ul>
 *   <li><b>消息 id</b>：自增主键，重新生成即可；顺序由 {@code sequence_no} 保证。</li>
 *   <li><b>引用快照</b>：派生展示数据（哪条回答引用了哪段），缺了不影响任何功能。</li>
 *   <li><b>会话摘要与边界</b>：会在下一轮对话后自动重建。</li>
 *   <li><b>提醒</b>：由扫描器按日程/任务重新物化，本来就是派生数据。</li>
 *   <li><b>附件文件</b>：导出不含二进制，元数据里的附件卡片会取不到内容。</li>
 * </ul>
 */
@Service
public class DataImportService {

    private static final Logger log = LoggerFactory.getLogger(DataImportService.class);

    /** 当前支持的导出格式版本，与 {@link DataExportService#FORMAT_VERSION} 对应 */
    private static final String SUPPORTED_FORMAT = "1";

    /** 消息角色的白名单：脏数据不该被写进库 */
    private static final Set<String> ROLES = Set.of("USER", "ASSISTANT");

    private static final Set<String> TASK_STATUS = Set.of("TODO", "DOING", "DONE");
    private static final Set<String> TASK_PRIORITY = Set.of("LOW", "MEDIUM", "HIGH");
    /**
     * 「完成前状态」的白名单：只有这两个值有意义。
     *
     * <p>刻意不含 {@code DONE} —— 完成前状态是 DONE 属于自相矛盾的数据，
     * 放进来会让 reopen 把任务退回已完成，用户点「取消完成」后毫无变化。
     */
    private static final Set<String> TASK_PREVIOUS_STATUS = Set.of("TODO", "DOING");
    private static final Set<String> RECURRENCE = Set.of("NONE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY");

    /** 空库校验覆盖的表；顺序只影响报错文案 */
    private static final List<String> GUARDED_TABLES = List.of(
            "chat_session", "chat_message", "task", "task_tag", "calendar_event",
            "memory", "knowledge_document", "confirmation", "operation_record");

    private final EntityManager entityManager;
    private final VectorStore vectorStore;
    private final JsonCodec jsonCodec;

    public DataImportService(EntityManager entityManager, VectorStore vectorStore, JsonCodec jsonCodec) {
        this.entityManager = entityManager;
        this.vectorStore = vectorStore;
        this.jsonCodec = jsonCodec;
    }

    /** 导入结果（给前端展示「到底进来了多少」） */
    public record ImportResult(int sessions, int messages, int tasks, int calendarEvents,
                               int memories, int documents, int vectors, List<String> notes) {
    }

    /**
     * 解析并导入。
     *
     * @param rawJson 导出文件的原始 JSON
     */
    @Transactional
    public ImportResult importAll(String rawJson) {
        ImportPayload payload;
        try {
            payload = jsonCodec.fromJson(rawJson, ImportPayload.class);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件不是合法的导出 JSON：" + ex.getMessage());
        }
        requireSupportedFormat(payload.formatVersion());
        requireEmptyDatabase();

        int messages = importSessions(payload.sessions());
        int tasks = importTasks(payload.tasks());
        int events = importCalendar(payload.calendarEvents());
        int memories = importMemories(payload.memories());
        DocStats docs = importDocuments(payload.knowledgeDocuments());

        ImportResult result = new ImportResult(
                sizeOf(payload.sessions()), messages, tasks, events, memories,
                docs.documents(), docs.vectors(), docs.notes());
        log.info("数据导入完成 sessions={} messages={} tasks={} events={} memories={} documents={} vectors={}",
                result.sessions(), result.messages(), result.tasks(), result.calendarEvents(),
                result.memories(), result.documents(), result.vectors());
        return result;
    }

    // ------------------------------------------------------------------ 校验

    private void requireSupportedFormat(String formatVersion) {
        if (formatVersion == null || !SUPPORTED_FORMAT.equals(formatVersion.trim())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, String.format(
                    "不支持的导出格式版本「%s」，当前只能导入 formatVersion=%s 的文件",
                    formatVersion, SUPPORTED_FORMAT));
        }
    }

    /**
     * 目标库必须完全为空。
     *
     * <p>只检查「有数据的表」而不是「必须等于 0 行」的全部表：漏检查一张表就等于
     * 允许一次静默污染，所以这里把会与导入冲突的表全部列进白名单常量，
     * 而不是凭印象挑几张。
     */
    private void requireEmptyDatabase() {
        Map<String, Long> occupied = new LinkedHashMap<>();
        for (String table : GUARDED_TABLES) {
            Long count = countOf(table);
            if (count != null && count > 0) {
                occupied.put(table, count);
            }
        }
        if (!occupied.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, String.format(
                    "目标库已有数据（%s），导入只支持空库。请先切换到空数据库，或清空后重试。",
                    occupied.entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("")));
        }
    }

    private Long countOf(String table) {
        Object raw = entityManager.createNativeQuery("select count(*) from " + table).getSingleResult();
        return raw instanceof Number number ? number.longValue() : null;
    }

    // ------------------------------------------------------------------ 各模块

    private int importSessions(List<ImportPayload.SessionPayload> sessions) {
        if (sessions == null) {
            return 0;
        }
        int messages = 0;
        for (ImportPayload.SessionPayload entry : sessions) {
            ImportPayload.SessionMeta session = entry.session();
            if (session == null || isBlank(session.conversationId())) {
                continue;
            }
            entityManager.createNativeQuery(
                            "insert into chat_session (conversation_id, title, created_at, updated_at) "
                                    + "values (?1, ?2, ?3, ?4)")
                    .setParameter(1, session.conversationId())
                    .setParameter(2, isBlank(session.title()) ? "导入的会话" : session.title())
                    .setParameter(3, ts(session.createdAt()))
                    .setParameter(4, ts(session.updatedAt() == null ? session.createdAt() : session.updatedAt()))
                    .executeUpdate();
            messages += importMessages(session.conversationId(), entry.messages());
        }
        return messages;
    }

    private int importMessages(String conversationId, List<ImportPayload.MessagePayload> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int imported = 0;
        int sequence = 1;
        for (ImportPayload.MessagePayload message : messages) {
            String role = message.role() == null ? "" : message.role().toUpperCase(Locale.ROOT);
            if (!ROLES.contains(role) || message.content() == null) {
                continue;
            }
            // 顺序号优先用原值；缺失或非法则按出现顺序补，保证对话可读
            int sequenceNo = message.sequenceNo() == null || message.sequenceNo() <= 0
                    ? sequence
                    : message.sequenceNo();
            // 时间缺失时用当前时间兜底：库里的 created_at 是 NOT NULL，
            // 而「导入时刻」至少比「导入失败」对用户更有用
            Instant createdAt = message.createdAt() == null ? Instant.now() : message.createdAt();
            entityManager.createNativeQuery(
                            "insert into chat_message (conversation_id, message_type, content, sequence_no, created_at) "
                                    + "values (?1, ?2, ?3, ?4, ?5)")
                    .setParameter(1, conversationId)
                    .setParameter(2, role)
                    .setParameter(3, message.content())
                    .setParameter(4, sequenceNo)
                    .setParameter(5, ts(createdAt))
                    .executeUpdate();
            sequence = sequenceNo + 1;
            imported++;
        }
        return imported;
    }

    private int importTasks(List<ImportPayload.TaskPayload> tasks) {
        if (tasks == null) {
            return 0;
        }
        int imported = 0;
        for (ImportPayload.TaskPayload task : tasks) {
            if (task.id() == null || isBlank(task.title())) {
                continue;
            }
            String status = normalize(task.status(), TASK_STATUS, "TODO");
            String priority = normalize(task.priority(), TASK_PRIORITY, "MEDIUM");
            // previous_status 只在 status=DONE 时才有意义；非 DONE 一律写 null，
            // 否则会出现「待办任务带着一个完成前状态」这种自相矛盾的数据
            String previousStatus = "DONE".equals(status)
                    ? normalize(task.previousStatus(), TASK_PREVIOUS_STATUS, null) : null;
            Instant createdAt = task.createdAt() == null ? Instant.now() : task.createdAt();
            entityManager.createNativeQuery(
                            "insert into task (id, title, description, status, priority, due_at, completed_at, "
                                    + "created_at, updated_at, previous_status) "
                                    + "values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)")
                    .setParameter(1, task.id())
                    .setParameter(2, task.title())
                    .setParameter(3, task.description())
                    .setParameter(4, status)
                    .setParameter(5, priority)
                    .setParameter(6, ts(task.dueAt()))
                    .setParameter(7, ts(task.completedAt()))
                    .setParameter(8, ts(createdAt))
                    .setParameter(9, ts(task.updatedAt() == null ? createdAt : task.updatedAt()))
                    .setParameter(10, previousStatus)
                    .executeUpdate();
            importTags(task.id(), task.tags());
            imported++;
        }
        return imported;
    }

    private void importTags(java.util.UUID taskId, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        int index = 0;
        for (String tag : tags) {
            if (isBlank(tag)) {
                continue;
            }
            entityManager.createNativeQuery(
                            "insert into task_tag (task_id, tag_index, tag) values (?1, ?2, ?3)")
                    .setParameter(1, taskId)
                    .setParameter(2, index++)
                    .setParameter(3, tag)
                    .executeUpdate();
        }
    }

    private int importCalendar(List<ImportPayload.CalendarPayload> events) {
        if (events == null) {
            return 0;
        }
        int imported = 0;
        for (ImportPayload.CalendarPayload event : events) {
            if (event.id() == null || isBlank(event.title()) || event.startsAt() == null) {
                continue;
            }
            Instant createdAt = event.createdAt() == null ? Instant.now() : event.createdAt();
            entityManager.createNativeQuery(
                            "insert into calendar_event (id, title, description, location, all_day, starts_at, "
                                    + "ends_at, recurrence_type, recurrence_until, reminder_lead_minutes, "
                                    + "created_at, updated_at) "
                                    + "values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)")
                    .setParameter(1, event.id())
                    .setParameter(2, event.title())
                    .setParameter(3, event.description())
                    .setParameter(4, event.location())
                    .setParameter(5, Boolean.TRUE.equals(event.allDay()))
                    .setParameter(6, ts(event.startsAt()))
                    .setParameter(7, ts(event.endsAt() == null ? event.startsAt() : event.endsAt()))
                    .setParameter(8, normalize(event.recurrenceType(), RECURRENCE, "NONE"))
                    .setParameter(9, ts(event.recurrenceUntil()))
                    .setParameter(10, event.reminderLeadMinutes() == null ? 15 : event.reminderLeadMinutes())
                    .setParameter(11, ts(createdAt))
                    .setParameter(12, ts(createdAt))
                    .executeUpdate();
            imported++;
        }
        return imported;
    }

    private int importMemories(List<ImportPayload.MemoryPayload> memories) {
        if (memories == null) {
            return 0;
        }
        int imported = 0;
        for (ImportPayload.MemoryPayload memory : memories) {
            if (memory.id() == null || isBlank(memory.content())) {
                continue;
            }
            Instant createdAt = memory.createdAt() == null ? Instant.now() : memory.createdAt();
            entityManager.createNativeQuery(
                            "insert into memory (id, content, content_hash, created_at, updated_at, source) "
                                    + "values (?1, ?2, ?3, ?4, ?5, ?6)")
                    .setParameter(1, memory.id())
                    .setParameter(2, memory.content())
                    // V17 起 content_hash 是 NOT NULL 唯一键；导入时与写入路径同口径重算
                    .setParameter(3, JsonCodec.sha256Hex(memory.content()))
                    .setParameter(4, ts(createdAt))
                    .setParameter(5, ts(memory.updatedAt() == null ? createdAt : memory.updatedAt()))
                    .setParameter(6, isBlank(memory.source()) ? "MANUAL" : memory.source())
                    .executeUpdate();
            imported++;
        }
        return imported;
    }

    /** 知识库导入结果：文档数、重建的向量数，以及需要告诉用户的注意事项 */
    private record DocStats(int documents, int vectors, List<String> notes) {
    }

    private DocStats importDocuments(List<ImportPayload.DocumentPayload> documents) {
        if (documents == null || documents.isEmpty()) {
            return new DocStats(0, 0, List.of());
        }
        int imported = 0;
        int vectors = 0;
        List<String> notes = new ArrayList<>();
        for (ImportPayload.DocumentPayload entry : documents) {
            ImportPayload.DocumentMeta meta = entry.document();
            List<String> chunks = entry.chunks();
            if (meta == null || meta.id() == null || chunks == null || chunks.isEmpty()) {
                continue;
            }
            Instant createdAt = meta.createdAt() == null ? Instant.now() : meta.createdAt();
            int charCount = meta.charCount() == null ? chunks.stream().mapToInt(String::length).sum() : meta.charCount();
            entityManager.createNativeQuery(
                            "insert into knowledge_document (id, title, filename, content_type, size_bytes, "
                                    + "char_count, chunk_count, created_at, updated_at) "
                                    + "values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)")
                    .setParameter(1, meta.id())
                    .setParameter(2, isBlank(meta.title()) ? meta.filename() : meta.title())
                    .setParameter(3, isBlank(meta.filename()) ? "imported.md" : meta.filename())
                    .setParameter(4, isBlank(meta.contentType()) ? "text/markdown" : meta.contentType())
                    .setParameter(5, meta.sizeBytes() == null ? 0L : meta.sizeBytes())
                    .setParameter(6, charCount)
                    .setParameter(7, chunks.size())
                    .setParameter(8, ts(createdAt))
                    .setParameter(9, ts(createdAt))
                    .executeUpdate();

            // 向量是「分块文本 + 嵌入模型」的派生物，导出时不带，只能重建。
            // 元数据的键必须与 KnowledgeDocumentService 写入时完全一致，
            // 否则检索出来的引用会缺字段（document_title / filename / chunk_index）。
            List<Document> documentsToIndex = new ArrayList<>(chunks.size());
            int index = 0;
            for (String chunk : chunks) {
                if (isBlank(chunk)) {
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("document_id", meta.id().toString());
                metadata.put("document_title", isBlank(meta.title()) ? meta.filename() : meta.title());
                metadata.put("filename", isBlank(meta.filename()) ? "imported.md" : meta.filename());
                metadata.put("chunk_index", index++);
                documentsToIndex.add(new Document(chunk, metadata));
            }
            if (!documentsToIndex.isEmpty()) {
                vectorStore.add(documentsToIndex);
                vectors += documentsToIndex.size();
            }
            imported++;
        }
        if (imported > 0) {
            notes.add("知识库已按分块文本重建向量；原始 PDF/Word/图片不在导出文件里，需要时请重新上传。");
        }
        return new DocStats(imported, vectors, notes);
    }

    // ------------------------------------------------------------------ 工具

    /** 原生 SQL 绑定时间必须用 OffsetDateTime —— 直接绑 Instant 不报错但会平移时区 */
    private OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String normalize(String value, Set<String> allowed, String fallback) {
        if (value == null) {
            return fallback;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(upper) ? upper : fallback;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
