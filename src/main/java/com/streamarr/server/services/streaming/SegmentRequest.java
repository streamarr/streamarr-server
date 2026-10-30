package com.streamarr.server.services.streaming;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** One player request for a segment of a stream session's variant. */
@Builder
public record SegmentRequest(
    @NonNull UUID sessionId, @NonNull String variantLabel, @NonNull String segmentName) {}
