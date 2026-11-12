-- A new probe of an unchanged file keeps the keyframe positions that an earlier keyframe packet
-- scan read, beside its own outcome, whose keyframe_status stays PACKET_SCAN_REQUIRED until its
-- packet scan stores a result. The foreign key therefore admits a positions row beside a KNOWN or a
-- PACKET_SCAN_REQUIRED outcome. It still refuses one beside UNAVAILABLE keyframes and beside an
-- outcome that reports no keyframes.
ALTER TABLE media_file_keyframe_positions
    DROP CONSTRAINT keyframe_positions_require_known_status,
    DROP COLUMN keyframe_status;

ALTER TABLE media_file_container_info
    DROP CONSTRAINT media_file_container_info_keyframe_status_key,
    ADD COLUMN keyframe_positions_admitted BOOLEAN
        GENERATED ALWAYS AS (keyframe_status IN ('KNOWN', 'PACKET_SCAN_REQUIRED')) STORED,
    ADD CONSTRAINT media_file_container_info_keyframe_positions_admitted_key
        UNIQUE (media_file_id, keyframe_positions_admitted);

ALTER TABLE media_file_keyframe_positions
    ADD COLUMN keyframe_positions_admitted BOOLEAN GENERATED ALWAYS AS (TRUE) STORED,
    ADD CONSTRAINT keyframe_positions_require_admitting_status
        FOREIGN KEY (media_file_id, keyframe_positions_admitted)
            REFERENCES media_file_container_info (media_file_id, keyframe_positions_admitted)
            ON DELETE CASCADE;
