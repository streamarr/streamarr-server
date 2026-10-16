-- A stream session completes its title at most once. A stopped report that the client retries
-- after losing the reply then saves no second completion. Manual marks carry no stream session.
ALTER TABLE watch_history
    ADD COLUMN stream_session_id UUID;

ALTER TABLE watch_history
    ADD CONSTRAINT uq_watch_history_stream_session UNIQUE (stream_session_id);
