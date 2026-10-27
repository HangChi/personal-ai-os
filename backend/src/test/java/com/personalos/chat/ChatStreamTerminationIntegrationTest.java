package com.personalos.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.entity.MessageRole;
import com.personalos.chat.repository.ChatMessageRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;

/**
 * 流式对话的**终止路径**集成测试（2026-10-27）。
 *
 * <p>走真实 HTTP + 真实 SSE 解析，因为要验证的正是「客户端到底收到了什么」——
 * 这是单测里 mock 掉 emitter 之后唯一证明不了的事。
 *
 * <h2>为什么单独一个类</h2>
 * 超时路径需要把 {@code app.chat.stream-timeout} 压到几百毫秒。放进大类的共享上下文里，
 * 所有正常用例也会被这个上限约束（时紧时松，在 CI 上会变成间歇性失败）。
 * 单独一个上下文，代价是多起一个容器，换来的是确定性。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        // 压到 400ms：正常流（两段立即到达）仍在窗口内完成，慢流必超时
        "app.chat.stream-timeout=400ms",
        // 关掉异步摘要，避免它再调一次桩模型干扰断言
        "app.context.summary.min-messages=100000"
})
@Testcontainers
class ChatStreamTerminationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /**
     * 可编排的桩模型。
     *
     * <p>{@code slowTail=true} 时：第一段立即返回，第二段延迟 3 秒 ——
     * 于是超时（400ms）必然发生在「已有半截内容」之后。这比「一个字节都没出」更接近真实：
     * 用户看到的是一条说到一半就断掉的回答。
     */
    static class StubChatModel implements ChatModel {

        static final String FIRST_CHUNK = "我先看一下你的日程";
        static final String SECOND_CHUNK = "，然后给出建议。";

        private final List<List<Message>> receivedInstructions = new CopyOnWriteArrayList<>();
        private volatile boolean slowTail;

        @Override
        public ChatResponse call(Prompt prompt) {
            receivedInstructions.add(List.copyOf(prompt.getInstructions()));
            return response(FIRST_CHUNK + SECOND_CHUNK);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            receivedInstructions.add(List.copyOf(prompt.getInstructions()));
            ChatResponse first = response(FIRST_CHUNK);
            ChatResponse second = response(SECOND_CHUNK);
            if (!slowTail) {
                return Flux.just(first, second);
            }
            return Flux.concat(Flux.just(first), Flux.just(second).delayElements(Duration.ofSeconds(3)));
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().build();
        }

        private static ChatResponse response(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }

        void slowTail(boolean value) {
            this.slowTail = value;
        }
    }

    @TestConfiguration
    static class StubChatModelConfiguration {
        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final int port;
    private final StubChatModel stub;
    private final ChatMessageRepository messageRepository;

    @Autowired
    ChatStreamTerminationIntegrationTest(@Value("${local.server.port}") int port,
                                         StubChatModel stub,
                                         ChatMessageRepository messageRepository) {
        this.port = port;
        this.stub = stub;
        this.messageRepository = messageRepository;
    }

    private record SseEvent(String name, String data) {
    }

    @Test
    @DisplayName("超时 → 必须发 error(50202) 而不是静默关流，且把半截回答落库")
    void timeoutEmitsErrorEventAndKeepsPartialReply() throws Exception {
        stub.slowTail(true);
        String conversationId = createSession();

        List<SseEvent> events = streamEvents(conversationId, "帮我看下这周安排");

        SseEvent errorEvent = events.stream()
                .filter(event -> "error".equals(event.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("超时没有推送 error 事件，客户端只能一直转圈：" + events));
        assertThat(extractInt(errorEvent.data(), "code"))
                .as("超时应映射为 50202（AI_TIMEOUT），而不是含混的内部错误")
                .isEqualTo(50202);
        assertThat(events).extracting(SseEvent::name).doesNotContain("done");

        // 用户消息由 Advisor 在订阅时写入；助手消息原本只在正常结束才写。
        // 超时若不补写，历史里就留下一条「只有提问没有回答」的记录。
        List<ChatMessageEntity> messages = messageRepository.findAll().stream()
                .filter(message -> message.getConversationId().equals(conversationId))
                .sorted((left, right) -> Integer.compare(left.getSequenceNo(), right.getSequenceNo()))
                .toList();
        assertThat(messages).as("应有用户提问 + 一条中断的助手回答").hasSize(2);

        ChatMessageEntity reply = messages.get(1);
        assertThat(reply.getMessageType()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(reply.getContent())
                .as("已推给客户端的半截内容要保留下来")
                .contains(StubChatModel.FIRST_CHUNK)
                .as("并且必须标注中断，模型下一轮才不会把半句话当结论")
                .contains("中断");
    }

    @Test
    @DisplayName("正常完成 → 只有 done，没有 error，回答不带中断标记（回归）")
    void normalCompletionIsUnaffected() throws Exception {
        stub.slowTail(false);
        String conversationId = createSession();

        List<SseEvent> events = streamEvents(conversationId, "正常一轮");

        assertThat(events).extracting(SseEvent::name).contains("done").doesNotContain("error");

        List<ChatMessageEntity> messages = messageRepository.findAll().stream()
                .filter(message -> message.getConversationId().equals(conversationId))
                .sorted((left, right) -> Integer.compare(left.getSequenceNo(), right.getSequenceNo()))
                .toList();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getContent())
                .isEqualTo(StubChatModel.FIRST_CHUNK + StubChatModel.SECOND_CHUNK)
                .doesNotContain("中断");
    }

    // ---------------------------------------------------------------- 工具方法

    private String createSession() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/sessions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        // 创建资源返回 201（不是 200）
        assertThat(response.statusCode()).isEqualTo(201);
        Matcher matcher = Pattern.compile("\"conversationId\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
        assertThat(matcher.find()).as("创建会话失败: %s", response.body()).isTrue();
        return matcher.group(1);
    }

    private List<SseEvent> streamEvents(String conversationId, String content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/api/v1/chat/sessions/" + conversationId + "/stream"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + content + "\"}"))
                .build();

        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).as("SSE 状态码应为 200").isEqualTo(200);

        List<SseEvent> events = new ArrayList<>();
        String currentEvent = null;
        try (Stream<String> lines = response.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.startsWith("event:")) {
                    currentEvent = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    events.add(new SseEvent(currentEvent, line.substring("data:".length()).trim()));
                }
            }
        }
        return events;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static int extractInt(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        assertThat(matcher.find()).as("响应里没有字段 %s: %s", field, json).isTrue();
        return Integer.parseInt(matcher.group(1));
    }
}
