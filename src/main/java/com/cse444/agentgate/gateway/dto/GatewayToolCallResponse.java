package com.cse444.agentgate.gateway.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Structured response returned to the AI Agent for every governed tool call (FR1-FR10).
 */
public record GatewayToolCallResponse(
        UUID callId,
        String traceId,
        String toolName,
        String outcome,
        String policyRuleName,
        int policyRuleVersion,
        UUID approvalId,
        String approvalState,
        String redactedResultPayload,
        long gatewayLatencyMs,
        Instant completedAt
) {}
