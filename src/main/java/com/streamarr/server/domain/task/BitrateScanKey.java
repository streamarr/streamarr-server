package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import lombok.NonNull;

/**
 * What a bitrate scan's chunks follow (ADR 0054): the source snapshot, the digest of the keyframe
 * positions whose intervals they measure, in lowercase hexadecimal, and the measurement version.
 */
public record BitrateScanKey(
    @NonNull SourceFileSnapshot snapshot,
    @NonNull String keyframePositionsDigest,
    int measurementVersion) {}
