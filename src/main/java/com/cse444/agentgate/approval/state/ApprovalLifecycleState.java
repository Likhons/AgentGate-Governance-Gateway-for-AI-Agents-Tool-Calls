package com.cse444.agentgate.approval.state;

import com.cse444.agentgate.approval.domain.ApprovalRequest;
import com.cse444.agentgate.shared.domain.ApprovalStateType;

/**
 * Design Pattern: State Pattern (FR4, FR8).
 * Encapsulates state-specific behavior and legal transitions for a high-risk tool call
 * approval request. Only PENDING requests can transition to APPROVED, APPROVED_EDITED,
 * REJECTED, TIMED_OUT, or CANCELLED_BY_KILL_SWITCH; terminal states reject all mutations.
 */
public sealed interface ApprovalLifecycleState
        permits ApprovalLifecycleState.PendingState, ApprovalLifecycleState.TerminalApprovalState {

    ApprovalStateType stateType();

    void approve(ApprovalRequest request, String approverId, String note);

    void approveWithEditedArguments(ApprovalRequest request, String approverId, String editedArgumentsJson, String note);

    void reject(ApprovalRequest request, String approverId, String reason);

    void timeout(ApprovalRequest request);

    void cancelByKillSwitch(ApprovalRequest request, String reason);

    static ApprovalLifecycleState fromType(ApprovalStateType type) {
        return switch (type) {
            case PENDING -> new PendingState();
            case APPROVED, APPROVED_EDITED, REJECTED, TIMED_OUT, CANCELLED_BY_KILL_SWITCH ->
                    new TerminalApprovalState(type);
        };
    }

    /**
     * Active state waiting for human approver action, timeout, or kill-switch cancellation.
     */
    final class PendingState implements ApprovalLifecycleState {

        @Override
        public ApprovalStateType stateType() {
            return ApprovalStateType.PENDING;
        }

        @Override
        public void approve(ApprovalRequest request, String approverId, String note) {
            request.applyTransition(
                    ApprovalStateType.APPROVED,
                    approverId,
                    request.getOriginalArgumentsJson(),
                    note != null ? note : "Approved by human reviewer"
            );
        }

        @Override
        public void approveWithEditedArguments(
                ApprovalRequest request,
                String approverId,
                String editedArgumentsJson,
                String note
        ) {
            if (editedArgumentsJson == null || editedArgumentsJson.isBlank()) {
                throw new IllegalArgumentException("Edited arguments JSON cannot be blank when approving with edits");
            }
            request.applyTransition(
                    ApprovalStateType.APPROVED_EDITED,
                    approverId,
                    editedArgumentsJson,
                    note != null ? note : "Approved with modified arguments by human reviewer"
            );
        }

        @Override
        public void reject(ApprovalRequest request, String approverId, String reason) {
            request.applyTransition(
                    ApprovalStateType.REJECTED,
                    approverId,
                    request.getOriginalArgumentsJson(),
                    reason != null ? reason : "Rejected by human reviewer"
            );
        }

        @Override
        public void timeout(ApprovalRequest request) {
            request.applyTransition(
                    ApprovalStateType.TIMED_OUT,
                    "SYSTEM_TIMEOUT",
                    request.getOriginalArgumentsJson(),
                    "Approval request timed out without human response (FR4)"
            );
        }

        @Override
        public void cancelByKillSwitch(ApprovalRequest request, String reason) {
            request.applyTransition(
                    ApprovalStateType.CANCELLED_BY_KILL_SWITCH,
                    "KILL_SWITCH",
                    request.getOriginalArgumentsJson(),
                    "Cancelled due to Kill Switch activation: " + reason
            );
        }
    }

    /**
     * Immutable terminal state: prevents re-approving, editing, or modifying finalized approvals.
     */
    record TerminalApprovalState(ApprovalStateType stateType) implements ApprovalLifecycleState {

        @Override
        public void approve(ApprovalRequest request, String approverId, String note) {
            throw illegalTransition("approve");
        }

        @Override
        public void approveWithEditedArguments(
                ApprovalRequest request,
                String approverId,
                String editedArgumentsJson,
                String note
        ) {
            throw illegalTransition("approveWithEditedArguments");
        }

        @Override
        public void reject(ApprovalRequest request, String approverId, String reason) {
            throw illegalTransition("reject");
        }

        @Override
        public void timeout(ApprovalRequest request) {
            throw illegalTransition("timeout");
        }

        @Override
        public void cancelByKillSwitch(ApprovalRequest request, String reason) {
            throw illegalTransition("cancelByKillSwitch");
        }

        private IllegalStateException illegalTransition(String attemptedAction) {
            return new IllegalStateException(
                    "Cannot perform '" + attemptedAction + "' on approval request already in terminal state " + stateType
            );
        }
    }
}
