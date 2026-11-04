package com.personalos.migration;

import com.personalos.common.search.*;
import com.personalos.export.ImportPayload;
import com.personalos.mcp.confirmation.McpConfirmationSummary;
import com.personalos.mcp.confirmation.McpToolCall;
import com.personalos.mcp.server.PersonalMcpTools;
import com.personalos.mcp.tool.McpToolNaming;
import com.personalos.mcp.tool.McpToolPolicy;
import java.util.*;
import org.springframework.ai.mcp.annotation.McpTool;
import tools.jackson.databind.json.JsonMapper;

/** Executes immutable Java business contracts; no servers, databases, or network. */
public final class P7Oracle {
    private static String text(String value) { return value == null ? "" : value; }

    private static SearchContributor contributor(String module, boolean fail, boolean empty) {
        return new SearchContributor() {
            public String moduleName() { return module; }
            public String kind() { return "fixture"; }
            public List<SearchHit> search(String keyword, int limit) {
                if (fail) throw new IllegalStateException("synthetic module failure");
                return empty ? List.of() : List.of(new SearchHit("fixture", keyword, "limit:" + limit, "", ""));
            }
        };
    }

    public static void main(String[] args) throws Exception {
        var json = JsonMapper.builder().build();
        var input = json.readTree(System.in);
        var output = new LinkedHashMap<String, Object>();
        var names = new ArrayList<String>();
        for (var row : input.get("names")) names.add(McpToolNaming.prefixedName(row.get(0).asText(), row.get(1).asText()));
        output.put("names", names);
        var policies = new ArrayList<String>();
        for (var row : input.get("policies")) policies.add(McpToolPolicy.resolve(row.get("name").asText(), new HashSet<>(json.convertValue(row.get("automatic"), List.class))).name());
        output.put("policies", policies);
        output.put("conflicts", McpToolNaming.conflicts(Set.of("mcp_a_t"), List.of("mcp_a_t", "mcp_a_t", "mcp_b_t", "mcp_b_t")));
        var summaries = new ArrayList<String>();
        for (var row : input.get("summaries")) summaries.add(McpConfirmationSummary.summarize(json.treeToValue(row, McpToolCall.class)));
        output.put("summaries", summaries);
        var patterns = new ArrayList<String>();
        for (var row : input.get("patterns")) patterns.add(SearchPatterns.likePattern(row.asText()));
        output.put("patterns", patterns);
        var controller = new SearchController(new GlobalSearchService(List.of(
                contributor("记忆", false, false), contributor("任务", false, false),
                contributor("日程", true, false), contributor("空", false, true), contributor("对话", false, false))));
        var searches = new ArrayList<Object>();
        for (var row : input.get("searches")) {
            try {
                searches.add(Map.of("accepted", true, "result", controller.search(row.get("keyword").asText(), row.get("limit").asInt()).data()));
            } catch (RuntimeException ex) {
                searches.add(Map.of("accepted", false, "message", ex.getMessage()));
            }
        }
        output.put("searches", searches);
        var tools = new ArrayList<Object>();
        Arrays.stream(PersonalMcpTools.class.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(McpTool.class))
                .sorted(Comparator.comparing(m -> m.getAnnotation(McpTool.class).name())).forEach(m -> {
                    var annotation = m.getAnnotation(McpTool.class);
                    tools.add(Map.of("name", annotation.name(), "description", annotation.description(), "readOnly", annotation.annotations().readOnlyHint()));
                });
        output.put("personalTools", tools);
        var imports = new ArrayList<Object>();
        for (var row : input.get("imports")) {
            try {
                var payload = json.readValue(row.asText(), ImportPayload.class);
                if (payload == null || payload.formatVersion() == null || !payload.formatVersion().trim().equals("1")) throw new IllegalArgumentException("format");
                var result = new LinkedHashMap<String, Object>();
                result.put("accepted", true);
                var tasks = new ArrayList<Object>();
                if (payload.tasks() != null) for (var task : payload.tasks()) {
                    if (task == null) { tasks.add(null); continue; }
                    var item = new LinkedHashMap<String, Object>();
                    item.put("id", task.id()); item.put("title", text(task.title())); item.put("description", task.description());
                    item.put("status", text(task.status())); item.put("priority", text(task.priority()));
                    item.put("tags", task.tags() == null ? List.of() : task.tags().stream().map(P7Oracle::text).toList());
                    tasks.add(item);
                }
                result.put("tasks", tasks);
                result.put("allDay", payload.calendarEvents() == null || payload.calendarEvents().isEmpty() ? false : Boolean.TRUE.equals(payload.calendarEvents().getFirst().allDay()));
                result.put("lead", payload.calendarEvents() == null || payload.calendarEvents().isEmpty() ? null : payload.calendarEvents().getFirst().reminderLeadMinutes());
                result.put("pinned", payload.memories() == null || payload.memories().isEmpty() ? false : Boolean.TRUE.equals(payload.memories().getFirst().pinned()));
                result.put("hits", payload.memories() == null || payload.memories().isEmpty() || payload.memories().getFirst().hitCount() == null ? 0 : payload.memories().getFirst().hitCount());
                result.put("size", payload.knowledgeDocuments() == null || payload.knowledgeDocuments().isEmpty() || payload.knowledgeDocuments().getFirst().document() == null || payload.knowledgeDocuments().getFirst().document().sizeBytes() == null ? 0 : payload.knowledgeDocuments().getFirst().document().sizeBytes());
                imports.add(result);
            } catch (RuntimeException ex) {
                imports.add(Map.of("accepted", false));
            }
        }
        output.put("imports", imports);
        System.out.println("ORACLE " + json.writeValueAsString(output));
    }
}
