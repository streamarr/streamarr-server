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

  /** Classifies the probe requested with these inputs against what the database holds. */
  public RequestedProbeResult resultFor(ProbeInputs inputs) {
    var outcome = stored.filter(candidate -> candidate.inputs().equals(inputs));
    if (outcome.isPresent()) {
      return outcomeResult(outcome.get());
    }

    // The requested probe cannot publish over a newer version's outcome for the same source.
    if (stored.filter(newer -> isNewerProbeOfSameSource(newer.inputs(), inputs)).isPresent()) {
      return RequestedProbeResult.SUPERSEDED;
    }

    if (requested.isEmpty()) {
      return RequestedProbeResult.REMOVED;
    }

    if (!requested.get().equals(inputs)) {
      return RequestedProbeResult.SUPERSEDED;
    }

    if (failure.isPresent()) {
      return RequestedProbeResult.FAILED;
    }

    return RequestedProbeResult.PENDING;
  }

  private static boolean isNewerProbeOfSameSource(ProbeInputs stored, ProbeInputs requested) {
    return stored.snapshot().equals(requested.snapshot())
        && stored.probeVersion() > requested.probeVersion();
  }

  private static RequestedProbeResult outcomeResult(Stored outcome) {
    if (outcome.error().isPresent()) {
      return RequestedProbeResult.MEDIA_ERROR;
    }

    return RequestedProbeResult.READY;
  }

  /** A stored outcome: a success, or a terminal media error that is not retried. */
  public record Stored(@NonNull ProbeInputs inputs, @NonNull Optional<ProbeError> error) {}
}
