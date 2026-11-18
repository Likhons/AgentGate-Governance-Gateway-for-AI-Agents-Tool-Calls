package com.cse444.agentgate.gateway.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Unified Tool Call Request sent by an AI Agent through the AgentGate front door (FR1-FR10).
 * Design Pattern: Command Pattern — encapsulates a governed tool invocation request.
 * The approver webhook is deliberately not part of the request: it is configured by an
 * administrator on the tool (FR4), so an agent cannot redirect approval notifications.
 */
public record GatewayToolCallRequest(
        @NotBlank
        String toolName,

        Map<String, Object> arguments,

        String delegatedUserId
) {}
