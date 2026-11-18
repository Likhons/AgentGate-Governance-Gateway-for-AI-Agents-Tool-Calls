-- ============================================================================
-- V9__limit_breach_alerts.sql
-- FR6: "A breach blocks further calls and raises an alert". Each alert is recorded
-- here (at most one per agent, tool and breach type per cooldown window) and sent to
-- the operations webhook when one is configured.
-- ============================================================================

CREATE TABLE limit_breach_alerts (
    id                UUID PRIMARY KEY,
    agent_client_id   VARCHAR(100) NOT NULL,
    delegated_user_id VARCHAR(120),
    tool_name         VARCHAR(120) NOT NULL,
    breach_type       VARCHAR(40)  NOT NULL CHECK (breach_type IN (
        'PER_MINUTE_RATE_LIMIT',
        'DAILY_QUOTA',
        'WRITE_BLAST_RADIUS_CAP'
    )),
    configured_limit  INTEGER      NOT NULL,
    message           TEXT         NOT NULL,
    webhook_url       VARCHAR(512),
    raised_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_limit_breach_alerts_raised_at ON limit_breach_alerts (raised_at DESC);
CREATE INDEX idx_limit_breach_alerts_agent_tool ON limit_breach_alerts (agent_client_id, tool_name, raised_at DESC);
