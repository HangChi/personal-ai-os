package com.personalos.chat.support;

import com.personalos.chat.entity.TokenUsageSource;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 从流式 {@code ChatResponse} 里抽取「正文」与「用量」的纯函数集合。
 *
 * <h2>为什么要从 {@code ChatResponse} 取而不是继续用 {@code content()}</h2>
 * {@code StreamResponseSpec.content()} 内部就是
 * {@code chatResponse().map(取正文).filter(非空)} —— 正文之外的元数据（含 usage）
 * 在这一步被丢掉了。改成 {@code chatResponse()} 自己映射，才能在同一个流上
 * 同时拿到正文与用量，不必再发一次非流式请求。
 *
 * <h2>为什么抽成静态方法</h2>
 * 这两个判断都带边界（{@code getResult()} 可能为 null、usage 可能是空实现、
 * token 可能是 null），而它们跑在流式回调里 —— 抛一个 NPE 就会让整轮对话变成
 * 错误帧。抽出来单独测，比在几层 lambda 里靠肉眼保证便宜得多。
 */
public final class ChatResponseInspection {

    private ChatResponseInspection() {
    }

    /**
     * 取该块的正文文本。
     *
     * @return 正文；工具调用块、纯 usage 块等没有正文的情况返回 {@code ""}（不返回 null，
     *         调用方无需判空）
     */
    public static String textOf(ChatResponse response) {
        if (response == null) {
            return "";
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            return "";
        }
        String text = generation.getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * 取该块携带的用量；没有有效用量时返回 {@code null}。
     *
     * <p><b>只有流里最后那个块带用量</b>：OpenAI 协议要求 provider 在
     * {@code stream_options.include_usage=true} 时于末尾追加一个 {@code choices} 为空的块，
     * 专门装 usage。因此调用方应当在**每块**上调用本方法并用非 null 结果覆盖，
     * 而不是只在第一块上取。
     *
     * <p>「无效用量」的判定：Spring AI 在 provider 未上报时会填 {@code EmptyUsage}，
     * 它把各个 token 字段都返回 0 —— 与「真的一个 token 都没用」无法区分，
     * 但两者都意味着「没有可信数据」，返回 null 让调用方走估算兜底。
     *
     * @return 用量快照；不存在或全为零时返回 {@code null}
     */
    public static StreamUsage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return null;
        }
        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        int prompt = promptTokens == null ? 0 : promptTokens;
        int completion = completionTokens == null ? 0 : completionTokens;
        if (prompt <= 0 && completion <= 0) {
            return null;
        }
        return new StreamUsage(response.getMetadata().getModel(), prompt, completion, TokenUsageSource.ACTUAL);
    }
}
