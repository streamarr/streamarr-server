package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The inputs of one keyframe packet scan task instance: which file, and the source snapshot and
 * probe version of the stored outcome that asked for the scan.
 */
@Builder(toBuilder = true)
public record KeyframePacketScanTaskRequest(
    @NonNull UUID mediaFileId,
    @NonNull String filepathUri,
    @NonNull SourceFileSnapshot snapshot,
    int probeVersion) {}
