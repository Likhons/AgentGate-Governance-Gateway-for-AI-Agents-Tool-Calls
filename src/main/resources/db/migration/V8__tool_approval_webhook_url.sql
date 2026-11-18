-- ============================================================================
-- V8__tool_approval_webhook_url.sql
-- FR4: Approver webhooks are configured per tool by an administrator, never by
-- the calling agent, so a prompt-injected agent cannot redirect approval
-- notifications (or their arguments) to a host of its choosing.
-- ============================================================================

ALTER TABLE registered_tools
    ADD COLUMN approval_webhook_url VARCHAR(512);
