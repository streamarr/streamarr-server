package com.streamarr.server.services.streaming;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * One player request for a segment of a stream session's variant. A request with a larger {@code
 * arrival} reached the server later and is newer.
 */
@Builder
public record SegmentRequest(
    @NonNull UUID sessionId,
    @NonNull String variantLabel,
    @NonNull String segmentName,
    long arrival) {}
