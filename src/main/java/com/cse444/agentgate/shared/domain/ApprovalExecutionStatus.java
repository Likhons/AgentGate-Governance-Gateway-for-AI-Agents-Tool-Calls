package com.cse444.agentgate.shared.domain;

/**
 * Outcome of the downstream call that runs after a human approves a high-risk tool call (FR4).
 * {@code null} on an approval request means the call has not been executed (pending, rejected,
 * timed out or cancelled).
 */
public enum ApprovalExecutionStatus {
    SUCCEEDED,
    FAILED
}
