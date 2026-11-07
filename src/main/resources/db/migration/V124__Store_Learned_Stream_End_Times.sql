-- A stream's end time learned from the first job attempt that reads past it, where the probed
-- container header states none (ADR 0037, early ends). It sits beside the probe facts but apart from
-- the probe results that ADR 0032 persists: it references the media file, not the stored outcome, so
-- a new probe never discards it, and it holds the source snapshot that the attempt read, so the
-- server applies it only to a probe outcome of that same snapshot.
--
-- end_seconds and end_nanos hold the end of the stream's last sample on the zero-based timeline of
-- the probe's keyframe positions, the timeline of the probed end times.
CREATE TABLE media_file_learned_stream_end
(
    media_file_id                UUID    NOT NULL REFERENCES media_file (id) ON DELETE CASCADE,
    stream_index                 INTEGER NOT NULL CHECK (stream_index >= 0),
    source_size                  BIGINT  NOT NULL CHECK (source_size >= 0),
    source_modified_epoch_second BIGINT  NOT NULL,
    source_modified_nanos        INTEGER NOT NULL CHECK (source_modified_nanos BETWEEN 0 AND 999999999),
    end_seconds                  BIGINT  NOT NULL CHECK (end_seconds >= 0),
    end_nanos                    INTEGER NOT NULL CHECK (end_nanos BETWEEN 0 AND 999999999),
    PRIMARY KEY (media_file_id, stream_index)
);
