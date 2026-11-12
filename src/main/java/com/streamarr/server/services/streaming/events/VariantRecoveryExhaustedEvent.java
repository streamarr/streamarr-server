package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A segment request found the variant's recovery exhausted: its job attempts failed on every worker
 * that can run it, or failed in a way that every later job attempt repeats. A lower variant beside
 * a copy has then failed for good, and the stream session withdraws its lower variants (ADR 0050).
 */
@Builder
public record VariantRecoveryExhaustedEvent(
    @NonNull UUID streamSessionId, @NonNull String variantLabel) {}
