package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.chat.repository.ChatRunRepository;
import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.api.ErrorCode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class ChatFailureRecoveryTest {
    @Test void oneToolFreeClosingRequestProducesUsableOutput() {
        var repository=mock(ChatRunRepository.class);
        when(repository.receipts("r")).thenReturn("createTask [SUCCESS]: 已创建任务");
        var model=mock(ChatModel.class);
        when(model.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("已创建任务；其余查询未完成。")));
        var plain=ChatClient.builder(model).build();
        var result=new ChatFailureRecovery(repository).recover(new AiChatClientProvider.ChatTarget(model,plain,plain),
                "r","整理任务","",ErrorCode.AGENT_EXECUTION_LIMIT).blockLast(Duration.ofSeconds(2));
        assertThat(result.getResult().getOutput().getText()).isEqualTo("已创建任务；其余查询未完成。");
        var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model,times(1)).stream(prompt.capture());
        assertThat(prompt.getValue().getContents()).contains("createTask [SUCCESS]", "不调用工具");
    }
    @Test void failedEmptyAndMaliciousClosingResponsesStillReturnReceipts() {
        var repository=mock(ChatRunRepository.class);
        when(repository.receipts("r")).thenReturn("externalWrite [IN_PROGRESS]: 结果待核实");
        for (var stream:List.of(
                Flux.<ChatResponse>error(new IllegalStateException("upstream-private-secret")),
                Flux.<ChatResponse>empty(),
                Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("bad","function","createTask","{}"))).build())))))) {
            var model=mock(ChatModel.class);
            when(model.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
            when(model.stream(any(Prompt.class))).thenReturn(stream);
            var plain=ChatClient.builder(model).build();
            var result=new ChatFailureRecovery(repository).recover(new AiChatClientProvider.ChatTarget(model,plain,plain),
                    "r","整理任务","已有草稿",ErrorCode.AGENT_EXECUTION_LIMIT).blockLast(Duration.ofSeconds(2));
            assertThat(result.getResult().getOutput().getText()).contains("externalWrite [IN_PROGRESS]","未重试")
                    .doesNotContain("upstream-private-secret");
            verify(model,times(1)).stream(any(Prompt.class));
        }
    }
    @Test void onlyRecoverableFailuresUseAutomaticClosing() {
        assertThat(ChatFailureRecovery.eligible(ErrorCode.AGENT_EXECUTION_LIMIT)).isTrue();
        assertThat(ChatFailureRecovery.eligible(ErrorCode.AI_UPSTREAM_ERROR)).isTrue();
        assertThat(ChatFailureRecovery.eligible(ErrorCode.AI_TIMEOUT)).isTrue();
        assertThat(ChatFailureRecovery.eligible(ErrorCode.VALIDATION_FAILED)).isFalse();
        assertThat(ChatFailureRecovery.eligible(ErrorCode.INTERNAL_ERROR)).isFalse();
        var repository=mock(ChatRunRepository.class);when(repository.receipts(any())).thenThrow(new IllegalStateException());
        assertThat(new ChatFailureRecovery(repository).deterministic("r")).contains("没有可核实");
    }
    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
