package com.personalos.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;

/** 限流与并发准入共享同一组模型入口。 */
public final class ModelRequestPaths {
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final List<String> POST_PATHS = List.of(
            "/api/v1/chat/sessions/*/stream", "/api/v1/chat/uploads", "/api/v1/chat/transcribe",
            "/api/v1/knowledge/documents", "/api/v1/knowledge/search", "/api/v1/knowledge/ask",
            "/api/v1/settings/model/test", "/api/v1/settings/model/profiles/test",
            "/api/v1/settings/model/profiles/probe-models", "/api/v1/settings/model/profiles/probe-window");

    private ModelRequestPaths() { }

    public static boolean matches(HttpServletRequest request) {
        String path = UriUtils.decode(request.getRequestURI().substring(request.getContextPath().length()),
                StandardCharsets.UTF_8);
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            return POST_PATHS.stream().anyMatch(pattern -> MATCHER.match(pattern, path));
        }
        return "PUT".equalsIgnoreCase(request.getMethod())
                && MATCHER.match("/api/v1/knowledge/documents/*", path);
    }
}
