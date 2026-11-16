-- ============================================================================
-- V3__init_limits_and_quotas.sql
-- Schema for per-agent and per-tool Rate Limits, Daily Quotas, and Write Blast-Radius Caps (FR6)
-- ============================================================================

CREATE TABLE agent_tool_limits (
    id                          UUID PRIMARY KEY,
    agent_client_id             VARCHAR(100) NOT NULL,
    tool_name                   VARCHAR(120) NOT NULL,
    requests_per_minute         INTEGER      NOT NULL CHECK (requests_per_minute > 0),
    daily_quota                 INTEGER      NOT NULL CHECK (daily_quota > 0),
    write_blast_radius_per_hour INTEGER      NOT NULL CHECK (write_blast_radius_per_hour > 0),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_agent_tool_limit UNIQUE (agent_client_id, tool_name)
);

CREATE INDEX idx_agent_tool_limits_lookup ON agent_tool_limits (agent_client_id, tool_name);
