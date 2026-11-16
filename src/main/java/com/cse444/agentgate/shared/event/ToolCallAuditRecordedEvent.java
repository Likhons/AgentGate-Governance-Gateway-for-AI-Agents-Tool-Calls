package com.cse444.agentgate.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published on every governed tool call (FR7).
 * Design Pattern: Observer / Event-Driven Architecture.
 * Consumed by the Audit module via @TransactionalEventListener so the Audit module
 * remains 100% decoupled from Gateway, Policy, Guard, and Approval modules.
 */
public record ToolCallAuditRecordedEvent(
        UUID eventId,
        String traceId,
        String agentClientId,
        String delegatedUserId,
        String toolName,
        String redactedArgumentsJson,
        String policyRuleName,
        int policyRuleVersion,
        UUID approvalId,
        String approvalState,
        String outcome,
        long latencyMs,
        Instant occurredAt
) {}
