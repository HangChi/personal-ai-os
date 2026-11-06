package com.personalos.migration;

import com.personalos.agent.tool.TaskTools;
import com.personalos.agent.plan.tool.AgentPlanTools;
import com.personalos.agent.interaction.tool.AgentUserTools;
import com.personalos.calendar.tool.CalendarTools;
import com.personalos.memory.tool.MemoryTools;
import com.personalos.knowledge.tool.KnowledgeTools;
import com.personalos.common.ai.PromptTokenCounter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.support.ToolCallbacks;
import tools.jackson.databind.json.JsonMapper;

public final class ContextSchemaOracle {
    public static void main(String[] args) throws Exception {
        var json = JsonMapper.builder().build();
        var rows = new ArrayList<Map<String, Object>>();
        for (var callback : ToolCallbacks.from(new TaskTools(null, null, null),
                new AgentPlanTools(null, null), new AgentUserTools(null, null),
                new CalendarTools(null, null, null, null), new MemoryTools(null, null),
                new KnowledgeTools(null, null))) {
            var definition = callback.getToolDefinition();
            var row = new LinkedHashMap<String, Object>();
            row.put("name", definition.name());
            row.put("schema", definition.inputSchema());
            row.put("raw_tokens", PromptTokenCounter.text(definition.inputSchema(), "mock-model"));
            rows.add(row);
        }
        rows.sort(Comparator.comparing(row -> (String) row.get("name")));
        System.out.println("ORACLE " + json.writeValueAsString(rows));
    }
}
