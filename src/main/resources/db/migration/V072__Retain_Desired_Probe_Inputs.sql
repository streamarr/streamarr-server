CREATE TABLE media_file_probe_task_request
(
    media_file_id UUID PRIMARY KEY REFERENCES media_file (id) ON DELETE CASCADE,
    source_size BIGINT NOT NULL CHECK (source_size >= 0),
    source_modified_epoch_second BIGINT NOT NULL,
    source_modified_nanos INTEGER NOT NULL CHECK (source_modified_nanos BETWEEN 0 AND 999999999),
    probe_version INTEGER NOT NULL CHECK (probe_version > 0)
);
