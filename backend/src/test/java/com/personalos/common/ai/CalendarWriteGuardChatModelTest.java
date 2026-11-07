package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.support.ScriptedToolCallingChatModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class CalendarWriteGuardChatModelTest {
    private final ScriptedToolCallingChatModel raw = new ScriptedToolCallingChatModel();
    private final CalendarWriteGuardChatModel guard = new CalendarWriteGuardChatModel(raw);
    private final AgentTurnBudget budget = new AgentTurnBudget(new AgentExecutionProperties(12, 2));

    private Prompt prompt(String text) {
        return new Prompt(List.of(new UserMessage(text)), ToolCallingChatOptions.builder()
                .toolContext(Map.of(ToolContextKeys.EXECUTION_BUDGET, budget)).build());
    }

    private String text(List<ChatResponse> responses) {
        return responses.stream().map(response -> response.getResult().getOutput().getText())
                .reduce("", String::concat);
    }

    @Test
    void synchronousPathAlsoRepairsAndPreservesOriginalRequest() {
        raw.enqueueText("已创建日程");
        raw.enqueueToolCall("createCalendarEvent", "{}");
        assertThat(guard.call(prompt("明天晚上19点开会")).hasToolCalls()).isTrue();
        assertThat(raw.receivedInstructions()).hasSize(2);
        assertThat(raw.receivedInstructions().get(1).get(0).getText()).isEqualTo("明天晚上19点开会");
    }

    @Test
    void readsFailuresAndPendingApprovalsAreNotSuccessfulWriteEvidence() {
        budget.recordWrite("findCalendarEvents", ToolExecutionResult.ok("找到", null));
        budget.recordWrite("createCalendarEvent", ToolExecutionResult.error("失败"));
        budget.recordWrite("createCalendarEvent", new ToolExecutionResult("REQUIRES_CONFIRMATION", "待确认", null, "id"));
        raw.enqueueText("已创建日程");
        raw.enqueueText("已经成功创建日程");
        assertThat(text(guard.stream(prompt("明天晚上开会")).collectList().block())).contains("尚未创建");
        assertThat(raw.receivedInstructions()).hasSize(2);
    }

    @Test
    void committedCreationIsNotRetriedAndNewTurnDoesNotReuseEvidence() {
        budget.recordWrite("createCalendarEvent", ToolExecutionResult.ok("创建成功", null));
        raw.enqueueText("已创建日程");
        assertThat(text(guard.stream(prompt("明天晚上开会")).collectList().block())).isEqualTo("已创建日程");
        assertThat(raw.receivedInstructions()).hasSize(1);
        assertThat(new AgentTurnBudget(new AgentExecutionProperties(12, 2)).hasSuccessfulWrite("createCalendarEvent")).isFalse();
    }

    @Test
    void taskCreationReadOnlyQueriesAndClarifyingQuestionsRemainUnchanged() {
        for (String input : List.of("创建明天的待办任务", "查询明天的日程", "帮我看看明天有哪些会议", "明天的会议已经添加了吗", "你好")) {
            raw.enqueueText("已创建任务");
            assertThat(text(guard.stream(prompt(input)).collectList().block())).isEqualTo("已创建任务");
        }
        raw.enqueueText("你指的是明天吗？");
        assertThat(text(guard.stream(prompt("晚上19点开会")).collectList().block())).isEqualTo("你指的是明天吗？");
        assertThat(raw.receivedInstructions()).hasSize(6);
    }

    @Test void synchronousRepairCannotRepeatAfterBudgetIsSpent() {
        budget.claimCalendarRepair();raw.enqueueText("已创建日程");
        assertThat(guard.call(prompt("明天开会")).getResult().getOutput().getText()).contains("尚未创建");
        raw.enqueueText("已创建日程");assertThat(text(guard.stream(prompt("明天开会")).collectList().block())).contains("尚未创建");
        assertThat(raw.receivedInstructions()).hasSize(2);
    }
    @Test void emptyNonUserAndUnbudgetedPromptsCannotTriggerCalendarWrites() {
        for(var p:List.of(new Prompt("明天开会"),new Prompt(List.of(new org.springframework.ai.chat.messages.SystemMessage("明天开会")),prompt("x").getOptions()),
                new Prompt(List.of(new UserMessage("明天开会"),new UserMessage("创建待办任务")),prompt("x").getOptions()))) {
            raw.enqueueText("已创建日程");assertThat(guard.call(p).getResult().getOutput().getText()).isEqualTo("已创建日程");
        }
        assertThat(guard.getOptions()).isEqualTo(raw.getOptions());
    }
    @Test void successfulRepairAndEmptyChunksRetainOriginalResponse() {
        raw.enqueueText("已创建日程");raw.enqueueText("请告诉我日期");
        assertThat(guard.call(prompt("明天开会")).getResult().getOutput().getText()).isEqualTo("请告诉我日期");
        var delegate=org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.mockito.Mockito.when(delegate.stream(org.mockito.ArgumentMatchers.any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.empty());
        assertThat(new CalendarWriteGuardChatModel(delegate).stream(prompt("明天开会")).collectList().block()).isEmpty();
        org.mockito.Mockito.when(delegate.call(org.mockito.ArgumentMatchers.any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        assertThat(new CalendarWriteGuardChatModel(delegate).call(prompt("明天开会")).getResults()).isEmpty();
    }
}
