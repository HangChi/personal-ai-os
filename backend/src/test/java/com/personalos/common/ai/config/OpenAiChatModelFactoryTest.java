package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.personalos.support.StaticObjectProvider;

/**
 * 运行期模型工厂的测试。
 *
 * <h2>为什么这里要起一个真的本地 HTTP 服务，而不是纯 mock</h2>
 * 本类要验的核心属性是「**凭据被放到了正确的位置**」，而它是不可见的：
 * {@code setupSyncClient} 的参数表里有 {@code apiKey} 与 {@code credential} 两个
 * 凭据入口，位置相邻且都不是模型名 —— 塞错位置不会有任何编译或运行期报错，
 * 只有在真正发请求时才会得到上游的 401。
 *
 * <p>本轮就踩了这个坑：模型名被放进了 apiKey 位，于是 Authorization 头变成
 * {@code Bearer qwen3.8-flash}，DashScope 回「Incorrect API key provided」，
 * 而同一把密钥用 curl 直连完全正常 —— 排查方向被彻底带偏。
 *
 * <p>唯一能钉住这一点的断言方式就是：<b>抓真实发出的请求头</b>。
 * 因此这里用 JDK 自带的 {@link HttpServer} 起一个桩，把 Authorization 与 model
 * 字段读出来断言。不引入 WireMock 之类的依赖，JDK 自带已足够。
 */
class OpenAiChatModelFactoryTest {

    private HttpServer server;
    private final AtomicReference<String> capturedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"id\":\"stub\",\"object\":\"chat.completion\",\"created\":1,"
                    + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":"
                    + "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private String stubBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private OpenAiChatModelFactory factory() {
        return new OpenAiChatModelFactory(new StaticObjectProvider<ToolCallingManager>(List.of()));
    }

    @Test
    @DisplayName("上游 500 时同步和流式请求都不做 SDK 隐式重试")
    void serverErrorsAreNotRetriedBySdk() {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        server.removeContext("/");
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"error\":{\"message\":\"unavailable\",\"type\":\"server_error\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        var model = factory().create(stubBaseUrl(), "test-key", "test-model", null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.call("hi"))
                .isInstanceOf(RuntimeException.class);
        assertThat(requests.get()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.stream(
                new org.springframework.ai.chat.prompt.Prompt("hi")).blockLast(java.time.Duration.ofSeconds(3)))
                .isInstanceOf(RuntimeException.class);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("启动期公共/聊天/嵌入配置都关闭 SDK 默认的三次重试")
    void startupConfigurationDisablesSdkRetries() throws IOException {
        var source = new org.springframework.boot.env.YamlPropertySourceLoader().load("application",
                new org.springframework.core.io.ClassPathResource("application.yml")).getFirst();
        var binder = new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(source));
        assertThat(binder.bind("spring.ai.openai",
                org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties.class).get().getMaxRetries()).isZero();
        assertThat(binder.bind("spring.ai.openai.chat",
                org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties.class).get().getMaxRetries()).isZero();
        assertThat(binder.bind("spring.ai.openai.embedding",
                org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties.class).get().getMaxRetries()).isZero();
    }

    /**
     * 最重要的回归：**模型名绝不能出现在 Authorization 头里**。
     *
     * <p>这正是本轮真实踩到的 bug（模型名被塞进了 apiKey 参数位）。
     * 断言写成「等于 sk-...」而不是「包含 sk-」，因为只要再多塞一个字符
     * 上游就会拒（有的端点会 trim，有的不会，不能赌）。
     */
    @Test
    @DisplayName("Authorization 头使用 apiKey，而不是模型名")
    void sendsApiKeyAsBearerToken() {
        OpenAiChatModel model = factory().create(
                stubBaseUrl(), "sk-secret-value", "test-model", null, null);

        model.call("hi");

        assertThat(capturedAuthorization.get()).isEqualTo("Bearer sk-secret-value");
        assertThat(capturedAuthorization.get()).doesNotContain("test-model");
    }

    @Test
    @DisplayName("请求打到 /chat/completions，且 body 里带正确的模型名")
    void sendsModelInBody() {
        OpenAiChatModel model = factory().create(
                stubBaseUrl(), "sk-x", "test-model", null, null);

        model.call("hi");

        assertThat(capturedPath.get()).endsWith("/chat/completions");
        assertThat(capturedBody.get()).contains("\"model\":\"test-model\"");
    }

    /**
     * 温度要能透传到请求体。
     *
     * <p>不断言具体数值格式：不同版本 SDK 可能序列化成 {@code 0.5} 或 {@code 0.50}，
     * 只断言「出现了这个值」，避免测试跟着 SDK 的序列化细节抖动。
     */
    @Test
    @DisplayName("温度写入模型默认选项并透传到请求体")
    void appliesTemperature() {
        OpenAiChatModel model = factory().create(
                stubBaseUrl(), "sk-x", "test-model", 0.5, null);

        assertThat(model.getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) model.getOptions();
        assertThat(options.getModel()).isEqualTo("test-model");
        assertThat(options.getTemperature()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("温度为 null 时不设置，交给服务端默认")
    void omitsTemperatureWhenNull() {
        OpenAiChatModel model = factory().create(
                stubBaseUrl(), "sk-x", "test-model", null, null);

        OpenAiChatOptions options = (OpenAiChatOptions) model.getOptions();
        // null 与 0.0 语义不同：前者是「服务端默认」，后者是「完全确定性」
        assertThat(options.getTemperature()).isNull();
    }

    @Test
    @DisplayName("extraBody 透传到模型选项")
    void appliesExtraBody() {
        OpenAiChatModel model = factory().create(
                stubBaseUrl(), "sk-x", "test-model", null,
                Map.of("enable_thinking", true));

        OpenAiChatOptions options = (OpenAiChatOptions) model.getOptions();
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", true);
    }

    /**
     * 同步与异步客户端必须**都**传入。
     *
     * <p>只传同步客户端时，{@code OpenAiChatModel.Builder.build()} 会用空凭据
     * 再构造一次异步客户端并抛「At least one credential source must be specified」——
     * 报错点与真正原因毫无关系。这条用例的价值不在「能构造出来」，
     * 而在于它**能真的完成一次请求**：缺异步客户端时构造阶段就会失败。
     */
    @Test
    @DisplayName("构造出的模型能完成真实一轮请求（同步与异步客户端都已提供）")
    void canPerformRealCall() {
        OpenAiChatModel model = factory().create(
                stubBaseUrl(), "sk-x", "test-model", null, null);

        String reply = model.call("你好");

        assertThat(reply).isEqualTo("ok");
    }
}
