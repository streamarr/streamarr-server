-- The server's internet upload speed in megabits per second, which a ServerAdmin enters from a speed
-- test. Stream sessions away from the local network may use 80% of it together. NULL keeps the
-- upload budget off, which is the default.
ALTER TABLE server_settings
    ADD COLUMN internet_upload_megabits_per_second INTEGER,
    ADD CONSTRAINT chk_server_settings_internet_upload_megabits_per_second
        CHECK (internet_upload_megabits_per_second BETWEEN 1 AND 10000);
