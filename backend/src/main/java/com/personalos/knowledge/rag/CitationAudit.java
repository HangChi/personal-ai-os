package com.personalos.knowledge.rag;

import com.personalos.common.json.JsonCodec;
import com.personalos.knowledge.dto.CitationDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CitationAudit {
    private static final Pattern MARK = Pattern.compile("\\[(\\d+)]");
    private static final String RULES = """
            核对回答中最多12条独立事实。claim 必须是回答中的连续原文，包含该事实所用的 [n]。只核对给出的证据。逐条标记 SUPPORTED（直接支持）、PARTIAL（部分支持）、UNSUPPORTED（证据不足或缺失）。SUPPORTED 和 PARTIAL 必须给出 claim 中的引用编号 index 和对应证据中至少四字符的连续原文 quote。UNSUPPORTED 的 index 为 0、quote 为空。仅返回 {"claims":[{"claim":"回答原文","status":"SUPPORTED","index":1,"quote":"证据原文"}]}。
            """;
    private static final Result UNVERIFIED = new Result("UNVERIFIED", List.of());
    private final RagModelGateway gateway;
    private final JsonCodec json;

    public CitationAudit(RagModelGateway gateway, JsonCodec json) {
        this.gateway = gateway;
        this.json = json;
    }

    public Result check(String answer, List<CitationDto> citations) {
        if (answer.isBlank() || answer.codePointCount(0, answer.length()) > 6000) return UNVERIFIED;
        Map<Integer, String> evidence = new LinkedHashMap<>();
        for (var citation : citations) {
            String content = citation.content();
            int end = content.offsetByCodePoints(0, Math.min(content.codePointCount(0, content.length()), 1600));
            evidence.put(citation.index(), content.substring(0, end));
        }
        try {
            var rows = gateway.call(RULES, json.toJson(Map.of("answer", answer, "evidence", evidence)), 5000).path("claims");
            if (!rows.isArray() || rows.isEmpty() || rows.size() > 12) return UNVERIFIED;
            var seen = new HashSet<String>();
            var checks = new ArrayList<Check>(rows.size());
            for (var row : rows) {
                String claim = row.path("claim").asText("");
                String status = row.path("status").asText("");
                String quote = row.path("quote").asText("");
                if (!row.path("claim").isTextual() || !row.path("status").isTextual() || !row.path("quote").isTextual()
                        || !row.path("index").isIntegralNumber() || !row.path("index").canConvertToInt()) return UNVERIFIED;
                int index = row.path("index").asInt();
                if (claim.isBlank() || !answer.contains(claim) || !seen.add(claim)) return UNVERIFIED;
                if ("UNSUPPORTED".equals(status)) {
                    if (index != 0 || !quote.isEmpty()) return UNVERIFIED;
                } else if ("SUPPORTED".equals(status) || "PARTIAL".equals(status)) {
                    String excerpt = evidence.get(index);
                    String strippedQuote = quote.strip();
                    if (excerpt == null || !hasIndex(claim, index) || !excerpt.contains(quote)
                            || strippedQuote.codePointCount(0, strippedQuote.length()) < 4) return UNVERIFIED;
                } else {
                    return UNVERIFIED;
                }
                checks.add(new Check(claim, status, index, quote));
            }
            return new Result("CHECKED", List.copyOf(checks));
        } catch (RuntimeException ex) {
            return UNVERIFIED;
        }
    }

    private static boolean hasIndex(String claim, int index) {
        var matcher = MARK.matcher(claim);
        while (matcher.find()) {
            try {
                if (Integer.parseInt(matcher.group(1)) == index) return true;
            } catch (NumberFormatException ignored) {
                // Ignore an out-of-range marker.
            }
        }
        return false;
    }

    public record Result(String status, List<Check> claims) { }
    public record Check(String claim, String status, int index, String quote) { }
}
