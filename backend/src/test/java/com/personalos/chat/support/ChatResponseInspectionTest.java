package com.personalos.chat.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.chat.entity.TokenUsageSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * {@link ChatResponseInspection} 的契约测试。
 *
 * <p>这两段代码跑在流式回调里，抛一个 NPE 就会让整轮对话变成错误帧 ——
 * 而边界情况（工具调用块没有正文、usage 是空实现、token 为 null）
 * 在日常对话里不容易自然出现。因此在这里逐个构造。
 */
class ChatResponseInspectionTest {

    private static ChatResponse withUsage(String text, String model, org.springframework.ai.chat.metadata.Usage usage) {
        return new ChatResponse(
                java.util.List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model(model).usage(usage).build());
    }

    @Test
    @DisplayName("取正文：正常块返回文本")
    void textOfReturnsContent() {
        ChatResponse response = withUsage("你好", "m", new EmptyUsage());

        assertThat(ChatResponseInspection.textOf(response)).isEqualTo("你好");
    }

    @Test
    @DisplayName("取正文：null 响应返回空串而不是 NPE")
    void textOfToleratesNull() {
        assertThat(ChatResponseInspection.textOf(null)).isEmpty();
    }

    @Test
    @DisplayName("取正文：没有 generation 的块（纯 usage 块）返回空串")
    void textOfToleratesMissingGeneration() {
        // 这正好是 include_usage 时末尾那个 choices 为空的块
        ChatResponse usageOnly = new ChatResponse(java.util.List.of(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 5)).build());

        assertThat(ChatResponseInspection.textOf(usageOnly)).isEmpty();
    }

    @Test
    @DisplayName("取正文：文本为 null 时返回空串")
    void textOfToleratesNullText() {
        // AssistantMessage 的 text 允许为 null（多模态/工具调用块）
        ChatResponse response = new ChatResponse(java.util.List.of(new Generation(new AssistantMessage((String) null))),
                ChatResponseMetadata.builder().usage(new EmptyUsage()).build());

        assertThat(ChatResponseInspection.textOf(response)).isEmpty();
    }

    @Test
    @DisplayName("取用量：有真实 usage 时标为 ACTUAL 并带上模型名")
    void usageOfReadsActualUsage() {
        ChatResponse response = withUsage("x", "qwen3.8-flash", new DefaultUsage(3071, 5));

        StreamUsage usage = ChatResponseInspection.usageOf(response);

        assertThat(usage).isNotNull();
        assertThat(usage.promptTokens()).isEqualTo(3071);
        assertThat(usage.completionTokens()).isEqualTo(5);
        assertThat(usage.source()).isEqualTo(TokenUsageSource.ACTUAL);
        assertThat(usage.model()).isEqualTo("qwen3.8-flash");
    }

    @Test
    @DisplayName("取用量：EmptyUsage 视为没有数据（provider 未上报）")
    void usageOfIgnoresEmptyUsage() {
        ChatResponse response = withUsage("x", "mock", new EmptyUsage());

        // 关键契约：EmptyUsage 的各个 token 字段都是 0，与「真的没用量」无法区分。
        // 返回 null 让调用方走估算兜底，而不是记一条 0/0 的假记录
        assertThat(ChatResponseInspection.usageOf(response)).isNull();
    }

    @Test
    @DisplayName("取用量：全零的真实 usage 也视为没有数据")
    void usageOfIgnoresZeroUsage() {
        ChatResponse response = withUsage("x", "m", new DefaultUsage(0, 0));

        assertThat(ChatResponseInspection.usageOf(response)).isNull();
    }

    @Test
    @DisplayName("取用量：null 响应与无 metadata 都不会 NPE")
    void usageOfToleratesNull() {
        assertThat(ChatResponseInspection.usageOf(null)).isNull();
        assertThat(ChatResponseInspection.usageOf(new ChatResponse(java.util.List.of()))).isNull();
    }

    @Test
    @DisplayName("只有 prompt 或只有 completion 也算有数据")
    void usageOfAcceptsPartialUsage() {
        // 个别实现只填一侧。这时另一侧为 0 是真实情况，不该整条丢弃
        StreamUsage promptOnly = ChatResponseInspection.usageOf(withUsage("x", "m", new DefaultUsage(120, 0)));
        StreamUsage completionOnly = ChatResponseInspection.usageOf(withUsage("x", "m", new DefaultUsage(0, 8)));

        assertThat(promptOnly).isNotNull();
        assertThat(promptOnly.promptTokens()).isEqualTo(120);
        assertThat(completionOnly).isNotNull();
        assertThat(completionOnly.completionTokens()).isEqualTo(8);
    }

    @Test
    @DisplayName("估算用量：中文按 1.6 字/token 折算并标为 ESTIMATED")
    void estimatedUsesCjkRatioForChinese() {
        String chinese = "测".repeat(1600);
        StreamUsage usage = StreamUsage.estimated(null, chinese, 1600, "测".repeat(160), 160);

        // 实测中文 1 token ≈ 1.679 字，取 1.6 → 1600 / 1.6 = 1000
        assertThat(usage.promptTokens()).isEqualTo(1000);
        assertThat(usage.completionTokens()).isEqualTo(100);
        assertThat(usage.source()).isEqualTo(TokenUsageSource.ESTIMATED);
        assertThat(usage.isMeaningful()).isTrue();
    }

    @Test
    @DisplayName("估算用量：英文按 5.5 字符/token 折算，不再高估 3.5 倍")
    void estimatedUsesLatinRatioForEnglish() {
        String english = "a".repeat(5500);
        StreamUsage usage = StreamUsage.estimated(null, english, 5500, "a".repeat(550), 550);

        // 实测英文 1 token ≈ 5.698 字符；旧实现用 1.6 会算成 3438，高估 3.5 倍
        assertThat(usage.promptTokens()).isEqualTo(1000);
        assertThat(usage.completionTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("估算用量：零字符时不算「有意义」，避免写入空记录")
    void estimatedOfZeroIsNotMeaningful() {
        assertThat(StreamUsage.estimated(null, null, 0, null, 0).isMeaningful()).isFalse();
    }
}
