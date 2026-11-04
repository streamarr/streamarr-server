-- Probe version 7 adds two source facts that direct play reads (ADR 0032, ADR 0045). An earlier
-- probe version leaves both columns NULL.
--
-- sample_entry is the type of the sample entry that an MP4 or MOV file stores for a video stream,
-- such as hvc1 or dvh1. major_brand is the major brand of the file's ftyp box, such as isom or
-- 'qt  ' with its two spaces. Each holds exactly four printable ASCII characters, and is NULL when
-- the file stores none.
--
-- Every column is added in the same statement as its constraints, so every existing row is NULL
-- and satisfies them. NOT VALID skips the scan of each table under ACCESS EXCLUSIVE, and
-- PostgreSQL still checks every insert and update. IS TRUE makes a row without a codec type break
-- a constraint rather than pass it as unknown.
ALTER TABLE media_file_stream_info
    ADD COLUMN sample_entry TEXT,
    ADD CONSTRAINT sample_entry_requires_video_stream CHECK (
        sample_entry IS NULL OR (codec_type = 'video') IS TRUE) NOT VALID,
    ADD CONSTRAINT sample_entry_has_four_printable_characters CHECK (
        sample_entry ~ '^[ -~]{4}$') NOT VALID;

ALTER TABLE media_file_container_info
    ADD COLUMN major_brand TEXT,
    ADD CONSTRAINT major_brand_has_four_printable_characters CHECK (
        major_brand ~ '^[ -~]{4}$') NOT VALID;
