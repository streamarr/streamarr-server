-- A packet scan that ends in a terminal media error leaves the file's keyframes unavailable.
ALTER TABLE media_file_container_info
    DROP CONSTRAINT media_file_container_info_keyframes_unavailable_reason_check,
    ADD CONSTRAINT media_file_container_info_keyframes_unavailable_reason_check
        CHECK (keyframes_unavailable_reason IN (
            'KEYFRAME_WITHOUT_TIMESTAMP', 'NO_KEYFRAMES', 'PACKET_SCAN_MEDIA_ERROR'));
