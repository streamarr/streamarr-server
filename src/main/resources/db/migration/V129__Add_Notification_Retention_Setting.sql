-- How long the server keeps Notification history, the news that its recipient read and the requests
-- that resolved, after its latest event (ADR 0063). Unread news and waiting requests stay.
ALTER TABLE server_settings
    ADD COLUMN notification_retention_days INTEGER NOT NULL DEFAULT 90,
    ADD CONSTRAINT chk_server_settings_notification_retention_days
        CHECK (notification_retention_days BETWEEN 1 AND 365);
