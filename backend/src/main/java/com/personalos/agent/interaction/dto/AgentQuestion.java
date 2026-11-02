package com.personalos.agent.interaction.dto;

import java.util.List;

public record AgentQuestion(String id, String runId, String question, List<Option> options,
                            String status, String answer) {
    public record Option(String label, String description) { }
}
