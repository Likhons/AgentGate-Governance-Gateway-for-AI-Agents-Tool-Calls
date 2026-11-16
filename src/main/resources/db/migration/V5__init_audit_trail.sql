-- ============================================================================
-- V5__init_audit_trail.sql
-- Schema for Tamper-Evident Hash-Chained Audit Trail (FR7, QA3)
-- ============================================================================

CREATE TABLE audit_log_entries (
    sequence_number         BIGSERIAL    NOT NULL UNIQUE,
    id                      UUID         PRIMARY KEY,
    trace_id                VARCHAR(64)  NOT NULL,
    agent_client_id         VARCHAR(100) NOT NULL,
    delegated_user_id       VARCHAR(120),
    tool_name               VARCHAR(120) NOT NULL,
    redacted_arguments_json TEXT         NOT NULL,
    policy_rule_name        VARCHAR(140) NOT NULL,
    policy_rule_version     INTEGER      NOT NULL DEFAULT 0,
    approval_id             UUID,
    approval_state          VARCHAR(32),
    outcome                 VARCHAR(40)  NOT NULL,
    latency_ms              BIGINT       NOT NULL,
    occurred_at             TIMESTAMPTZ  NOT NULL,
    previous_hash           VARCHAR(64)  NOT NULL,
    entry_hash              VARCHAR(64)  NOT NULL
);

CREATE INDEX idx_audit_log_sequence ON audit_log_entries (sequence_number ASC);
CREATE INDEX idx_audit_log_agent_time ON audit_log_entries (agent_client_id, occurred_at DESC);
CREATE INDEX idx_audit_log_tool_time ON audit_log_entries (tool_name, occurred_at DESC);
CREATE INDEX idx_audit_log_user_time ON audit_log_entries (delegated_user_id, occurred_at DESC);
CREATE INDEX idx_audit_log_outcome ON audit_log_entries (outcome, occurred_at DESC);
