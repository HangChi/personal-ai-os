package com.personalos.common.ai.config;

/**
 * 模型端点的协议类型。
 *
 * <h2>这个枚举只管「怎么问出模型列表」，不管「怎么聊天」</h2>
 * 聊天链路统一走 OpenAI 兼容协议（DashScope、DeepSeek、Moonshot、智谱、
 * OpenRouter、以及 Ollama 的 {@code /v1} 兼容端点都支持）。本枚举只决定
 * 「获取模型列表」时打哪个 URL、带什么鉴权头、解析哪个字段 ——
 * 因为这三件事在三种协议下**完全不同**，无法用一套代码猜出来。
 *
 * <p>把两者混为一谈会导出错误预期：以为选了 ANTHROPIC 就会用 Anthropic
 * 的 Messages API 聊天。实际不会 —— 本项目没装 anthropic starter。
 * 若将来要支持 Anthropic 原生聊天，那是另一件事（需要新 starter +
 * 重新映射工具调用与流式协议），与本枚举无关。
 *
 * <h2>各协议的模型列表接口</h2>
 * <table border="1">
 *   <caption>探测接口对照</caption>
 *   <tr><th>协议</th><th>请求</th><th>鉴权</th><th>响应字段</th></tr>
 *   <tr>
 *     <td>{@link #OPENAI}</td>
 *     <td>{@code GET {base}/models}</td>
 *     <td>{@code Authorization: Bearer &lt;key&gt;}</td>
 *     <td>{@code data[].id}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #ANTHROPIC}</td>
 *     <td>{@code GET {base}/v1/models}</td>
 *     <td>{@code x-api-key} + {@code anthropic-version}</td>
 *     <td>{@code data[].id}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #OLLAMA}</td>
 *     <td>{@code GET {origin}/api/tags}</td>
 *     <td>无需密钥</td>
 *     <td>{@code models[].name}</td>
 *   </tr>
 * </table>
 */
public enum ModelProtocol {

    /** OpenAI 兼容协议：绝大多数国内外厂商与中转站都属于这一类 */
    OPENAI,

    /** Anthropic 官方协议：鉴权头与路径都与 OpenAI 不同 */
    ANTHROPIC,

    /**
     * Ollama 本地运行时。
     *
     * <p>注意它的模型列表在 {@code /api/tags}（Ollama 原生 API），而不是
     * OpenAI 兼容端点的 {@code /v1/models}。两者都能问出模型，但原生接口
     * 返回的信息更全（大小、量化等级、修改时间）。
     */
    OLLAMA;

    /**
     * 宽松解析：无法识别时回落 {@link #OPENAI}。
     *
     * <p>为什么不抛异常：{@code protocol} 来自用户输入（前端下拉或 API 调用），
     * 一个拼错的值不应该让「保存配置」整个失败 —— 那会让用户卡在一个
     * 与他的真实目的（存下一套配置）无关的错误上。回落成最常见的协议，
     * 行为可预期；数据库层的 CHECK 约束会挡住非法值真正落库。
     *
     * @param raw 原始值，可为 null / 空白 / 大小写混杂
     * @return 对应的协议；识别不出时为 {@link #OPENAI}
     */
    public static ModelProtocol parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPENAI;
        }
        String normalized = raw.strip().toUpperCase(java.util.Locale.ROOT);
        for (ModelProtocol protocol : values()) {
            if (protocol.name().equals(normalized)) {
                return protocol;
            }
        }
        return OPENAI;
    }
}
