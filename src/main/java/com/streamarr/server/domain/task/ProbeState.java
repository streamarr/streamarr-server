package com.streamarr.server.domain.task;

import com.streamarr.server.domain.streaming.ProbeError;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * What the database holds for one media file's probe: the inputs most recently requested, the
 * stored outcome, and the latest failed attempt at the requested inputs.
 */
@Builder
public record ProbeState(
    @NonNull UUID mediaFileId,
    @NonNull Optional<ProbeInputs> requested,
    @NonNull Optional<Stored> stored,
    @NonNull Optional<ProbeAttemptFailure> failure) {

  /** A stored outcome: a success, or a terminal media error that is not retried. */
  public record Stored(@NonNull ProbeInputs inputs, @NonNull Optional<ProbeError> error) {}
}
