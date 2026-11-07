package com.personalos.agent.interaction;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.personalos.agent.interaction.dto.AgentFollowup;
import com.personalos.agent.interaction.dto.AgentQuestion;
import com.personalos.agent.interaction.repository.AgentInteractionRepository;
import com.personalos.agent.interaction.service.AgentInteractionService;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.chat.repository.ChatRunRepository;
import com.personalos.common.ai.AgentControlSignal;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AgentInteractionServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final AgentInteractionRepository repository = mock(AgentInteractionRepository.class);
    final ChatRunRepository runs = mock(ChatRunRepository.class);
    final AgentInteractionService service = new AgentInteractionService(repository, runs, new JsonCodec(json));

    static Stream<Arguments> questionCases() throws Exception {
        var fixture = new ObjectMapper().readTree(Files.readString(Path.of("../testdata/agent-rag/questions.json")));
        var cases = new ArrayList<Arguments>();
        for (var test : fixture.path("cases")) cases.add(Arguments.of(test.path("name").asString(), test));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("questionCases")
    void sharedQuestionValidationAndAcceptedBoundaries(String name, JsonNode test) {
        var options = test.path("options").isNull() ? null : List.of(json.treeToValue(test.path("options"), AgentQuestion.Option[].class));
        when(runs.find("c", "r")).thenReturn(Optional.of(new ChatRunRepository.Run("r", "c", "{}", test.path("runStatus").asString())));
        if (test.path("expectedError").asInt() != 0) {
            assertThatThrownBy(() -> service.ask("c", "r", test.path("question").asString(), options)).as(name)
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode().getCode()).isEqualTo(40900));
            verify(repository, never()).createQuestion(any(), anyString());
            assertThatCode(() -> service.checkpoint("r")).doesNotThrowAnyException();
        } else {
            var result = service.ask("c", "r", test.path("question").asString(), options);
            assertThat(result.question()).isEqualTo(test.path("question").asString().strip());
            assertThat(result.options()).isEqualTo(options == null ? List.of() : options);
            assertThat(result.status()).isEqualTo("PENDING");
            verify(repository).createQuestion(eq(result), anyString());
            assertThatThrownBy(() -> service.checkpoint("r")).isInstanceOf(AgentControlSignal.class);
        }
    }

    @Test
    void duplicateQuestionReturnsPriorAnswerAndPendingQuestionBlocksNewQuestion() {
        when(runs.find("c", "r")).thenReturn(Optional.of(new ChatRunRepository.Run("r", "c", "{}", "RUNNING")));
        var answered = new AgentQuestion("q", "r", "问题", List.of(), "ANSWERED", "已决定");
        when(repository.byDigest(eq("r"), anyString())).thenReturn(Optional.of(answered));
        assertThat(service.ask("c", "r", "问题", null)).isSameAs(answered);
        verify(repository, never()).createQuestion(any(), anyString());
        when(repository.byDigest(eq("r"), anyString())).thenReturn(Optional.empty());
        when(repository.pendingQuestion("r")).thenReturn(Optional.of(answered));
        assertThatThrownBy(() -> service.ask("c", "r", "另一个问题", null)).hasMessageContaining("先等待");
        assertThatThrownBy(() -> service.ask("missing", "r", "问题", null)).hasMessageContaining("后台执行任务");
        assertThatThrownBy(() -> service.ask("c", "r", null, null)).hasMessageContaining("问题须");
        assertThatThrownBy(() -> service.ask("c", "r", "问题", java.util.Arrays.asList((AgentQuestion.Option) null)))
                .hasMessageContaining("选项");
    }

    @Test
    void answerIsTrimmedAndIdempotentButCannotOverwriteHumanDecision() {
        var answered = new AgentQuestion("q", "r", "问题", List.of(), "ANSWERED", "表格");
        when(repository.question("r", "q")).thenReturn(Optional.of(answered));
        service.answer("r", "q", "  表格  ");
        verify(repository, never()).answer(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service.answer("r", "q", "列表")).hasMessageContaining("不能覆盖");
        assertThatThrownBy(() -> service.answer("r", "other", "表格")).hasMessageContaining("不属于");
        when(repository.question("r", "q")).thenReturn(Optional.of(new AgentQuestion("q", "r", "问题", List.of(), "PENDING", null)));
        service.answer("r", "q", "  表格  ");
        verify(repository).answer("r", "q", "表格");
        assertThatCode(() -> service.checkpoint("r")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4001})
    void answerAndEditRejectEmptyAndOversizedContent(int length) {
        for (String text : List.of("x".repeat(length), " ")) {
            assertThatThrownBy(() -> service.answer("r", "q", text)).hasMessageContaining("回答须");
            assertThatThrownBy(() -> service.edit("c", "i", text)).hasMessageContaining("消息须");
        }
        assertThatThrownBy(() -> service.answer("r", "q", null)).hasMessageContaining("回答须");
        assertThatThrownBy(() -> service.edit("c", "i", null)).hasMessageContaining("消息须");
        verifyNoInteractions(repository);
    }

    @Test
    void followupIdempotencyChecksRequestModeAndRunBeforeQueueCapacity() {
        var input = input("QUEUE", "PENDING");
        when(repository.followup("c", "i")).thenReturn(Optional.of(input));
        when(repository.pendingInputs("c")).thenReturn(java.util.Collections.nCopies(20, input));
        assertThat(service.submit(input)).isSameAs(input);
        for (var changed : List.of(
                new AgentFollowup("i", "other", "c", "QUEUE", input.request(), "PENDING"),
                input("STEER", "PENDING"),
                new AgentFollowup("i", "r", "c", "QUEUE", request("其他要求"), "PENDING"))) {
            assertThatThrownBy(() -> service.submit(changed)).hasMessageContaining("已用于其他内容");
        }
        when(repository.followup("c", "i")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submit(input)).hasMessageContaining("20 条");
        assertThatThrownBy(() -> service.submit(input("UNKNOWN", "PENDING"))).hasMessageContaining("排队或引导");
        verify(repository, never()).createFollowup(any());
    }

    @Test
    void steeringSignalsAreRestoredAppliedAndClearedWithoutChangingQueuedMessages() {
        var queue = input("QUEUE", "PENDING");
        service.submit(queue);
        assertThatCode(() -> service.checkpoint("r")).doesNotThrowAnyException();
        verify(repository).createFollowup(queue);
        var steering = new AgentFollowup("steer", "r", "c", "STEER", request("只给结论"), "PENDING");
        service.submit(steering);
        assertThatThrownBy(() -> service.checkpoint("r")).isInstanceOf(AgentControlSignal.class);
        when(repository.pendingSteering("r")).thenReturn(List.of(steering)).thenReturn(List.of());
        assertThat(service.applySteering("r")).containsExactly(steering);
        verify(repository).status("steer", "APPLIED");
        assertThatCode(() -> service.checkpoint("r")).doesNotThrowAnyException();
        when(repository.pendingQuestion("r")).thenReturn(Optional.of(new AgentQuestion("q", "r", "问题", List.of(), "PENDING", null)));
        service.restore("r");
        assertThatThrownBy(() -> service.checkpoint("r")).isInstanceOf(AgentControlSignal.class);
    }

    @Test
    void cancelAndSteerRejectProcessedInputsAndSteeringRetriesAreIdempotent() {
        when(repository.followup("c", "i")).thenReturn(Optional.of(input("QUEUE", "APPLIED")));
        assertThatThrownBy(() -> service.cancel("c", "i")).hasMessageContaining("无法移除");
        when(repository.followup("c", "i")).thenReturn(Optional.of(input("STEER", "APPLIED")));
        assertThat(service.steer("c", "i", "new")).isFalse();
        verify(repository, never()).steer(anyString(), anyString(), anyString());
        when(repository.followup("c", "i")).thenReturn(Optional.of(input("QUEUE", "PENDING")));
        assertThatThrownBy(() -> service.steer("c", "i", "new")).hasMessageContaining("无法改为引导");
        when(repository.steer("c", "i", "new")).thenReturn(true);
        assertThat(service.steer("c", "i", "new")).isTrue();
        assertThatThrownBy(() -> service.checkpoint("new")).isInstanceOf(AgentControlSignal.class);
        service.cancel("c", "i");
        verify(repository).status("i", "CANCELLED");
        assertThatThrownBy(() -> service.cancel("c", "missing")).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.steer("c", "missing", "r")).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.edit("c", "missing", "内容")).hasMessageContaining("不存在");
    }

    private StreamChatRequest request(String content) { return new StreamChatRequest(content, false, 4, "off", List.of()); }
    private AgentFollowup input(String mode, String status) { return new AgentFollowup("i", "r", "c", mode, request("要求"), status); }
}
