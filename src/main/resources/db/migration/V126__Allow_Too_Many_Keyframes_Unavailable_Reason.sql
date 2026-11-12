-- A worker reports a file's keyframes as unavailable when the chosen video stream holds more
-- keyframes than the server's keyframe limit (ADR 0062).
--
-- The new list only adds a value, so every existing row satisfies it. NOT VALID skips the scan of
-- the table under ACCESS EXCLUSIVE, and PostgreSQL still checks every insert and update.
ALTER TABLE media_file_container_info
    DROP CONSTRAINT media_file_container_info_keyframes_unavailable_reason_check,
    ADD CONSTRAINT media_file_container_info_keyframes_unavailable_reason_check
        CHECK (keyframes_unavailable_reason IN (
            'KEYFRAME_WITHOUT_TIMESTAMP', 'NO_KEYFRAMES', 'PACKET_SCAN_MEDIA_ERROR',
            'PACKET_SCAN_FAILED', 'TOO_MANY_KEYFRAMES')) NOT VALID;
