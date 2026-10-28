package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.ai.config.dto.TranscriptionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 语音转写的**纯逻辑**部分：URL 拼接与结果对象的状态语义。
 *
 * <h2>为什么 URL 拼接值得单独测</h2>
 * 用户填的 baseUrl 写法不加限制：{@code https://api.openai.com}、
 * {@code .../v1}、末尾带斜杠、前后带空格都出现过。三种都要落到
 * {@code .../v1/audio/transcriptions}。拼错的表现为 404，
 * 而 404 的原因可能是「路径错」也可能是「该端点不支持」——
 * 只看日志分不清这两者，所以必须在拼出来的那一刻就断言。
 *
 * <h2>为什么结果对象的工厂方法也要测</h2>
 * 前端靠 {@code unavailable} 决定「回落到浏览器识别」还是「提示错误」。
 * 若 {@link TranscriptionResult#unavailable} 与
 * {@link TranscriptionResult#failed} 的字段搭错（比如 unavailable 忘了置 true），
 * 症状是「没配语音模型时弹出一个用户不知道怎么办的红色报错」——
 * 而这在成功路径的测试里完全看不出来。
 *
 * <p>刻意**不用 {@code @Nested}**：本项目 surefire 配置下嵌套类不被执行。
 */
@DisplayName("AudioTranscriptionService 的 URL 与结果语义")
class AudioTranscriptionServiceTest {

    // ------------------------------------------------------------------ URL 拼接

    @Test
    @DisplayName("base 不带 /v1 时补上")
    void appendsV1() {
        assertThat(AudioTranscriptionService.resolveUri("https://api.openai.com").toString())
                .isEqualTo("https://api.openai.com/v1/audio/transcriptions");
    }

    @Test
    @DisplayName("base 已带 /v1 时不重复")
    void keepsExistingV1() {
        assertThat(AudioTranscriptionService.resolveUri("https://api.openai.com/v1").toString())
                .isEqualTo("https://api.openai.com/v1/audio/transcriptions");
    }

    @Test
    @DisplayName("末尾斜杠与前后空白都被吃掉")
    void trimsTrailingSlashAndSpaces() {
        assertThat(AudioTranscriptionService.resolveUri("  https://x.example.com/v1///  ").toString())
                .isEqualTo("https://x.example.com/v1/audio/transcriptions");
    }

    @Test
    @DisplayName("带路径前缀的兼容端点保留前缀（DashScope 兼容模式）")
    void keepsPathPrefix() {
        assertThat(AudioTranscriptionService.resolveUri(
                "https://dashscope.aliyuncs.com/compatible-mode/v1").toString())
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/audio/transcriptions");
    }

    @Test
    @DisplayName("本地 Ollama/自建端点（http + 端口）也拼得出")
    void localEndpoint() {
        assertThat(AudioTranscriptionService.resolveUri("http://127.0.0.1:11434").toString())
                .isEqualTo("http://127.0.0.1:11434/v1/audio/transcriptions");
    }

    @Test
    @DisplayName("null 不会 NPE（未配置时仍要能算出原因而不是崩）")
    void nullBaseUrlDoesNotThrow() {
        assertThat(AudioTranscriptionService.resolveUri(null).toString())
                .isEqualTo("/v1/audio/transcriptions");
    }

    // ------------------------------------------------------------------ 结果语义

    @Test
    @DisplayName("unavailable：不是错误，error 只用来解释原因")
    void unavailableIsNotAnError() {
        TranscriptionResult result = TranscriptionResult.unavailable("当前生效的模型配置没有语音模型");

        assertThat(result.ok()).isFalse();
        assertThat(result.unavailable()).isTrue();
        assertThat(result.text()).isEmpty();
        // 前端据 unavailable 回落，不该把这个当红色报错弹出来
        assertThat(result.error()).contains("语音模型");
    }

    @Test
    @DisplayName("empty：识别成功但没内容，不设 error")
    void emptyIsSuccess() {
        TranscriptionResult result = TranscriptionResult.empty();

        assertThat(result.ok()).isTrue();
        assertThat(result.unavailable()).isFalse();
        assertThat(result.text()).isEmpty();
        // 没有 error 才会走到「没听清，请再说一次」而不是「转写失败」
        assertThat(result.error()).isNull();
    }

    @Test
    @DisplayName("failed：不可用标志必须为 false，否则前端会误回落")
    void failedIsNotUnavailable() {
        TranscriptionResult result = TranscriptionResult.failed("认证失败（401）：API Key 无效");

        assertThat(result.ok()).isFalse();
        assertThat(result.unavailable()).isFalse();
        assertThat(result.error()).contains("401");
    }

    @Test
    @DisplayName("success：带文本与耗时，无 error")
    void successCarriesText() {
        TranscriptionResult result = TranscriptionResult.success("今天天气不错", 123);

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).isEqualTo("今天天气不错");
        assertThat(result.latencyMs()).isEqualTo(123);
        assertThat(result.error()).isNull();
        assertThat(result.unavailable()).isFalse();
    }
}
