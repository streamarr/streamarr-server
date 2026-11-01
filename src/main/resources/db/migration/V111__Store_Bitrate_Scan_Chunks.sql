-- One finished chunk of a bitrate scan (ADR 0054). A chunk runs from its first keyframe interval
-- for keyframe_interval_count intervals of the keyframe positions whose digest it names. The server
-- assembles a measurement only from chunks that follow the stored outcome's snapshot, keyframe
-- positions and the current measurement version, and that tile every keyframe interval exactly.
CREATE TABLE media_file_bitrate_scan_chunk
(
    media_file_id                UUID    NOT NULL REFERENCES media_file (id) ON DELETE CASCADE,
    first_keyframe_interval      INTEGER NOT NULL CHECK (first_keyframe_interval >= 0),
    keyframe_interval_count      INTEGER NOT NULL CHECK (keyframe_interval_count BETWEEN 1 AND 4096),
    source_size                  BIGINT  NOT NULL,
    source_modified_epoch_second BIGINT  NOT NULL,
    source_modified_nanos        INTEGER NOT NULL,
    measurement_version          INTEGER NOT NULL,
    keyframe_positions_digest    BYTEA   NOT NULL,
    PRIMARY KEY (media_file_id, first_keyframe_interval)
);

-- One measured stream of a finished chunk. encoded_keyframe_interval_bytes and
-- encoded_keyframe_interval_packet_counts hold one unsigned 32-bit count for each of the chunk's
-- keyframe intervals in KeyframeIntervalCountsCodec's format. The times count ticks of the stream's
-- time base and are absent when the chunk holds no packet of the stream.
CREATE TABLE media_file_bitrate_scan_chunk_stream
(
    media_file_id                           UUID    NOT NULL,
    first_keyframe_interval                 INTEGER NOT NULL,
    stream_index                            INTEGER NOT NULL CHECK (stream_index >= 0),
    measured_bytes                          BIGINT  NOT NULL CHECK (measured_bytes >= 0),
    packet_count                            BIGINT  NOT NULL CHECK (packet_count >= 0),
    time_base_numerator                     INTEGER NOT NULL CHECK (time_base_numerator > 0),
    time_base_denominator                   INTEGER NOT NULL CHECK (time_base_denominator > 0),
    first_presentation_time                 BIGINT,
    end_time                                BIGINT,
    encoded_keyframe_interval_bytes         BYTEA   NOT NULL,
    encoded_keyframe_interval_packet_counts BYTEA   NOT NULL,
    PRIMARY KEY (media_file_id, first_keyframe_interval, stream_index),
    CONSTRAINT chunk_stream_times_are_both_known_or_both_absent
        CHECK ((first_presentation_time IS NULL) = (end_time IS NULL)),
    FOREIGN KEY (media_file_id, first_keyframe_interval)
        REFERENCES media_file_bitrate_scan_chunk (media_file_id, first_keyframe_interval)
        ON DELETE CASCADE
);

-- A bitrate scan that finds a stream whose packets a copy would change marks the measurement
-- unavailable for the snapshot (ADR 0054).
ALTER TABLE media_file_stream_measurement
    DROP CONSTRAINT media_file_stream_measurement_unavailable_reason_check,
    ADD CONSTRAINT media_file_stream_measurement_unavailable_reason_check
        CHECK (unavailable_reason IN ('FAILED_ATTEMPTS', 'COPY_CHANGES_PACKETS')) NOT VALID;

-- Bitrate scans share the keyframe packet scan table at lower priorities than keyframe packet
-- scans, and claims sort by priority, then execution time. The server also counts the waiting
-- bitrate scans in this table for a metric.
UPDATE keyframe_scan_scheduled_tasks SET priority = 50 WHERE priority IS NULL;
CREATE INDEX keyframe_scan_scheduled_tasks_priority_execution_time_idx
    ON keyframe_scan_scheduled_tasks (priority DESC, execution_time ASC);
