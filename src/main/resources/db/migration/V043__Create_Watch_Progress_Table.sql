CREATE TABLE watch_progress
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    user_id          UUID                     NOT NULL,
    media_file_id    UUID                     NOT NULL,
    position_seconds INT                      NOT NULL DEFAULT 0,
    percent_complete DOUBLE PRECISION         NOT NULL DEFAULT 0.0,
    duration_seconds INT                      NOT NULL,
    last_played_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT watch_progress_pkey PRIMARY KEY (id),
    CONSTRAINT fk_watch_progress_media_file FOREIGN KEY (media_file_id)
        REFERENCES media_file (id) ON DELETE CASCADE,
    CONSTRAINT uq_watch_progress_user_media_file UNIQUE (user_id, media_file_id)
);

CREATE INDEX idx_watch_progress_user_id ON watch_progress (user_id);
CREATE INDEX idx_watch_progress_media_file_id ON watch_progress (media_file_id);
CREATE INDEX idx_watch_progress_last_played ON watch_progress (user_id, last_played_at);
