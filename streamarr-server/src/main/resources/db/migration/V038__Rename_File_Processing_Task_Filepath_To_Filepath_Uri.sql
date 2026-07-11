ALTER TABLE file_processing_task RENAME COLUMN filepath TO filepath_uri;

DROP INDEX IF EXISTS file_processing_task_filepath_active_idx;
CREATE UNIQUE INDEX file_processing_task_filepath_uri_active_idx
    ON file_processing_task (filepath_uri)
    WHERE status IN ('PENDING', 'PROCESSING');
