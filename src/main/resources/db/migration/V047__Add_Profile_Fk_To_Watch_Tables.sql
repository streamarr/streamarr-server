-- NOT VALID: existing rows may still carry the retired placeholder id on databases that have
-- not run first-time setup (the setup transaction remaps them); all NEW writes are enforced.
-- A later migration runs VALIDATE CONSTRAINT once the placeholder era ends.
ALTER TABLE session_progress
    ADD CONSTRAINT fk_session_progress_profile FOREIGN KEY (profile_id)
        REFERENCES profile (id) ON DELETE CASCADE NOT VALID;

ALTER TABLE watch_history
    ADD CONSTRAINT fk_watch_history_profile FOREIGN KEY (profile_id)
        REFERENCES profile (id) ON DELETE CASCADE NOT VALID;
