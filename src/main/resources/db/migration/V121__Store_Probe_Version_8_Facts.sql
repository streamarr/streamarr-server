-- Probe version 8 adds three facts to a video stream (ADR 0032). An earlier probe version, and every
-- stream that is not video, leaves them NULL.
--
-- maximum_frame_rate_numerator and maximum_frame_rate_denominator hold the chosen video stream's
-- maximum frame rate, as ADR 0032 defines it, as an exact fraction. Both are NULL when the probe
-- could not measure the maximum, so an unknown maximum is never zero.
--
-- rpu_enhancement_layer classifies a Dolby Vision stream's enhancement layer by its RPUs' NLQ
-- parameters: NONE, MEL or FEL, and NULL when the probe could not tell. rpu_max_content_light_level
-- and rpu_max_frame_average_light_level hold MaxCLL and MaxFALL from the first RPU's level 6 block,
-- in cd/m^2, and are NULL when no RPU that the probe parsed carries one. All three need Dolby Vision.
--
-- Every column is added in the same statement as its constraints, so every existing row is NULL
-- and satisfies them. NOT VALID skips the scan of the table under ACCESS EXCLUSIVE, and PostgreSQL
-- still checks every insert and update. IS TRUE makes a row without a codec type or Dolby Vision
-- break a constraint rather than pass it as unknown.
ALTER TABLE media_file_stream_info
    ADD COLUMN maximum_frame_rate_numerator INTEGER,
    ADD COLUMN maximum_frame_rate_denominator INTEGER,
    ADD COLUMN rpu_enhancement_layer TEXT,
    ADD COLUMN rpu_max_content_light_level INTEGER,
    ADD COLUMN rpu_max_frame_average_light_level INTEGER,
    ADD CONSTRAINT maximum_frame_rate_has_positive_terms CHECK (
        maximum_frame_rate_numerator > 0 AND maximum_frame_rate_denominator > 0) NOT VALID,
    ADD CONSTRAINT complete_maximum_frame_rate CHECK (
        (maximum_frame_rate_numerator IS NULL) = (maximum_frame_rate_denominator IS NULL)) NOT VALID,
    ADD CONSTRAINT maximum_frame_rate_requires_video_stream CHECK (
        maximum_frame_rate_numerator IS NULL OR (codec_type = 'video') IS TRUE) NOT VALID,
    ADD CONSTRAINT rpu_enhancement_layer_is_classified CHECK (
        rpu_enhancement_layer IN ('NONE', 'MEL', 'FEL')) NOT VALID,
    ADD CONSTRAINT rpu_light_levels_in_range CHECK (
        rpu_max_content_light_level BETWEEN 0 AND 65535 AND
        rpu_max_frame_average_light_level BETWEEN 0 AND 65535) NOT VALID,
    ADD CONSTRAINT complete_rpu_light_levels CHECK (
        (rpu_max_content_light_level IS NULL) = (rpu_max_frame_average_light_level IS NULL))
        NOT VALID,
    ADD CONSTRAINT rpu_facts_require_dolby_vision CHECK (
        num_nonnulls(rpu_enhancement_layer, rpu_max_content_light_level) = 0 OR
        (dolby_vision = 'PRESENT') IS TRUE) NOT VALID;
