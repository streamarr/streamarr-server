package com.streamarr.server.domain.streaming;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * Terminal evidence for one producer run, retained at the death transition and consumed exactly
 * once by recovery classification. Always scoped to the attempt that produced it so a late event
 * from a superseded attempt is never attributed to its replacement.
 */
@Builder
public record ProducerEnd(UUID attemptId, EndKind kind, String detail, Instant at) {

  public enum EndKind {
    PROCESS_EXIT,
    FAILED,
    COMPLETED,
    STOPPED,
    DISCONNECTED,
    STALLED
  }
}
