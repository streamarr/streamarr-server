-- Whether a keyframe packet scan read the stream's end time, or a job attempt reported it (ADR
-- 0037, early ends). A packet scan fails when it cannot read the source to its end, so only its end
-- times may move a stream session's end below the container's duration. A job attempt's FFmpeg can
-- stop at a read error and still exit with status 0, so its end time only fills an end time that
-- the container header does not state. Every row stored before this column came from a job attempt.
ALTER TABLE media_file_learned_stream_end
    ADD COLUMN from_packet_scan BOOLEAN NOT NULL DEFAULT FALSE;
