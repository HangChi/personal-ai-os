package com.personalos.mcp.server;

import com.personalos.calendar.dto.CalendarOccurrenceDto;
import com.personalos.calendar.service.CalendarEventService;
import com.personalos.common.api.PageDto;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import com.personalos.memory.dto.MemoryDto;
import com.personalos.memory.service.MemoryService;
import com.personalos.task.dto.TaskDto;
import com.personalos.task.dto.TaskQuery;
import com.personalos.task.entity.TaskStatus;
import com.personalos.task.service.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Read-only MCP tools backed by Personal AI OS domain services. */
@Component
public class PersonalMcpTools {

    private final TaskService taskService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final CalendarEventService calendarEventService;
    private final MemoryService memoryService;

    public PersonalMcpTools(TaskService taskService,
                            KnowledgeRetrievalService knowledgeRetrievalService,
                            CalendarEventService calendarEventService,
                            MemoryService memoryService) {
        this.taskService = taskService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.calendarEventService = calendarEventService;
        this.memoryService = memoryService;
    }

    @McpTool(name = "personal_list_tasks",
            description = "查询个人任务，可按状态和关键词筛选，并返回分页结果。")
    public PageDto<TaskDto> listTasks(
            @McpToolParam(description = "状态：TODO、DOING 或 DONE；留空表示全部", required = false)
            String status,
            @McpToolParam(description = "匹配标题或描述的关键词；留空表示全部", required = false)
            String keyword,
            @McpToolParam(description = "从 0 开始的页码", required = false)
            Integer page,
            @McpToolParam(description = "每页数量，最大 100", required = false)
            Integer size) {
        TaskStatus parsedStatus = parseStatus(status);
        return taskService.search(new TaskQuery(parsedStatus, keyword, null,
                page == null ? 0 : page,
                size == null ? TaskQuery.DEFAULT_SIZE : size));
    }

    @McpTool(name = "personal_search_knowledge",
            description = "在个人知识库中进行混合检索，返回相关文档片段及来源。")
    public List<KnowledgeHitDto> searchKnowledge(
            @McpToolParam(description = "要检索的问题或关键词") String query,
            @McpToolParam(description = "返回条数，留空使用系统默认值", required = false)
            Integer topK) {
        return knowledgeRetrievalService.search(query, topK);
    }

    @McpTool(name = "personal_list_calendar",
            description = "查询个人日程发生实例，时间使用 ISO-8601 格式。")
    public PageDto<CalendarOccurrenceDto> listCalendar(
            @McpToolParam(description = "区间起点，例如 2026-11-01T00:00:00Z；留空表示今天开始", required = false)
            String from,
            @McpToolParam(description = "区间终点；留空表示起点后 7 天", required = false)
            String to,
            @McpToolParam(description = "从 0 开始的页码", required = false)
            Integer page,
            @McpToolParam(description = "每页数量", required = false)
            Integer size) {
        return calendarEventService.search(parseInstant(from), parseInstant(to), page, size);
    }

    @McpTool(name = "personal_list_memories",
            description = "读取个人长期记忆，包括内容、来源、置顶状态和更新时间。")
    public List<MemoryDto> listMemories() {
        return memoryService.list();
    }

    private static TaskStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("status 必须是 TODO、DOING 或 DONE");
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("时间必须是 ISO-8601 格式，例如 2026-11-01T00:00:00Z");
        }
    }
}
