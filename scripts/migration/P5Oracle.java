package com.personalos.migration;

import com.personalos.calendar.entity.RecurrenceType;
import com.personalos.calendar.recurrence.RecurrenceCalculator;
import com.personalos.calendar.tool.CalendarTools;
import com.personalos.common.json.JsonCodec;
import com.personalos.memory.tool.MemoryTools;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.support.ToolCallbacks;
import tools.jackson.databind.json.JsonMapper;

public final class P5Oracle {
    public static void main(String[] args) throws Exception {
        var mapper = JsonMapper.builder().build();
        var input = mapper.readTree(System.in);
        var definitions = new ArrayList<Map<String, Object>>();
        for (var callback : ToolCallbacks.from(new CalendarTools(null, null, null, null), new MemoryTools(null, null))) {
            var definition = callback.getToolDefinition();
            var row = new LinkedHashMap<String, Object>();
            row.put("name", definition.name());
            row.put("description", definition.description());
            row.put("parameters", mapper.readTree(definition.inputSchema()));
            definitions.add(row);
        }
        definitions.sort(Comparator.comparing(row -> (String) row.get("name")));

        var dates = new ArrayList<List<String>>();
        for (var row : input.get("recurrence")) {
            var calculator = new RecurrenceCalculator(ZoneId.of(row.get("zone").asText()));
            var rule = row.get("rule").isNull() ? null : RecurrenceType.valueOf(row.get("rule").asText());
            var until = row.get("until").isNull() ? null : Instant.parse(row.get("until").asText());
            dates.add(calculator.occurrences(rule, Instant.parse(row.get("start").asText()), until,
                    Instant.parse(row.get("from").asText()), Instant.parse(row.get("to").asText()))
                    .stream().map(Instant::toString).toList());
        }

        var parser = Class.forName("com.personalos.memory.service.MemoryFactParser")
                .getDeclaredMethod("parse", JsonCodec.class, String.class, int.class);
        parser.setAccessible(true);
        var codec = new JsonCodec(mapper);
        var facts = new ArrayList<Object>();
        for (var row : input.get("facts")) {
            facts.add(parser.invoke(null, codec, row.get("raw").asText(), row.get("limit").asInt()));
        }
        System.out.println("ORACLE " + mapper.writeValueAsString(Map.of("tools", definitions, "dates", dates, "facts", facts)));
    }
}
