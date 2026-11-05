package com.personalos.chat.dto;

import com.personalos.agent.interaction.dto.AgentQuestion;
import com.personalos.agent.interaction.dto.AgentFollowup;
import java.util.List;

public record ChatRunDto(String id, String conversationId, String status, List<Event> events,
                         AgentQuestion question, List<AgentFollowup> followups, String recoveryMode) {
    public record Event(long id, String name, String payload) { }
}
