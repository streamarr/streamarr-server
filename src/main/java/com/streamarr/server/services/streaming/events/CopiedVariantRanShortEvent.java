package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A copied variant ran short of its playlist while a source stream still has media after the copy's
 * last segment, so the copy failed as a copy does (ADR 0052).
 */
@Builder
public record CopiedVariantRanShortEvent(
    @NonNull UUID streamSessionId, @NonNull UUID jobAttemptId, @NonNull String variantLabel) {}
