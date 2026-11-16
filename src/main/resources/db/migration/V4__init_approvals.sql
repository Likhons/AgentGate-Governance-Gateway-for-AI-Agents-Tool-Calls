-- ============================================================================
-- V4__init_approvals.sql
-- Schema for Human-in-the-Loop Approval State Machine (FR4, FR8)
-- ============================================================================

CREATE TABLE approval_requests (
    id                       UUID PRIMARY KEY,
    agent_client_id          VARCHAR(100) NOT NULL,
    delegated_user_id        VARCHAR(120),
    tool_name                VARCHAR(120) NOT NULL,
    write_operation          BOOLEAN      NOT NULL DEFAULT FALSE,
    original_arguments_json  TEXT         NOT NULL,
    effective_arguments_json TEXT         NOT NULL,
    policy_rule_name         VARCHAR(140) NOT NULL,
    policy_rule_version      INTEGER      NOT NULL DEFAULT 1,
    state                    VARCHAR(32)  NOT NULL CHECK (state IN (
        'PENDING',
        'APPROVED',
        'APPROVED_EDITED',
        'REJECTED',
        'TIMED_OUT',
        'CANCELLED_BY_KILL_SWITCH'
    )),
    approver_id              VARCHAR(120),
    decision_note            TEXT,
    execution_result_payload TEXT,
    webhook_url              VARCHAR(512),
    expires_at               TIMESTAMPTZ  NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    decided_at               TIMESTAMPTZ,
    version                  BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_approval_requests_state_expires ON approval_requests (state, expires_at);
CREATE INDEX idx_approval_requests_agent ON approval_requests (agent_client_id, state);
CREATE INDEX idx_approval_requests_tool ON approval_requests (tool_name, state);
