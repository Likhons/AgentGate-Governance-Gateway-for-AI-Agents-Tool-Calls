-- ============================================================================
-- V6__update_modulith_event_publication.sql
-- Align Spring Modulith 2.0 event publication & archive schema with Hibernate validate
-- ============================================================================

ALTER TABLE event_publication
    ADD COLUMN IF NOT EXISTS status VARCHAR(64),
    ADD COLUMN IF NOT EXISTS completion_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_resubmission_date TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS event_publication_archive (
    id                     UUID NOT NULL PRIMARY KEY,
    listener_id            VARCHAR(512) NOT NULL,
    event_type             VARCHAR(512) NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMPTZ NOT NULL,
    completion_date        TIMESTAMPTZ,
    status                 VARCHAR(64),
    completion_attempts    INTEGER NOT NULL DEFAULT 0,
    last_resubmission_date TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_event_pub_archive_completion ON event_publication_archive (completion_date);
