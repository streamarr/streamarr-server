-- Probe version 7 adds these facts. An earlier probe version leaves every column NULL.
--
-- channel_layout and sample_format are ffprobe's channel_layout and sample_fmt text, unchanged,
-- and are NULL when ffprobe names none. The six dispositions from visual_impaired to dependent are
-- set together, exactly for an audio stream of probe version 7 or later, and comment beside them.
-- comment is also set for each subtitle stream of probe version 7 or later. Audio streams of
-- probe version 7 and later also report their bit depth, which the existing bit_depth column
-- stores.
--
-- flac_minimum_block_size_samples is the first field of a FLAC stream's STREAMINFO block, a
-- 16-bit number, and is NULL when the probe found no 34-byte STREAMINFO block.
--
-- Every column is added in the same statement as its constraint, so every existing row is NULL
-- and satisfies it. NOT VALID skips the scan of media_file_stream_info under ACCESS EXCLUSIVE,
-- and PostgreSQL still checks every insert and update. IS TRUE makes a row without a codec type or
-- codec break a constraint rather than pass it as unknown.
ALTER TABLE media_file_stream_info
    ADD COLUMN channel_layout TEXT,
    ADD COLUMN sample_format TEXT,
    ADD COLUMN comment BOOLEAN,
    ADD COLUMN visual_impaired BOOLEAN,
    ADD COLUMN descriptions BOOLEAN,
    ADD COLUMN original BOOLEAN,
    ADD COLUMN dub BOOLEAN,
    ADD COLUMN clean_effects BOOLEAN,
    ADD COLUMN dependent BOOLEAN,
    ADD COLUMN flac_minimum_block_size_samples INTEGER,
    ADD CONSTRAINT complete_audio_stream_properties CHECK (
        num_nonnulls(visual_impaired, descriptions, original, dub, clean_effects, dependent) = 0 OR
        (num_nonnulls(visual_impaired, descriptions, original, dub, clean_effects, dependent) = 6 AND
         comment IS NOT NULL AND (codec_type = 'audio') IS TRUE)) NOT VALID,
    ADD CONSTRAINT audio_facts_require_audio_stream_properties CHECK (
        num_nonnulls(channel_layout, sample_format, flac_minimum_block_size_samples) = 0 OR
        visual_impaired IS NOT NULL) NOT VALID,
    ADD CONSTRAINT comment_requires_audio_or_subtitle_stream CHECK (
        comment IS NULL OR (codec_type IN ('audio', 'subtitle')) IS TRUE) NOT VALID,
    ADD CONSTRAINT audio_comment_requires_audio_stream_properties CHECK (
        comment IS NULL OR codec_type <> 'audio' OR visual_impaired IS NOT NULL) NOT VALID,
    ADD CONSTRAINT flac_minimum_block_size_requires_flac CHECK (
        flac_minimum_block_size_samples IS NULL OR (codec = 'flac') IS TRUE) NOT VALID,
    ADD CONSTRAINT flac_minimum_block_size_fits_streaminfo CHECK (
        flac_minimum_block_size_samples BETWEEN 0 AND 65535) NOT VALID;
