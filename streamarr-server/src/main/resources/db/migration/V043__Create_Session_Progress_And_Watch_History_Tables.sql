CREATE TABLE session_progress
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    session_id       UUID                     NOT NULL,
    user_id          UUID                     NOT NULL,
    media_file_id    UUID                     NOT NULL,
    position_seconds INT                      NOT NULL DEFAULT 0,
    percent_complete DOUBLE PRECISION         NOT NULL DEFAULT 0.0,
    duration_seconds INT                      NOT NULL,
    CONSTRAINT session_progress_pkey PRIMARY KEY (id),
    CONSTRAINT fk_session_progress_media_file FOREIGN KEY (media_file_id)
        REFERENCES media_file (id) ON DELETE CASCADE,
    CONSTRAINT uq_session_progress_session UNIQUE (session_id)
);

CREATE INDEX idx_session_progress_user_id ON session_progress (user_id);
CREATE INDEX idx_session_progress_media_file_id ON session_progress (media_file_id);
CREATE INDEX idx_session_progress_resume
    ON session_progress (user_id, media_file_id, last_modified_on DESC);

CREATE TABLE watch_history
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    user_id          UUID                     NOT NULL,
    collectable_id   UUID                     NOT NULL,
    watched_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_seconds INT                      NOT NULL,
    dismissed_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT watch_history_pkey PRIMARY KEY (id),
    CONSTRAINT uq_watch_history_user_collectable_watched
        UNIQUE (user_id, collectable_id, watched_at)
);

CREATE INDEX idx_watch_history_user_collectable
    ON watch_history (user_id, collectable_id, watched_at DESC);
