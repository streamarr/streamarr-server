CREATE TABLE library_deletion_intent
(
    library_id    UUID                     NOT NULL,
    filepath_uri  TEXT                     NOT NULL,
    requested_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT library_deletion_intent_pkey PRIMARY KEY (library_id),
    CONSTRAINT fk_library_deletion_intent_library FOREIGN KEY (library_id)
        REFERENCES library (id) ON DELETE CASCADE
);

CREATE TABLE media_file_deletion_intent
(
    media_file_id UUID                     NOT NULL,
    requested_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT media_file_deletion_intent_pkey PRIMARY KEY (media_file_id),
    CONSTRAINT fk_media_file_deletion_intent_media_file FOREIGN KEY (media_file_id)
        REFERENCES media_file (id) ON DELETE CASCADE
);

CREATE INDEX idx_library_deletion_intent_requested
    ON library_deletion_intent (requested_at, library_id);
CREATE INDEX idx_media_file_deletion_intent_requested
    ON media_file_deletion_intent (requested_at, media_file_id);

ALTER TABLE stream_session
    ADD CONSTRAINT fk_stream_session_media_file FOREIGN KEY (media_file_id)
        REFERENCES media_file (id) ON DELETE RESTRICT NOT VALID;
