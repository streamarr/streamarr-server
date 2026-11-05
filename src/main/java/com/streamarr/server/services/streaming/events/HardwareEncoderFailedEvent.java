package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A worker reported that the hardware encoder of a variant's job attempt failed or refused to start
 * (ADR 0044). The worker states separately whether it still offers that encoder.
 */
@Builder
public record HardwareEncoderFailedEvent(
    @NonNull UUID streamSessionId, @NonNull UUID jobAttemptId, @NonNull String variantLabel) {}
