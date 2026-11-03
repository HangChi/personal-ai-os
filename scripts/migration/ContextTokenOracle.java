package com.personalos.migration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import tools.jackson.databind.json.JsonMapper;
import com.personalos.common.ai.ContextBudgetService;
import com.personalos.common.ai.PromptTokenCounter;

public final class ContextTokenOracle {
    public static void main(String[] args) throws Exception {
        var mapper = JsonMapper.builder().build();
        var input = mapper.readTree(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        var results = new ArrayList<Object>();
        for (var row : input) {
            String text = row.get("text").asText();
            String model = row.get("model").asText();
            var result = new LinkedHashMap<String, Object>();
            result.put("tokens", PromptTokenCounter.text(text, model));
            result.put("estimated", ContextBudgetService.toTokensByContent(text));
            results.add(result);
        }
        System.out.println("ORACLE " + mapper.writeValueAsString(results));
    }
}
