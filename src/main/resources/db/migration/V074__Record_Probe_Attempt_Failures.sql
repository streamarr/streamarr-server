-- The latest failed attempt at the requested inputs. The probe keeps retrying, so this is not a
-- terminal probe_error; a new request with different inputs or a stored outcome clears it.
ALTER TABLE media_file_probe_task_request
    ADD COLUMN failure_reason item_result_failure_reason,
    ADD COLUMN failure_detail TEXT,
    ADD COLUMN failed_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT media_file_probe_task_request_failure_complete CHECK (
        (failure_reason IS NULL) = (failure_detail IS NULL)
            AND (failure_reason IS NULL) = (failed_at IS NULL)
    );
