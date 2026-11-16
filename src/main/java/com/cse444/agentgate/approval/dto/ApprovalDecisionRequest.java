package com.cse444.agentgate.approval.dto;

import java.util.Map;

/**
 * Request payload for an approver to approve, edit arguments & approve, or reject a pending request (FR4).
 */
public record ApprovalDecisionRequest(
        String note,
        Map<String, Object> editedArguments
) {}
