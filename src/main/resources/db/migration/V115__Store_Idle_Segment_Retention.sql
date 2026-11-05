-- How long the server keeps a stream session, and its segments, after the last request for it.
ALTER TABLE server_settings
    ADD COLUMN idle_segment_retention_minutes INTEGER NOT NULL DEFAULT 60,
    ADD CONSTRAINT chk_server_settings_idle_segment_retention_minutes
        CHECK (idle_segment_retention_minutes BETWEEN 5 AND 10080);
