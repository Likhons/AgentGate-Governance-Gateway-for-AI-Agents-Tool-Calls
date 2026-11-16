-- ============================================================================
-- V2__init_identity_and_policies.sql
-- Schema for Agent OAuth2 Identity (FR2, FR8) and Versioned Policies (FR3, FR9)
-- ============================================================================

CREATE TABLE agent_clients (
    id                 UUID PRIMARY KEY,
    client_id          VARCHAR(100) NOT NULL UNIQUE,
    client_secret_hash VARCHAR(255) NOT NULL,
    agent_name         VARCHAR(150) NOT NULL,
    owning_team        VARCHAR(100) NOT NULL,
    clearance_level    VARCHAR(30)  NOT NULL CHECK (clearance_level IN ('NONE', 'INTERNAL', 'FULL_PII')),
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('ENABLED', 'DISABLED')),
    scopes             VARCHAR(255) NOT NULL DEFAULT 'mcp:invoke',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_agent_clients_client_id ON agent_clients (client_id);
CREATE INDEX idx_agent_clients_status ON agent_clients (status);

CREATE TABLE policy_rules (
    id              UUID PRIMARY KEY,
    rule_name       VARCHAR(140) NOT NULL,
    version         INTEGER      NOT NULL DEFAULT 1,
    priority        INTEGER      NOT NULL DEFAULT 100,
    agent_pattern   VARCHAR(120) NOT NULL,
    tool_pattern    VARCHAR(120) NOT NULL,
    effect          VARCHAR(16)  NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),
    conditions_json TEXT         NOT NULL DEFAULT '[]',
    dry_run         BOOLEAN      NOT NULL DEFAULT FALSE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    description     TEXT,
    created_by      VARCHAR(100) NOT NULL DEFAULT 'admin',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_policy_rule_name_version UNIQUE (rule_name, version)
);

CREATE INDEX idx_policy_rules_active_priority ON policy_rules (active, priority ASC);
