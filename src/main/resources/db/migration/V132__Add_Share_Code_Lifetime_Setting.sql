-- How long a share code stays live after it is issued (ADR 0024 §Share codes): seven days by
-- default, between one hour and thirty days. A ServerAdmin sets it; a change applies to codes issued
-- after it.
ALTER TABLE server_settings
    ADD COLUMN share_code_lifetime_hours INTEGER NOT NULL DEFAULT 168,
    ADD CONSTRAINT chk_server_settings_share_code_lifetime_hours
        CHECK (share_code_lifetime_hours BETWEEN 1 AND 720);
