package com.personalos.chat;

import com.personalos.support.ContainerContextTest;

import static org.assertj.core.api.Assertions.*;
import com.personalos.chat.dto.ChatRunDto;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.chat.repository.ChatRunRepository;
import com.personalos.chat.service.ChatRunService;
import com.personalos.chat.service.ChatSessionService;
import com.personalos.common.idempotency.*;
import com.personalos.common.json.JsonCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.ai.model.chat=none", "app.context.summary.min-messages=100000"})
@Testcontainers
class ChatRunIntegrationTest extends ContainerContextTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired ChatRunService runs;
    @Autowired ChatRunRepository repository;
    @Autowired ChatSessionService sessions;
    @Autowired OperationRecordRepository operations;
    @Autowired IdempotencyService idempotency;
    @Autowired JsonCodec json;
    @Autowired com.personalos.agent.plan.service.AgentPlanService plans;
    @Autowired com.personalos.common.confirmation.ConfirmationService confirmations;
    @Autowired com.personalos.common.ai.tool.ToolExecutor tools;
    @Autowired com.personalos.common.web.ModelConcurrencyGate gate;
    @Autowired Model model;
    static class Model implements ChatModel {
        volatile boolean hold;
        @Override public ChatResponse call(Prompt prompt) { return response("完成"); }
        @Override public ChatOptions getOptions() { return ChatOptions.builder().build(); }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            if (hold) return Flux.never();
            return Flux.concat(Flux.just(response("开始")), Flux.just(response("完成")).delayElements(Duration.ofMillis(400)));
        }
        private ChatResponse response(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    }
    @TestConfiguration static class Config { @Bean Model model() { return new Model(); } }
    private StreamChatRequest request() { return new StreamChatRequest("整理任务", false, 4, "off", List.of()); }
    private ChatRunDto awaitFinished(String conversation, String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            var run = runs.get(conversation, id, 0);
            if (!"RUNNING".equals(run.status())) return run;
            Thread.sleep(25);
        }
        throw new AssertionError("任务未在 5 秒内结束");
    }
    @Test void backgroundPermitSurvivesHttpAndRetryAndIsReleasedOnStop() throws Exception {
        model.hold = true;
        var held = new java.util.ArrayList<com.personalos.common.web.ModelConcurrencyGate.Lease>();
        String conversation = sessions.create("并发准入").conversationId();
        String other = sessions.create("被限流").conversationId();
        String id = UUID.randomUUID().toString();
        try {
            runs.start(conversation, id, request());
            for (var lease = gate.tryAcquire(); lease != null; lease = gate.tryAcquire()) held.add(lease);
            assertThat(held).hasSize(3);
            assertThat(runs.start(conversation, id, request()).id()).isEqualTo(id);
            assertThatThrownBy(() -> runs.start(other, "rejected", request()))
                    .isInstanceOf(com.personalos.common.exception.BusinessException.class)
                    .hasMessageContaining("频繁");
            assertThat(repository.find(other, "rejected")).isEmpty();
            runs.stop(conversation, id);
            var released = gate.tryAcquire();
            assertThat(released).isNotNull();
            released.close();
        } finally { runs.stop(conversation, id); held.forEach(AutoCloseableLease -> AutoCloseableLease.close()); model.hold = false; }
    }
    @Test void persistedSseReplaysByCursorAndDisconnectDoesNotCancelWork() throws Exception {
        String conversation = sessions.create("流式进度").conversationId();
        var run = runs.start(conversation, UUID.randomUUID().toString(), request());
        var url = "http://127.0.0.1:" + port + "/api/v1/chat/sessions/" + conversation + "/runs/" + run.id() + "/events";
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            response.body().close();
            var done = awaitFinished(conversation, run.id());
            assertThat(done.status()).isEqualTo("COMPLETED");
            long cursor = done.events().getFirst().id();
            var replay = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "?after=" + cursor))
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(replay.body()).contains("event:run", "COMPLETED", "done")
                    .doesNotContain("\"name\":\"start\"");
        }
    }
    @Test void httpStartReturnsExecutionIdentityAndBrowserCanReconnectByCursor() throws Exception {
        String conversation = sessions.create("HTTP 后台执行").conversationId();
        String id = UUID.randomUUID().toString();
        var client = java.net.http.HttpClient.newHttpClient();
        var start = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                "http://localhost:" + port + "/api/v1/chat/sessions/" + conversation + "/runs"))
                .header("Content-Type", "application/json").header("Idempotency-Key", id)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.toJson(request()))).build();
        var response = client.send(start, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(id, "RUNNING");
        var done = awaitFinished(conversation, id);
        long cursor = done.events().getFirst().id();
        var reconnect = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                "http://localhost:" + port + "/api/v1/chat/sessions/" + conversation + "/runs/" + id + "?after=" + cursor)).GET().build();
        var replay = client.send(reconnect, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("COMPLETED", "delta", "done").doesNotContain("\"name\":\"start\"");
    }
    @Test void backgroundFinishesWithoutAnyBrowserAndCursorReplaysOnlyNewEvents() throws Exception {
        String conversation = sessions.create("后台任务").conversationId();
        var run = runs.start(conversation, UUID.randomUUID().toString(), request());
        assertThat(runs.start(conversation, run.id(), request()).id()).isEqualTo(run.id());
        var done = awaitFinished(conversation, run.id());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.events()).extracting(ChatRunDto.Event::name).contains("start", "delta", "done");
        long cursor = done.events().getFirst().id();
        assertThat(runs.get(conversation, run.id(), cursor).events()).allMatch(event -> event.id() > cursor);
        String other = sessions.create("另一会话").conversationId();
        assertThatThrownBy(() -> runs.get(other, run.id(), 0)).hasMessageContaining("不存在");
    }
    @Test void explicitStopPersistsProgressAndResumeUsesSameExecutionIdentity() throws Exception {
        String conversation = sessions.create("暂停恢复").conversationId();
        var run = runs.start(conversation, UUID.randomUUID().toString(), request());
        runs.stop(conversation, run.id());
        assertThat(runs.get(conversation, run.id(), 0).status()).isEqualTo("STOPPED");
        assertThat(runs.resume(conversation, run.id()).id()).isEqualTo(run.id());
        assertThat(awaitFinished(conversation, run.id()).events()).extracting(ChatRunDto.Event::name).contains("reset", "done");
    }
    @Test void unfinishedPlanKeepsContinueEntryAfterTheModelEndsItsTurn() throws Exception {
        String conversation = sessions.create("未结束的计划").conversationId();
        plans.createPlan(conversation, "整理任务", List.of("查找任务", "整理结果"));
        var run = runs.start(conversation, UUID.randomUUID().toString(), request());
        assertThat(awaitFinished(conversation, run.id()).status()).isEqualTo("INTERRUPTED");
    }
    @Test void resumingAnOldTaskCannotOverlapAnotherTaskInTheSameConversation() throws Exception {
        String conversation = sessions.create("会话互斥").conversationId();
        var first = runs.start(conversation, UUID.randomUUID().toString(), request());
        runs.stop(conversation, first.id());
        var second = runs.start(conversation, UUID.randomUUID().toString(), request());
        assertThatThrownBy(() -> runs.resume(conversation, first.id())).hasMessageContaining("其他任务正在执行");
        awaitFinished(conversation, second.id());
    }
    @Test void confirmationGatesResumeUntilAnExplicitHumanDecision() throws Exception {
        String conversation = sessions.create("等待人工确认").conversationId();
        var confirmation = confirmations.create(com.personalos.common.confirmation.ConfirmationAction.DELETE_TASK,
                "删除测试任务", java.util.Map.of("ids", List.of()), 1, conversation);
        var run = runs.start(conversation, UUID.randomUUID().toString(), request());
        assertThat(awaitFinished(conversation, run.id()).status()).isEqualTo("WAITING_CONFIRMATION");
        assertThatThrownBy(() -> runs.resume(conversation, run.id())).hasMessageContaining("先处理待确认");
        confirmations.cancel(confirmation.id(), confirmation.token());
        runs.resume(conversation, run.id());
        assertThat(awaitFinished(conversation, run.id()).status()).isEqualTo("COMPLETED");
    }
    @Test void toolActivitiesAreOrderedAndStableRunIdentityPreventsDuplicateWrites() {
        String conversation = sessions.create("工具续跑").conversationId();
        String id = UUID.randomUUID().toString();
        AtomicInteger effects = new AtomicInteger();
        java.util.List<String> states = new java.util.ArrayList<>();
        com.personalos.common.ai.tool.ToolProgress progress = (activityId, tool, status, duration) -> states.add(status);
        var context = new org.springframework.ai.chat.model.ToolContext(java.util.Map.of(
                com.personalos.common.ai.ToolContextKeys.CONVERSATION_ID, conversation,
                com.personalos.common.ai.ToolContextKeys.TURN_ID, id,
                com.personalos.common.ai.ToolContextKeys.PROGRESS, progress));
        java.util.function.Supplier<com.personalos.common.ai.tool.ToolExecutionResult> action = () -> {
            effects.incrementAndGet();
            assertThat(states.getLast()).isEqualTo("RUNNING");
            return com.personalos.common.ai.tool.ToolExecutionResult.ok("已写入", null);
        };
        tools.write("externalWrite", java.util.Map.of("title", "测试"), context, action);
        tools.write("externalWrite", java.util.Map.of("title", "测试"), context, action);
        assertThat(effects).hasValue(1);
        assertThat(states).containsExactly("RUNNING", "OK", "RUNNING", "DUPLICATE");
    }
    @Test void restartAutomaticallyRecoversOnlyUnambiguousRuns() throws Exception {
        String conversation = sessions.create("重启续跑").conversationId();
        String id = UUID.randomUUID().toString();
        repository.create(id, conversation, json.toJson(request()));
        String uncertainConversation = sessions.create("未知结果").conversationId();
        String uncertainId = UUID.randomUUID().toString();
        repository.create(uncertainId, uncertainConversation, json.toJson(request()));
        operations.saveAndFlush(new OperationRecordEntity("unknown-" + uncertainId, OperationSource.TOOL,
                "externalWrite", "digest", uncertainConversation, uncertainId, Instant.now().minusSeconds(600)));
        runs.recover();
        assertThat(awaitFinished(conversation, id).status()).isEqualTo("COMPLETED");
        assertThat(runs.get(uncertainConversation, uncertainId, 0).status()).isEqualTo("INTERRUPTED");
        assertThatThrownBy(() -> runs.resume(uncertainConversation, uncertainId)).hasMessageContaining("尚未核实");
        AtomicInteger effects = new AtomicInteger();
        assertThatThrownBy(() -> idempotency.execute(new IdempotencyRequest("unknown-" + uncertainId,
                OperationSource.TOOL, "externalWrite", "digest", uncertainConversation, uncertainId), () -> {
                    effects.incrementAndGet(); return IdempotentResult.of("{}", IdempotencyOutcome.SUCCESS);
                })).hasMessageContaining("尚未确定");
        assertThat(effects).hasValue(0);
    }
}
