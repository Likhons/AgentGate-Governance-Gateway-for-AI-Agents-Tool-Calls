package com.cse444.agentgate.approval.dto;

import com.cse444.agentgate.approval.domain.ApprovalRequest;
import com.cse444.agentgate.shared.domain.ApprovalExecutionStatus;
import com.cse444.agentgate.shared.domain.ApprovalStateType;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO representing a pending or finalized approval request (FR4).
 */
public record ApprovalRequestResponse(
        UUID id,
        String agentClientId,
        String delegatedUserId,
        String toolName,
        boolean writeOperation,
        String originalArgumentsJson,
        String effectiveArgumentsJson,
        String policyRuleName,
        int policyRuleVersion,
        ApprovalStateType state,
        String approverId,
        String decisionNote,
        String executionResultPayload,
        ApprovalExecutionStatus executionStatus,
        Instant expiresAt,
        Instant createdAt,
        Instant decidedAt
) {
    public static ApprovalRequestResponse from(ApprovalRequest entity) {
        return new ApprovalRequestResponse(
                entity.getId(),
                entity.getAgentClientId(),
                entity.getDelegatedUserId(),
                entity.getToolName(),
                entity.isWriteOperation(),
                entity.getOriginalArgumentsJson(),
                entity.getEffectiveArgumentsJson(),
                entity.getPolicyRuleName(),
                entity.getPolicyRuleVersion(),
                entity.getState(),
                entity.getApproverId(),
                entity.getDecisionNote(),
                entity.getExecutionResultPayload(),
                entity.getExecutionStatus(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getDecidedAt()
        );
    }
}
