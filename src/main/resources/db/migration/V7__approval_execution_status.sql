-- ============================================================================
-- V7__approval_execution_status.sql
-- FR4: An approved high-risk call records whether its downstream execution
-- succeeded, so the agent always receives a clear final result instead of a
-- request that rolls back to PENDING when the tool fails.
-- ============================================================================

ALTER TABLE approval_requests
    ADD COLUMN execution_status VARCHAR(16);

ALTER TABLE approval_requests
    ADD CONSTRAINT chk_approval_requests_execution_status
        CHECK (execution_status IS NULL OR execution_status IN ('SUCCEEDED', 'FAILED'));
