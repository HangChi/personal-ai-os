package com.personalos.common.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Request protection limits. Token counts remain estimates unless the provider reports usage. */
@ConfigurationProperties(prefix = "app.context.protection")
public record ContextProtectionProperties(int outputReserveTokens, int safetyMarginTokens,
                                          int imageReserveTokens, int maxRetries) {
    public ContextProtectionProperties {
        outputReserveTokens = outputReserveTokens > 0 ? outputReserveTokens : 4096;
        safetyMarginTokens = safetyMarginTokens > 0 ? safetyMarginTokens : 512;
        imageReserveTokens = imageReserveTokens > 0 ? imageReserveTokens : 4096;
        maxRetries = Math.max(0, Math.min(maxRetries, 2));
    }

    public static ContextProtectionProperties defaults() {
        return new ContextProtectionProperties(4096, 512, 4096, 1);
    }
}
