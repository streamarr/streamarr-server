-- Probe version 5 adds these facts to each video stream. An earlier probe version, and every
-- stream that is not video, leaves them NULL. first_frames_read and the two omitted flags are set
-- exactly for a video stream of probe version 5 or later. A codec property or color property that
-- the probe could not name stays NULL beside them, so a NULL codec_profile on such a row is the
-- contract's UNSPECIFIED codec profile.
--
-- A Dolby Vision stream keeps the header of the first RPU that the probe read, whole or not at
-- all, and only when the probe observed an RPU. dolby_vision_rpu_starts_sample is NULL when the
-- probe could not see where the RPUs sit, and it too needs an observed RPU.
ALTER TABLE media_file_stream_info
    ADD COLUMN codec_profile TEXT CHECK (codec_profile IN (
        'H264_CONSTRAINED_BASELINE', 'H264_BASELINE', 'H264_MAIN', 'H264_EXTENDED', 'H264_HIGH',
        'H264_HIGH_10', 'H264_HIGH_422', 'H264_HIGH_444_PREDICTIVE', 'HEVC_MAIN', 'HEVC_MAIN_10',
        'HEVC_MAIN_STILL_PICTURE', 'HEVC_FORMAT_RANGE_EXTENSIONS', 'AV1_MAIN', 'AV1_HIGH',
        'AV1_PROFESSIONAL')),
    ADD COLUMN codec_level INTEGER CHECK (codec_level > 0),
    ADD COLUMN codec_tier TEXT CHECK (codec_tier IN ('MAIN', 'HIGH')),
    ADD COLUMN color_primaries TEXT CHECK (color_primaries IN (
        'BT709', 'BT470M', 'BT470BG', 'SMPTE170M', 'SMPTE240M', 'FILM', 'BT2020', 'SMPTE428',
        'SMPTE431', 'SMPTE432', 'EBU3213')),
    ADD COLUMN transfer_characteristics TEXT CHECK (transfer_characteristics IN (
        'BT709', 'BT470M', 'BT470BG', 'SMPTE170M', 'SMPTE240M', 'LINEAR', 'LOG100', 'LOG316',
        'IEC61966_2_4', 'BT1361', 'IEC61966_2_1', 'BT2020_10', 'BT2020_12', 'SMPTE2084',
        'SMPTE428', 'ARIB_STD_B67')),
    ADD COLUMN matrix_coefficients TEXT CHECK (matrix_coefficients IN (
        'IDENTITY', 'BT709', 'FCC', 'BT470BG', 'SMPTE170M', 'SMPTE240M', 'YCGCO', 'BT2020_NCL',
        'BT2020_CL', 'SMPTE2085', 'CHROMA_DERIVED_NCL', 'CHROMA_DERIVED_CL', 'ICTCP')),
    ADD COLUMN color_range TEXT CHECK (color_range IN ('LIMITED', 'FULL')),
    ADD COLUMN first_frames_read BOOLEAN,
    ADD COLUMN mastering_display_omitted BOOLEAN,
    ADD COLUMN content_light_level_omitted BOOLEAN,
    ADD COLUMN dolby_vision_rpu_starts_sample TEXT
        CHECK (dolby_vision_rpu_starts_sample IN ('ABSENT', 'PRESENT')),
    ADD COLUMN first_rpu_vdr_rpu_profile INTEGER CHECK (first_rpu_vdr_rpu_profile >= 0),
    ADD COLUMN first_rpu_base_layer_full_range BOOLEAN,
    ADD COLUMN first_rpu_enhancement_layer_spatial_resampling_filter BOOLEAN,
    ADD COLUMN first_rpu_disable_residual BOOLEAN,
    ADD COLUMN first_rpu_vdr_bit_depth INTEGER CHECK (first_rpu_vdr_bit_depth >= 8),
    ADD CONSTRAINT complete_video_stream_properties CHECK (
        (first_frames_read IS NULL) = (mastering_display_omitted IS NULL) AND
        (first_frames_read IS NULL) = (content_light_level_omitted IS NULL) AND
        (first_frames_read IS NOT NULL OR
         num_nonnulls(codec_profile, codec_level, codec_tier, color_primaries,
                      transfer_characteristics, matrix_coefficients, color_range) = 0)),
    ADD CONSTRAINT codec_level_and_tier_require_codec_profile CHECK (
        codec_profile IS NOT NULL OR (codec_level IS NULL AND codec_tier IS NULL)),
    ADD CONSTRAINT omitted_mastering_display_is_not_listed CHECK (
        mastering_display_omitted IS NOT TRUE OR mastering_red_x IS NULL),
    ADD CONSTRAINT omitted_content_light_level_is_not_listed CHECK (
        content_light_level_omitted IS NOT TRUE OR max_content_light_level IS NULL),
    ADD CONSTRAINT complete_first_rpu_header CHECK (
        num_nonnulls(first_rpu_vdr_rpu_profile, first_rpu_base_layer_full_range,
                     first_rpu_enhancement_layer_spatial_resampling_filter,
                     first_rpu_disable_residual, first_rpu_vdr_bit_depth) IN (0, 5)),
    ADD CONSTRAINT rpu_details_require_observed_rpu CHECK (
        (first_rpu_vdr_rpu_profile IS NULL AND dolby_vision_rpu_starts_sample IS NULL) OR
        dolby_vision_observed_rpu = 'PRESENT');
