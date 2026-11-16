package com.cse444.agentgate.audit.dto;

import com.cse444.agentgate.audit.domain.AuditLogEntry;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO representing a single hash-chained audit record (FR7, QA3).
 */
public record AuditLogResponse(
        long sequenceNumber,
        UUID id,
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
        Instant occurredAt,
        String previousHash,
        String entryHash
) {
    public static AuditLogResponse from(AuditLogEntry entry) {
        return new AuditLogResponse(
                entry.getSequenceNumber(),
                entry.getId(),
                entry.getTraceId(),
                entry.getAgentClientId(),
                entry.getDelegatedUserId(),
                entry.getToolName(),
                entry.getRedactedArgumentsJson(),
                entry.getPolicyRuleName(),
                entry.getPolicyRuleVersion(),
                entry.getApprovalId(),
                entry.getApprovalState(),
                entry.getOutcome(),
                entry.getLatencyMs(),
                entry.getOccurredAt(),
                entry.getPreviousHash(),
                entry.getEntryHash()
        );
    }
}
