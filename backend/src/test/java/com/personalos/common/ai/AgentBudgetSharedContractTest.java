package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AgentBudgetSharedContractTest {
    @Test
    void sharedExecutionBudgetPreservesSafetyBoundaries() throws Exception {
        var cases = new ObjectMapper().readTree(Files.readString(
                Path.of("../testdata/agent-rag/execution-budget.json"))).path("cases");
        assertThat(cases.size()).isPositive();
        for (var scenario : cases) {
            var budget = new AgentTurnBudget(new AgentExecutionProperties(
                    scenario.path("models").asInt(), scenario.path("failures").asInt()));
            for (var step : scenario.path("steps")) {
                String name = step.path("name").asText();
                String fingerprint = name + ":" + step.path("digest").asText();
                switch (step.path("op").asText()) {
                    case "model", "tool" -> {
                        Runnable action = step.path("op").asText().equals("model")
                                ? budget::beforeModel : () -> budget.beforeTool(fingerprint);
                        if (step.path("allowed").asBoolean()) {
                            assertThatCode(action::run).as(scenario.path("name").asText()).doesNotThrowAnyException();
                        } else {
                            assertThatThrownBy(action::run).as(scenario.path("name").asText())
                                    .isInstanceOf(BusinessException.class)
                                    .extracting(e -> ((BusinessException) e).getErrorCode())
                                    .isEqualTo(ErrorCode.AGENT_EXECUTION_LIMIT);
                        }
                    }
                    case "record" -> {
                        var result = new ToolExecutionResult(step.path("status").asText(), "synthetic", null, null);
                        budget.record(fingerprint, result);
                        if (step.path("write").asBoolean()) budget.recordWrite(name, result);
                    }
                    case "has_write" -> assertThat(budget.hasSuccessfulWrite(name))
                            .as(scenario.path("name").asText()).isEqualTo(step.path("expected").asBoolean());
                    default -> throw new AssertionError("Unknown contract operation: " + step);
                }
            }
        }
    }
}
