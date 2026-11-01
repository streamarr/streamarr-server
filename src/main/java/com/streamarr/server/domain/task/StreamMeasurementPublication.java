package com.streamarr.server.domain.task;

import com.streamarr.server.domain.streaming.StreamMeasurements;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** A measurement of the file's streams that follows the key's snapshot and keyframe positions. */
@Builder
public record StreamMeasurementPublication(
    @NonNull UUID mediaFileId,
    @NonNull BitrateScanKey key,
    @NonNull StreamMeasurements measurements) {}
