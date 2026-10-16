ALTER TABLE media_file_container_info
    DROP CONSTRAINT media_file_container_info_probe_error_check,
    ADD CONSTRAINT media_file_container_info_probe_error_check
        CHECK (probe_error IN ('INVALID_MEDIA', 'NO_VIDEO_STREAM', 'RESULT_TOO_LARGE'));
