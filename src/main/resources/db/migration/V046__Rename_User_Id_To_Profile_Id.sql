ALTER TABLE session_progress
    RENAME COLUMN user_id TO profile_id;
ALTER INDEX idx_session_progress_user_id RENAME TO idx_session_progress_profile_id;

ALTER TABLE watch_history
    RENAME COLUMN user_id TO profile_id;
ALTER TABLE watch_history
    RENAME CONSTRAINT uq_watch_history_user_collectable_watched
        TO uq_watch_history_profile_collectable_watched;
ALTER INDEX idx_watch_history_user_collectable RENAME TO idx_watch_history_profile_collectable;
