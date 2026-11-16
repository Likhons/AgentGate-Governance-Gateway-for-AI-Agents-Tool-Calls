package com.cse444.agentgate.gateway.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Unified Tool Call Request sent by an AI Agent through the AgentGate front door (FR1-FR10).
 * Design Pattern: Command Pattern — encapsulates a governed tool invocation request.
 */
public record GatewayToolCallRequest(
        @NotBlank
        String toolName,

        Map<String, Object> arguments,

        String delegatedUserId,

        String approvalWebhookUrl
) {}
