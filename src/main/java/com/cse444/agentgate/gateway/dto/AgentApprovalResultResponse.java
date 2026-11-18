package com.cse444.agentgate.gateway.dto;

import com.cse444.agentgate.approval.dto.ApprovalRequestResponse;
import com.cse444.agentgate.shared.domain.ApprovalExecutionStatus;
import com.cse444.agentgate.shared.domain.ApprovalStateType;

import java.time.Instant;
import java.util.UUID;

/**
 * Current or final result of a paused high-risk tool call, as seen by the agent that made it (FR4).
 * {@code resultStatus} is one of {@code PENDING}, {@code EXECUTED}, {@code EXECUTION_FAILED},
 * {@code REJECTED}, {@code TIMED_OUT} or {@code CANCELLED_BY_KILL_SWITCH}; once {@code terminal}
 * is true the result never changes, so the agent can stop polling.
 */
public record AgentApprovalResultResponse(
        UUID approvalId,
        String toolName,
        ApprovalStateType approvalState,
        String resultStatus,
        boolean terminal,
        String message,
        String decisionNote,
        String resultPayload,
        Instant expiresAt,
        Instant decidedAt
) {

    private record Outcome(String resultStatus, String message) {}

    public static AgentApprovalResultResponse from(ApprovalRequestResponse approval) {
        ApprovalStateType state = approval.state();
        boolean executed = state == ApprovalStateType.APPROVED || state == ApprovalStateType.APPROVED_EDITED;
        Outcome outcome = switch (state) {
            case PENDING -> new Outcome("PENDING",
                    "Waiting for a human approver; poll again. Unanswered requests are rejected at expiresAt.");
            case APPROVED, APPROVED_EDITED -> approval.executionStatus() == ApprovalExecutionStatus.FAILED
                    ? new Outcome("EXECUTION_FAILED",
                            "Approved, but the downstream tool call failed; see resultPayload for the error.")
                    : new Outcome("EXECUTED", state == ApprovalStateType.APPROVED_EDITED
                            ? "Approved with arguments edited by the approver, then executed."
                            : "Approved and executed.");
            case REJECTED -> new Outcome("REJECTED", "Rejected by a human approver; the tool was not called.");
            case TIMED_OUT -> new Outcome("TIMED_OUT",
                    "No approver answered before the timeout, so the call was rejected; the tool was not called.");
            case CANCELLED_BY_KILL_SWITCH -> new Outcome("CANCELLED_BY_KILL_SWITCH",
                    "Cancelled because an administrator activated a kill switch; the tool was not called.");
        };

        return new AgentApprovalResultResponse(
                approval.id(),
                approval.toolName(),
                state,
                outcome.resultStatus(),
                state != ApprovalStateType.PENDING,
                outcome.message(),
                approval.decisionNote(),
                executed ? approval.executionResultPayload() : null,
                approval.expiresAt(),
                approval.decidedAt()
        );
    }
}
