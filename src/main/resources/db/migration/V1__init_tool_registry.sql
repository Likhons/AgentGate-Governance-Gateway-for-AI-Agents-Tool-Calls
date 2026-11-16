-- ============================================================================
-- V1__init_tool_registry.sql
-- Schema for Tool Registry (FR1), Kill Switch state (FR8), and Spring Modulith Events
-- ============================================================================

CREATE TABLE registered_tools (
    id              UUID PRIMARY KEY,
    name            VARCHAR(120) NOT NULL UNIQUE,
    description     TEXT         NOT NULL,
    input_schema    TEXT         NOT NULL,
    risk_level      VARCHAR(20)  NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    owning_team     VARCHAR(100) NOT NULL,
    tool_type       VARCHAR(20)  NOT NULL CHECK (tool_type IN ('REST', 'MCP')),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('ENABLED', 'DISABLED')),
    write_operation BOOLEAN      NOT NULL DEFAULT FALSE,
    downstream_url  VARCHAR(512) NOT NULL,
    http_method     VARCHAR(16)  NOT NULL DEFAULT 'POST',
    timeout_ms      INTEGER      NOT NULL DEFAULT 3000 CHECK (timeout_ms BETWEEN 100 AND 60000),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_registered_tools_status ON registered_tools (status);
CREATE INDEX idx_registered_tools_risk_level ON registered_tools (risk_level);
CREATE INDEX idx_registered_tools_owning_team ON registered_tools (owning_team);

-- Global kill-switch state table (e.g., instant kill switch for all write tools - FR8)
CREATE TABLE global_kill_switch_state (
    switch_key      VARCHAR(64) PRIMARY KEY,
    active          BOOLEAN     NOT NULL DEFAULT FALSE,
    reason          TEXT,
    updated_by      VARCHAR(120),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO global_kill_switch_state (switch_key, active, reason, updated_by)
VALUES ('ALL_WRITE_TOOLS', FALSE, 'Initial default state', 'SYSTEM');

-- Spring Modulith Transactional Event Publication Registry
CREATE TABLE IF NOT EXISTS event_publication (
    id                     UUID NOT NULL PRIMARY KEY,
    listener_id            VARCHAR(512) NOT NULL,
    event_type             VARCHAR(512) NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMPTZ NOT NULL,
    completion_date        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_event_pub_completion ON event_publication (completion_date);
