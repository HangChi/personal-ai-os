package com.personalos.common.ai;

import java.net.URI;
import java.util.Locale;

/** Provider-specific official references; they do not certify a gateway's deployed capacity. */
public final class ModelWindowReferences {
    public record Reference(int tokens, String url, Integer inputLimit) {
        public Reference(int tokens, String url) { this(tokens, url, null); }
        public int configurationWindow() { return inputLimit == null ? tokens : Math.min(tokens, inputLimit); }
    }
    private ModelWindowReferences() { }

    public static Reference find(String modelName, String baseUrl) {
        if (modelName == null) return null;
        String name = modelName.strip().toLowerCase(Locale.ROOT);
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.startsWith("cmaas-")) name = name.substring(6);

        String host = "";
        try { host = URI.create(baseUrl).getHost(); } catch (RuntimeException ignored) { }
        if (host != null) host = host.toLowerCase(Locale.ROOT);
        boolean aliyun = host != null && (host.equals("dashscope.aliyuncs.com") || host.endsWith(".dashscope.aliyuncs.com")
                || host.startsWith("dashscope-") && host.endsWith(".aliyuncs.com"));
        if (aliyun && name.equals("qwen3.8-flash")) return new Reference(1_000_000,
                "https://help.aliyun.com/zh/model-studio/qwen3-8-flash", 983_616);
        if (aliyun && name.equals("deepseek-v4.1-flash")) return new Reference(1_000_000,
                "https://help.aliyun.com/zh/model-studio/deepseek-v4-1-flash");
        if (aliyun && name.equals("glm-5.3")) return new Reference(1_048_576, "https://help.aliyun.com/zh/model-studio/glm-5-3");
        if (aliyun && name.equals("minimax-m2.5")) return new Reference(204_800, "https://help.aliyun.com/zh/model-studio/minimax-m2-5");
        if (!name.equals("deepseek-v4-pro") && !name.matches("deepseek-v4-pro-[0-9]{4,8}")) return null;
        if (aliyun) {
            // https://help.aliyun.com/zh/model-studio/deepseek-v4-pro, checked 2026-10-31.
            return new Reference(1_000_000, "https://help.aliyun.com/zh/model-studio/deepseek-v4-pro");
        }
        // DeepSeek's own /models schema declares 1048576. A proxy can impose a smaller limit.
        return new Reference(1_048_576, "https://api-docs.deepseek.com/api/list-models/");
    }
}
