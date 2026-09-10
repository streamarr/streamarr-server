CREATE TABLE media_file_container_info
(
    media_file_id UUID PRIMARY KEY REFERENCES media_file (id) ON DELETE CASCADE,
    source_size BIGINT NOT NULL CHECK (source_size >= 0),
    source_modified_epoch_second BIGINT NOT NULL,
    source_modified_nanos INTEGER NOT NULL CHECK (source_modified_nanos BETWEEN 0 AND 999999999),
    probe_version INTEGER NOT NULL CHECK (probe_version > 0),
    format TEXT,
    duration_seconds BIGINT,
    duration_nanos INTEGER CHECK (duration_nanos BETWEEN 0 AND 999999999),
    total_bitrate BIGINT,
    probe_error TEXT CHECK (probe_error IN ('INVALID_MEDIA', 'NO_VIDEO_STREAM')),
    CONSTRAINT complete_probe_duration CHECK ((duration_seconds IS NULL) = (duration_nanos IS NULL)),
    CONSTRAINT failed_probe_has_no_container_properties CHECK (
        probe_error IS NULL OR
        (format IS NULL AND duration_seconds IS NULL AND duration_nanos IS NULL AND total_bitrate IS NULL)
    )
);

CREATE TABLE media_file_stream_info
(
    media_file_id UUID NOT NULL REFERENCES media_file_container_info (media_file_id) ON DELETE CASCADE,
    stream_index INTEGER NOT NULL CHECK (stream_index >= 0),
    codec_type TEXT NOT NULL,
    codec TEXT,
    width INTEGER,
    height INTEGER,
    framerate DOUBLE PRECISION,
    channels INTEGER,
    bitrate BIGINT,
    language TEXT,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    is_forced BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (media_file_id, stream_index)
);
