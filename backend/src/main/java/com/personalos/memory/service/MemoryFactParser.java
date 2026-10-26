package com.personalos.memory.service;

import com.personalos.common.json.JsonCodec;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析「记忆自动沉淀」的模型输出（阶段 22）。
 *
 * <p>独立成一个纯工具类，是因为这里最容易出问题：模型会给出代码块围栏、前后缀说明、
 * 甚至直接返回一段自然语言。把它和「调模型」「写库」分开，才能用普通单测覆盖各种脏输出 ——
 * 这类解析一旦写错，症状是「静默导入了垃圾记忆」，比抛异常难查得多。
 */
final class MemoryFactParser {

    /** 单条记忆的最短/最长长度：与 MemoryService.CONTENT_MAX_LENGTH 对齐 */
    private static final int MIN_LENGTH = 5;
    private static final int MAX_LENGTH = MemoryService.CONTENT_MAX_LENGTH;

    private MemoryFactParser() {
    }

    /**
     * 从模型输出中提取待沉淀的陈述。
     *
     * <p>宽容策略：先剥掉 ``` 围栏，再取第一个 {@code [} 到最后一个 {@code ]} 之间解析。
     * 解析失败返回空列表 —— 宁可不沉淀，也不要把半截 JSON 当内容写进库。
     *
     * @param codec    JSON 编解码
     * @param raw      模型原始输出，可为 null
     * @param maxItems 最多返回条数
     * @return 清洗后的陈述列表；无有效内容时为空列表
     */
    static List<String> parse(JsonCodec codec, String raw, int maxItems) {
        if (raw == null || raw.isBlank() || maxItems <= 0) {
            return List.of();
        }
        String json = extractJsonArray(raw);
        if (json == null) {
            return List.of();
        }
        String[] facts;
        try {
            facts = codec.fromJson(json, String[].class);
        } catch (RuntimeException ex) {
            return List.of();
        }
        if (facts == null) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (String fact : facts) {
            if (result.size() >= maxItems) {
                break;
            }
            if (fact == null) {
                continue;
            }
            String trimmed = fact.strip();
            // 过短的（「好的」「嗯」）没有长期价值；过长的多半是整段复述，不该固化
            if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
                continue;
            }
            result.add(trimmed);
        }
        return result;
    }

    /** 剥掉代码块围栏，取第一个数组字面量 */
    private static String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }
}
