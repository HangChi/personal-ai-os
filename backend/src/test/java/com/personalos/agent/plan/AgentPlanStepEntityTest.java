package com.personalos.agent.plan;

import static org.assertj.core.api.Assertions.*;

import com.personalos.agent.plan.entity.AgentPlanStepEntity;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AgentPlanStepEntityTest {
    static Stream<Arguments> receipts() {
        return Stream.of(Arguments.of(null, null), Arguments.of("", ""), Arguments.of("回执", "回执"),
                Arguments.of("界".repeat(400), "界".repeat(400)),
                Arguments.of("界".repeat(401), "界".repeat(400) + "…"),
                Arguments.of("😀".repeat(200), "😀".repeat(200)),
                Arguments.of("😀".repeat(201), "😀".repeat(200) + "…"));
    }

    @ParameterizedTest
    @MethodSource("receipts")
    void firstReceiptPreservesToolNameAndUsesUtf16StorageLimit(String text, String expected) {
        var step = new AgentPlanStepEntity(UUID.randomUUID(), 1, "核对任务", null);
        step.bindTool("findTasks", text, 400);
        assertThat(step.getToolName()).isEqualTo("findTasks");
        assertThat(step.getToolResult()).isEqualTo(expected);
    }

    @Test
    void laterReceiptsAppendWithoutReplacingFirstToolAndHistoryIsBounded() {
        for (String initial : new String[]{null, "", "  ", "找到任务"}) {
            var step = new AgentPlanStepEntity(UUID.randomUUID(), 1, "核对任务", null);
            step.bindTool("findTasks", initial, 400);
            step.bindTool("updateTask", "已更新", 400);
            String expected = initial == null || initial.isBlank() ? "updateTask：已更新"
                    : initial + (char) 10 + "updateTask：已更新";
            assertThat(step.getToolName()).isEqualTo("findTasks");
            assertThat(step.getToolResult()).isEqualTo(expected);
        }
        var step = new AgentPlanStepEntity(UUID.randomUUID(), 1, "核对任务", null);
        step.bindTool("findTasks", "甲".repeat(400), 400);
        step.bindTool("updateTask", "乙".repeat(400), 400);
        assertThat(step.getToolName()).isEqualTo("findTasks");
        assertThat(step.getToolResult()).isEqualTo("甲".repeat(400) + (char) 10 + "updateTask：" + "乙".repeat(388) + "…");
    }
}
