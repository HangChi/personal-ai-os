package com.personalos.common.ai;

import java.net.URI;
import java.util.List;

public record WebSource(String title, String url, String content, String host) {
    public static WebSource from(String title, String url, String content) {
        if (url.length() > 4096) return null;
        try {
            URI link = URI.create(url);
            String host = link.getHost();
            if (host == null || link.getUserInfo() != null
                    || !("https".equals(link.getScheme()) || "http".equals(link.getScheme()))) return null;
            String excerpt = limit(content, 1600);
            if (excerpt.isBlank()) return null;
            String label = limit(title, 200);
            return new WebSource(label.isBlank() ? limit(host, 200) : label, url, excerpt, limit(host, 255));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String limit(String text, int max) {
        String cleaned = text.replace("\u0000", "").strip();
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, Math.min(max, cleaned.codePointCount(0, cleaned.length()))));
    }

    public record Numbered(int index, String title, String url, String content) { }

    @FunctionalInterface
    public interface Registrar {
        List<Numbered> add(List<WebSource> sources);
    }
}
