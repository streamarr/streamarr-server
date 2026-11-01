-- Whether a stream copy delivers every packet of an H.264 or HEVC video stream with its bytes
-- unchanged. The probe reports it only for those codecs, and it is NULL when the probe did not
-- report it, which every result probed before the probe reported this fact leaves. IS TRUE makes
-- a stream without a codec break the constraint rather than pass it as unknown.
ALTER TABLE media_file_stream_info
    ADD COLUMN copy_keeps_packet_bytes BOOLEAN,
    ADD CONSTRAINT copy_keeps_packet_bytes_requires_h264_or_hevc CHECK (
        copy_keeps_packet_bytes IS NULL OR (codec IN ('h264', 'hevc')) IS TRUE) NOT VALID;
