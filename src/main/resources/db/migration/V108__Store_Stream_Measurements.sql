-- positions_digest identifies the keyframe positions that a stream measurement's keyframe intervals
-- follow (ADR 0054). It covers the chosen stream, its time base and the encoded times.
ALTER TABLE media_file_keyframe_positions
    ADD COLUMN positions_digest BYTEA GENERATED ALWAYS AS (
        sha256(int4send(stream_index) || int4send(time_base_numerator)
            || int4send(time_base_denominator) || encoded_times)) STORED;

-- A measurement lives apart from the stored probe outcome, so a new probe version keeps it. The
-- server uses it only while its source snapshot and keyframe positions digest match the stored
-- outcome's and its measurement_version is the server's current one. A source change deletes it.
CREATE TABLE media_file_stream_measurement
(
    media_file_id                UUID PRIMARY KEY REFERENCES media_file (id) ON DELETE CASCADE,
    source_size                  BIGINT  NOT NULL,
    source_modified_epoch_second BIGINT  NOT NULL,
    source_modified_nanos        INTEGER NOT NULL,
    measurement_version          INTEGER NOT NULL,
    keyframe_positions_digest    BYTEA   NOT NULL,
    status                       TEXT    NOT NULL CHECK (status IN ('MEASURED', 'UNAVAILABLE')),
    source                       TEXT CHECK (source IN (
        'SAMPLE_TABLE', 'KEYFRAME_PACKET_SCAN', 'BITRATE_SCAN')),
    unavailable_reason           TEXT CHECK (unavailable_reason IN ('FAILED_ATTEMPTS')),
    CONSTRAINT measured_status_names_its_source
        CHECK ((status = 'MEASURED') = (source IS NOT NULL)),
    CONSTRAINT unavailable_status_names_its_reason
        CHECK ((status = 'UNAVAILABLE') = (unavailable_reason IS NOT NULL)),
    CONSTRAINT media_file_stream_measurement_status_key UNIQUE (media_file_id, status)
);

-- encoded_keyframe_interval_bytes and encoded_keyframe_interval_packet_counts each hold one unsigned
-- 32-bit count for each keyframe interval in KeyframeIntervalCountsCodec's format: the stream's bytes
-- and its packets in that interval. The foreign key admits a row only while its measurement's status
-- is MEASURED.
CREATE TABLE media_file_measured_stream
(
    media_file_id                           UUID     NOT NULL,
    status                                  TEXT     GENERATED ALWAYS AS ('MEASURED') STORED,
    stream_index                            INTEGER  NOT NULL CHECK (stream_index >= 0),
    measured_bytes                          BIGINT   NOT NULL CHECK (measured_bytes >= 0),
    packet_count                            BIGINT   NOT NULL CHECK (packet_count >= 0),
    time_base_numerator                     INTEGER  NOT NULL CHECK (time_base_numerator > 0),
    time_base_denominator                   INTEGER  NOT NULL CHECK (time_base_denominator > 0),
    measured_duration                       BIGINT   NOT NULL CHECK (measured_duration >= 0),
    keyframe_interval_count                 INTEGER  NOT NULL CHECK (keyframe_interval_count > 0),
    encoded_keyframe_interval_bytes         BYTEA    NOT NULL,
    encoded_keyframe_interval_packet_counts BYTEA    NOT NULL,
    PRIMARY KEY (media_file_id, stream_index),
    CONSTRAINT measured_stream_requires_measured_status
        FOREIGN KEY (media_file_id, status)
            REFERENCES media_file_stream_measurement (media_file_id, status)
            ON DELETE CASCADE
);
