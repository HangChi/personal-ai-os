package com.personalos.chat.dto;

/**
 * 模型思考程度（2026-10-26）。
 *
 * <p>对齐 DashScope 兼容模式的 {@code enable_thinking} / {@code thinking_budget}：
 * 关=不思考（默认，与阶段 1 行为一致），标准/深度只差在思考预算。
 *
 * <h2>为什么用「预算」而不是「开关」</h2>
 * 思考不是免费的：预算越大，首字节越慢、计费越高。用户需要的不是「开/关」，
 * 而是「这次要不要多想、想多深」—— 所以做成三档，并把预算值显式写在这里而不是散落在调用点。
 *
 * <p><b>未知值一律回落 OFF</b>：这是前端可控的枚举，出现未知值说明请求被伪造或版本不匹配，
 * 按最保守（最便宜、最快）的档位执行，比抛异常更合理。
 */
public enum ThinkingLevel {

    /** 不思考：默认档，行为与阶段 1 逐字符一致 */
    OFF("off", 0),

    /** 标准思考：适合日常问答与一般推理 */
    STANDARD("standard", 2048),

    /** 深度思考：复杂排错、方案权衡；首字节明显变慢 */
    DEEP("deep", 8192);

    private final String code;
    private final int budget;

    ThinkingLevel(String code, int budget) {
        this.code = code;
        this.budget = budget;
    }

    public String code() {
        return code;
    }

    /** 思考预算（token 数）；OFF 为 0 */
    public int budget() {
        return budget;
    }

    public boolean enabled() {
        return this != OFF;
    }

    /**
     * 容错解析：null / 空 / 未知一律回落 OFF。
     *
     * <p>只去首尾空白，**不做大小写归一** —— 与前端 {@code clampThinkingLevel} 口径一致。
     * 合法编码由界面自己产生（小写），出现 {@code DEEP} 这类值说明请求被手改过，
     * 此时按最保守档位执行比「猜用户想开思考」更安全。
     */
    public static ThinkingLevel fromCode(String code) {
        if (code == null || code.isBlank()) {
            return OFF;
        }
        String normalized = code.strip();
        for (ThinkingLevel level : values()) {
            if (level.code.equals(normalized)) {
                return level;
            }
        }
        return OFF;
    }
}
