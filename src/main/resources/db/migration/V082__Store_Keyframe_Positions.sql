-- An outcome from a probe version that reports no keyframes keeps keyframe_status NULL.
ALTER TABLE media_file_container_info
    ADD COLUMN keyframe_status TEXT
        CHECK (keyframe_status IN ('KNOWN', 'PACKET_SCAN_REQUIRED', 'UNAVAILABLE')),
    ADD COLUMN keyframe_packet_scan_reason TEXT
        CHECK (keyframe_packet_scan_reason IN (
            'NO_CONTAINER_INDEX', 'INCOMPLETE_CONTAINER_INDEX', 'UNREADABLE_LAST_INDEXED_KEYFRAME')),
    ADD COLUMN keyframes_unavailable_reason TEXT
        CHECK (keyframes_unavailable_reason IN ('KEYFRAME_WITHOUT_TIMESTAMP', 'NO_KEYFRAMES')),
    ADD CONSTRAINT failed_probe_has_no_keyframe_status
        CHECK (probe_error IS NULL OR keyframe_status IS NULL),
    ADD CONSTRAINT packet_scan_reason_only_when_packet_scan_required
        CHECK ((keyframe_status IS NOT DISTINCT FROM 'PACKET_SCAN_REQUIRED')
            = (keyframe_packet_scan_reason IS NOT NULL)),
    ADD CONSTRAINT unavailable_reason_only_when_keyframes_unavailable
        CHECK ((keyframe_status IS NOT DISTINCT FROM 'UNAVAILABLE')
            = (keyframes_unavailable_reason IS NOT NULL)),
    ADD CONSTRAINT media_file_container_info_keyframe_status_key
        UNIQUE (media_file_id, keyframe_status);

-- encoded_times holds the presentation and decode times in KeyframePositionsCodec's format.
-- The foreign key admits a positions row only while its outcome's keyframe_status is KNOWN.
CREATE TABLE media_file_keyframe_positions
(
    media_file_id UUID PRIMARY KEY,
    keyframe_status TEXT GENERATED ALWAYS AS ('KNOWN') STORED,
    stream_index INTEGER NOT NULL CHECK (stream_index >= 0),
    time_base_numerator INTEGER NOT NULL CHECK (time_base_numerator > 0),
    time_base_denominator INTEGER NOT NULL CHECK (time_base_denominator > 0),
    keyframe_count INTEGER NOT NULL CHECK (keyframe_count > 0),
    encoded_times BYTEA NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('CONTAINER_INDEX', 'PACKET_SCAN')),
    CONSTRAINT keyframe_positions_require_known_status
        FOREIGN KEY (media_file_id, keyframe_status)
            REFERENCES media_file_container_info (media_file_id, keyframe_status)
            ON DELETE CASCADE
);
