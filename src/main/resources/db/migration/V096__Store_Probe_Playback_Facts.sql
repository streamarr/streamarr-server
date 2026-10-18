-- Probe version 4 names the chosen video stream and reports the frame-rate rule's result for it.
-- An outcome from an earlier probe version, or a failed probe, leaves every column NULL. The
-- output frame rate is NULL exactly when the rule refused to choose one. Fractions keep both
-- positive terms, and durations are seconds and nanoseconds.
ALTER TABLE media_file_container_info
    ADD COLUMN chosen_video_stream_index INTEGER CHECK (chosen_video_stream_index >= 0),
    ADD COLUMN output_frame_rate_basis TEXT CHECK (output_frame_rate_basis IN (
        'DECLARED_RATES', 'MATROSKA_STATISTICS', 'PACKET_SCAN', 'DECLARED_RATE_FALLBACK', 'REFUSED')),
    ADD COLUMN output_frame_rate_numerator INTEGER CHECK (output_frame_rate_numerator > 0),
    ADD COLUMN output_frame_rate_denominator INTEGER CHECK (output_frame_rate_denominator > 0),
    ADD COLUMN base_rate_numerator INTEGER CHECK (base_rate_numerator > 0),
    ADD COLUMN base_rate_denominator INTEGER CHECK (base_rate_denominator > 0),
    ADD COLUMN average_rate_numerator INTEGER CHECK (average_rate_numerator > 0),
    ADD COLUMN average_rate_denominator INTEGER CHECK (average_rate_denominator > 0),
    ADD COLUMN field_order TEXT CHECK (field_order IN ('PROGRESSIVE', 'TT', 'BB', 'TB', 'BT')),
    ADD COLUMN time_base_numerator INTEGER CHECK (time_base_numerator > 0),
    ADD COLUMN time_base_denominator INTEGER CHECK (time_base_denominator > 0),
    ADD COLUMN matroska_number_of_frames BIGINT CHECK (matroska_number_of_frames >= 0),
    ADD COLUMN matroska_duration_seconds BIGINT CHECK (matroska_duration_seconds >= 0),
    ADD COLUMN matroska_duration_nanos INTEGER
        CHECK (matroska_duration_nanos BETWEEN 0 AND 999999999),
    ADD COLUMN packet_scan_frame_count INTEGER CHECK (packet_scan_frame_count >= 0),
    ADD COLUMN packet_scan_motion_rate DOUBLE PRECISION CHECK (packet_scan_motion_rate > 0),
    ADD COLUMN packet_scan_duration_seconds BIGINT CHECK (packet_scan_duration_seconds >= 0),
    ADD COLUMN packet_scan_duration_nanos INTEGER
        CHECK (packet_scan_duration_nanos BETWEEN 0 AND 999999999),
    ADD CONSTRAINT chosen_video_stream_has_frame_rate_rule
        CHECK ((chosen_video_stream_index IS NULL) = (output_frame_rate_basis IS NULL)),
    ADD CONSTRAINT failed_probe_has_no_chosen_video_stream
        CHECK (probe_error IS NULL OR chosen_video_stream_index IS NULL),
    ADD CONSTRAINT complete_output_frame_rate
        CHECK ((output_frame_rate_numerator IS NULL) = (output_frame_rate_denominator IS NULL)),
    ADD CONSTRAINT output_frame_rate_absent_only_when_refused
        CHECK (output_frame_rate_basis IS NULL OR
               (output_frame_rate_numerator IS NULL) = (output_frame_rate_basis = 'REFUSED')),
    ADD CONSTRAINT complete_base_rate
        CHECK ((base_rate_numerator IS NULL) = (base_rate_denominator IS NULL)),
    ADD CONSTRAINT complete_average_rate
        CHECK ((average_rate_numerator IS NULL) = (average_rate_denominator IS NULL)),
    ADD CONSTRAINT complete_time_base
        CHECK ((time_base_numerator IS NULL) = (time_base_denominator IS NULL)),
    ADD CONSTRAINT complete_matroska_duration
        CHECK ((matroska_duration_seconds IS NULL) = (matroska_duration_nanos IS NULL)),
    ADD CONSTRAINT complete_packet_scan_duration
        CHECK ((packet_scan_duration_seconds IS NULL) = (packet_scan_duration_nanos IS NULL)),
    ADD CONSTRAINT packet_scan_has_frame_count
        CHECK (packet_scan_frame_count IS NOT NULL OR
               (packet_scan_motion_rate IS NULL AND packet_scan_duration_seconds IS NULL));

-- Probe version 4 adds these facts to each stream. An earlier probe version leaves them NULL.
-- dynamic_range_compatible_base is set exactly for a video stream whose probe reported a dynamic
-- range. dolby_vision is UNSPECIFIED, ABSENT or PRESENT. The configuration record's flags are set
-- exactly when the probe found a whole record, and its numeric claims may each be missing.
ALTER TABLE media_file_stream_info
    ADD COLUMN dynamic_range_compatible_base TEXT
        CHECK (dynamic_range_compatible_base IN ('UNSPECIFIED', 'NONE', 'SDR', 'PQ', 'HLG')),
    ADD COLUMN hdr10_plus TEXT CHECK (hdr10_plus IN ('UNSPECIFIED', 'ABSENT', 'PRESENT')),
    ADD COLUMN dolby_vision TEXT CHECK (dolby_vision IN ('UNSPECIFIED', 'ABSENT', 'PRESENT')),
    ADD COLUMN dolby_vision_observed_rpu TEXT
        CHECK (dolby_vision_observed_rpu IN ('UNSPECIFIED', 'ABSENT', 'PRESENT')),
    ADD COLUMN dolby_vision_profile INTEGER CHECK (dolby_vision_profile >= 0),
    ADD COLUMN dolby_vision_level INTEGER CHECK (dolby_vision_level >= 0),
    ADD COLUMN dolby_vision_base_layer_compatibility_id INTEGER
        CHECK (dolby_vision_base_layer_compatibility_id >= 0),
    ADD COLUMN dolby_vision_base_layer BOOLEAN,
    ADD COLUMN dolby_vision_enhancement_layer BOOLEAN,
    ADD COLUMN dolby_vision_rpu BOOLEAN,
    ADD COLUMN bit_depth INTEGER CHECK (bit_depth > 0),
    ADD COLUMN chroma_subsampling TEXT
        CHECK (chroma_subsampling IN ('CHROMA_420', 'CHROMA_422', 'CHROMA_444', 'MONOCHROME')),
    ADD COLUMN rotation_degrees INTEGER CHECK (rotation_degrees BETWEEN -180 AND 180),
    ADD COLUMN crop_top INTEGER CHECK (crop_top >= 0),
    ADD COLUMN crop_bottom INTEGER CHECK (crop_bottom >= 0),
    ADD COLUMN crop_left INTEGER CHECK (crop_left >= 0),
    ADD COLUMN crop_right INTEGER CHECK (crop_right >= 0),
    ADD COLUMN codec_string TEXT CHECK (codec_string <> ''),
    ADD COLUMN sample_rate_hz INTEGER CHECK (sample_rate_hz > 0),
    ADD COLUMN audio_profile TEXT,
    ADD CONSTRAINT complete_dynamic_range CHECK (
        (dynamic_range_compatible_base IS NULL) = (hdr10_plus IS NULL) AND
        (dynamic_range_compatible_base IS NULL) = (dolby_vision IS NULL)),
    ADD CONSTRAINT dolby_vision_details_require_presence CHECK (
        (dolby_vision_observed_rpu IS NOT NULL) = (dolby_vision IS NOT DISTINCT FROM 'PRESENT')),
    ADD CONSTRAINT complete_dolby_vision_record CHECK (
        (dolby_vision_base_layer IS NULL) = (dolby_vision_enhancement_layer IS NULL) AND
        (dolby_vision_base_layer IS NULL) = (dolby_vision_rpu IS NULL)),
    ADD CONSTRAINT dolby_vision_record_requires_presence CHECK (
        dolby_vision_base_layer IS NULL OR dolby_vision = 'PRESENT'),
    ADD CONSTRAINT dolby_vision_claims_require_record CHECK (
        dolby_vision_base_layer IS NOT NULL OR (
            dolby_vision_profile IS NULL AND dolby_vision_level IS NULL AND
            dolby_vision_base_layer_compatibility_id IS NULL)),
    ADD CONSTRAINT complete_container_cropping CHECK (
        (crop_top IS NULL) = (crop_bottom IS NULL) AND
        (crop_top IS NULL) = (crop_left IS NULL) AND
        (crop_top IS NULL) = (crop_right IS NULL));
