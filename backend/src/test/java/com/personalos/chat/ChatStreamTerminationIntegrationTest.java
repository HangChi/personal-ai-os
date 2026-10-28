package com.personalos.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.entity.MessageRole;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.support.SseHeartbeatScheduler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
 * 超时路径需要把 {@code app.chat.stream-timeout} 压到秒级以下。放进大类的共享上下文里，
 * 所有正常用例也会被这个上限约束（时紧时松，在 CI 上会变成间歇性失败）。
 * 单独一个上下文，代价是多起一个容器，换来的是确定性。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        // 1500ms：要同时满足两件相反的事 ——
        //   ① 慢尾 3 秒必须超时（1500 < 3000，留了一倍余量）
        //   ② 互斥用例在「模型已进入」之后发第二条请求，此时第一条流必须还活着。
        // 原先是 400ms，而互斥用例 sleep(400) 等锁，两者零余量对齐，实测只剩 68ms，
        // 换线程模型就翻车。放宽到 1500ms 后，从模型进入到超时还有约 1.4 秒可用，
        // 发一个本地请求绰绰有余。
        "app.chat.stream-timeout=1500ms",
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
     * 于是超时必然发生在「已有半截内容」之后。这比「一个字节都没出」更接近真实：
     * 用户看到的是一条说到一半就断掉的回答。
     */
    static class StubChatModel implements ChatModel {

        static final String FIRST_CHUNK = "我先看一下你的日程";
        static final String SECOND_CHUNK = "，然后给出建议。";

        private final List<List<Message>> receivedInstructions = new CopyOnWriteArrayList<>();
        private volatile boolean slowTail;

        /**
         * 「模型已被调用」的信号，供会话互斥用例做确定性同步。
         *
         * <p>为什么可以拿它当「锁已持有」的证据：{@code ChatCompletionService.stream()} 里
         * {@code activeStreams.putIfAbsent} 是同步执行的，而真正的模型调用发生在
         * {@code Flux.defer} 的订阅阶段，即 putIfAbsent **之后**。所以只要看到这个方法被
         * 调用，会话锁必然已经拿到。
         *
         * <p>用一个可重新布防的 latch 而不是一次性 latch：桩是单例、被同类所有用例共享，
         * 而 JUnit 不保证用例顺序，必须由每个用例自己 arm。
         */
        private final AtomicReference<CountDownLatch> streamEntered =
                new AtomicReference<>(new CountDownLatch(1));

        @Override
        public ChatResponse call(Prompt prompt) {
            receivedInstructions.add(List.copyOf(prompt.getInstructions()));
            return response(FIRST_CHUNK + SECOND_CHUNK);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            receivedInstructions.add(List.copyOf(prompt.getInstructions()));
            streamEntered.get().countDown();
            ChatResponse first = response(FIRST_CHUNK);
            ChatResponse second = response(SECOND_CHUNK);
            if (!slowTail) {
                return Flux.just(first, second);
            }
            return Flux.concat(Flux.just(first), Flux.just(second).delayElements(Duration.ofSeconds(3)));
        }

        /** 重新布防 {@link #streamEntered}，下一个用例才能等到属于它自己的那次调用 */
        void armStreamEntry() {
            streamEntered.set(new CountDownLatch(1));
        }

        /** 等模型被调用；返回 false 表示在超时内没等到（说明第一流根本没跑起来） */
        boolean awaitStreamEntry(long timeout, TimeUnit unit) throws InterruptedException {
            return streamEntered.get().await(timeout, unit);
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

    /** 只用来验证「收尾时把两个定时任务都停掉」，行为仍是真实的调度器 */
    @MockitoSpyBean
    SseHeartbeatScheduler heartbeatScheduler;

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

    @Test
    @DisplayName("正常完成后看门狗必须被取消：否则每条成功的对话都在超时点刷一条假「超时」告警")
    void watchdogIsCancelledAfterNormalCompletion() throws Exception {
        stub.slowTail(false);
        String conversationId = createSession();

        streamEvents(conversationId, "正常一轮");

        // 一个流会注册两件定时任务：心跳 + 超时看门狗。收尾时**两者都要取消**。
        // 漏掉看门狗的后果不是数据错（terminated 会挡住后续动作），而是超时时限一到
        // 就刷一条 `流式对话超时` —— 我在真机日志里就是这么发现它的：
        // 一个 21:58 就正常结束的会话，在 22:00:45 报了一次 120000ms 超时。
        // 虚假告警的害处是把真超时淹没掉。
        verify(heartbeatScheduler, times(2)).stop(any());
    }

    @Test
    @DisplayName("会话级互斥：同会话第二条并发流被拒绝为 409（审查 #13）")
    void concurrentStreamToSameSessionIsRejected() throws Exception {
        // 第一条流持续约 3 秒，保证第二条到达时它仍持有会话锁
        stub.slowTail(true);
        stub.armStreamEntry();
        String conversationId = createSession();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<SseEvent>> first = executor.submit(() -> streamEvents(conversationId, "第一条流"));
            // 等模型真的被调用，而不是 sleep 一个猜出来的时长。
            //
            // 原先这里是 Thread.sleep(400)，而本类的 stream-timeout 恰好也是 400ms ——
            // 「持锁 400ms」和「等 400ms」零余量对齐，谁先到全看调度。实测曾在
            // 409 与超时之间只剩 68ms（50.520 / 50.588）。心跳线程模型一改，调度时序
            // 偏移，这个用例就翻到另一侧（拿到 200 而不是 409）。那不是产品回归，
            // 是测试把「锁还活着」赌在了超时窗口的缝隙上。
            //
            // 现在改成等桩模型的真实调用信号：putIfAbsent 在订阅之前同步完成，
            // 所以模型被调用的那一刻，锁必然已被持有 —— 与超时设置彻底解耦。
            assertThat(stub.awaitStreamEntry(5, TimeUnit.SECONDS))
                    .as("第一条流未在 5 秒内进入模型调用，锁可能根本没被抢占")
                    .isTrue();

            HttpResponse<String> second = streamRaw(conversationId, "第二条流");
            assertThat(second.statusCode())
                    .as("同会话并发第二条流应被拒绝，否则两条流并发写消息会撞 sequence_no 唯一约束")
                    .isEqualTo(409);
            assertThat(second.body()).contains("40900");

            // 第一条流最终会收尾（慢尾 3 秒必触发超时 → error 而非 done），
            // 关键是它正常结束并释放了锁 —— 断言「能等到结果」而不是具体是哪种结束
            assertThat(first.get(15, TimeUnit.SECONDS)).isNotNull();
        } finally {
            executor.shutdownNow();
        }
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
        HttpResponse<Stream<String>> response = streamRawLines(conversationId, content);
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

    /** 发流式请求，返回原始字符串响应（用于被拒绝的非 SSE 场景，如 409） */
    private HttpResponse<String> streamRaw(String conversationId, String content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/api/v1/chat/sessions/" + conversationId + "/stream"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + content + "\"}"))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<Stream<String>> streamRawLines(String conversationId, String content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/api/v1/chat/sessions/" + conversationId + "/stream"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + content + "\"}"))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
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
