package com.cse444.agentgate.shared.domain;

/**
 * States of the Human-in-the-Loop Approval State Machine (FR4, FR8).
 */
public enum ApprovalStateType {
    PENDING,
    APPROVED,
    APPROVED_EDITED,
    REJECTED,
    TIMED_OUT,
    CANCELLED_BY_KILL_SWITCH
}
