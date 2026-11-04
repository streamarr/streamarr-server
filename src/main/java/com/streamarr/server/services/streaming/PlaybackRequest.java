package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A player's request for a stream session's media. {@code delivery} names the delivery that the
 * requested resource belongs to: an HLS playlist or segment, or the direct-play source file.
 */
@Builder
public record PlaybackRequest(
    @NonNull UUID streamSessionId,
    @NonNull AuthenticatedIdentity identity,
    @NonNull StreamDelivery delivery) {}
