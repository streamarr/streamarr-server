-- A new probe of an unchanged file keeps the keyframe positions that an earlier probe version's
-- packet scan read, until the new version's own packet scan stores its result. Such an outcome's
-- keyframe_status stays KNOWN, and it keeps its keyframe_packet_scan_reason, because that packet
-- scan is still owed. An outcome therefore stores a packet scan reason exactly while a packet scan
-- is owed for it: always while its keyframe_status is PACKET_SCAN_REQUIRED. While it is KNOWN, the
-- server stores the reason only beside kept positions. This constraint cannot check that, because
-- the positions are rows of another table.
ALTER TABLE media_file_container_info
    DROP CONSTRAINT packet_scan_reason_only_when_packet_scan_required,
    ADD CONSTRAINT packet_scan_reason_only_while_packet_scan_owed
        CHECK (CASE keyframe_status
                   WHEN 'PACKET_SCAN_REQUIRED' THEN keyframe_packet_scan_reason IS NOT NULL
                   WHEN 'KNOWN' THEN TRUE
                   ELSE keyframe_packet_scan_reason IS NULL
            END);
