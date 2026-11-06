package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The hardware encoder of the variant that the player requests failed, so the playback session
 * continues the encode on the software encoder of the same codec family (ADR 0044).
 */
@Builder
public record RequestedVariantHardwareEncoderFailedEvent(
    @NonNull UUID streamSessionId, @NonNull String variantLabel) {}
