package com.personalos.agent.interaction.dto;

import com.personalos.chat.dto.StreamChatRequest;

public record AgentFollowup(String id, String runId, String conversationId, String mode,
                            StreamChatRequest request, String status) { }
