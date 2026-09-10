ALTER TABLE file_processing_task
    ADD COLUMN media_file_id UUID REFERENCES media_file (id) ON DELETE CASCADE,
    ADD COLUMN source_size BIGINT,
    ADD COLUMN source_modified_epoch_second BIGINT,
    ADD COLUMN source_modified_nanos INTEGER,
    ADD COLUMN probe_version INTEGER,
    ADD COLUMN claim_id UUID,
    ADD COLUMN retry_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    ADD CONSTRAINT file_processing_task_probe_inputs_check CHECK (
        (media_file_id IS NULL AND source_size IS NULL
            AND source_modified_epoch_second IS NULL AND source_modified_nanos IS NULL
            AND probe_version IS NULL)
        OR (media_file_id IS NOT NULL AND source_size IS NOT NULL AND source_size >= 0
            AND source_modified_epoch_second IS NOT NULL
            AND source_modified_nanos IS NOT NULL AND source_modified_nanos BETWEEN 0 AND 999999999
            AND probe_version IS NOT NULL AND probe_version > 0)
    );
