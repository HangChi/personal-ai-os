package com.personalos.mcp.tool;

import com.personalos.common.ai.WebSource;
import com.personalos.common.json.JsonCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

final class McpWebSources {
    private static final Pattern FETCHED_PAGE = Pattern.compile("^Contents of (https?://[^\\s]+):\\r?\\n", Pattern.MULTILINE);
    private static final Set<String> SEARCH_TOOLS = Set.of("web_search", "search_web", "brave_web_search",
            "tavily_search", "tavily-search");

    private McpWebSources() { }

    static List<WebSource> extract(String tool, Map<String, Object> arguments, String raw, JsonCodec json) {
        if (raw.length() > (1 << 20) || !("fetch".equals(tool) || SEARCH_TOOLS.contains(tool))) return List.of();
        var result = json.fromJson(raw, JsonNode.class);
        if (result.path("isError").asBoolean(false)) return List.of();
        if ("fetch".equals(tool)) {
            if (!(arguments.get("url") instanceof String requested)) return List.of();
            for (var item : result.path("content")) {
                String text = text(item);
                var header = FETCHED_PAGE.matcher(text);
                if (!header.find() || !withoutTrailingSlash(requested).equals(withoutTrailingSlash(header.group(1)))) continue;
                String body = text.substring(header.end());
                int truncated = body.indexOf("\n\n<error>Content truncated.");
                if (truncated >= 0) body = body.substring(0, truncated);
                if (body.stripLeading().startsWith("<error>")) continue;
                var source = WebSource.from("", requested, body);
                if (source != null) return List.of(source);
            }
            return List.of();
        }
        List<WebSource> sources = extractResults(result.path("structuredContent"));
        if (!sources.isEmpty()) return sources;
        for (var item : result.path("content")) {
            String text = text(item).strip();
            if (!text.startsWith("{")) continue;
            try {
                sources = extractResults(json.fromJson(text, JsonNode.class));
                if (!sources.isEmpty()) return sources;
            } catch (IllegalStateException ignored) {
                // Older MCP servers may return ordinary text.
            }
        }
        return List.of();
    }

    private static List<WebSource> extractResults(JsonNode payload) {
        List<WebSource> sources = new ArrayList<>();
        for (var item : payload.path("results")) {
            String excerpt = item.path("content").asText("");
            if (excerpt.isBlank()) excerpt = item.path("snippet").asText("");
            if (excerpt.isBlank()) excerpt = item.path("description").asText("");
            var source = WebSource.from(item.path("title").asText(""), item.path("url").asText(""), excerpt);
            if (source != null) sources.add(source);
            if (sources.size() == 5) break;
        }
        return sources;
    }

    private static String text(JsonNode item) {
        return "text".equals(item.path("type").asText()) ? item.path("text").asText("") : "";
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
