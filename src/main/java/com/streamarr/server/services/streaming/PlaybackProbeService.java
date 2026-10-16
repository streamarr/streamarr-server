package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.PersistedProbeOutcome;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.events.library.MediaFileProbeTaskRequested;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeAttemptEnds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@Builder
@RequiredArgsConstructor
public class PlaybackProbeService {

  private final PersistedProbeReader reader;
  private final MediaFileContainerInfoRepository outcomes;
  private final ApplicationEventPublisher eventPublisher;
  private final ProbeWorkers probeWorkers;
  private final ProbeAttemptEnds attemptEnds;
  private final Clock clock;
  private final StreamingProperties properties;

  /**
   * Reads the media file's stored probe outcome. When the file has none, requests its probe ahead
   * of every queued probe and waits, up to the configured limit, for the probe to store an outcome.
   * The wait ends without an outcome, as not ready, when no compatible worker is connected, when an
   * attempt after the request fails, when the source is removed, or at the limit. The probe keeps
   * running in each case.
   */
  public Outcome<MediaProbe, CreateStreamSessionRejection> read(@NonNull UUID mediaFileId) {
    var stored = reader.find(mediaFileId);
    if (stored.isPresent()) {
      return playable(stored.get());
    }

    var requestedAt = clock.instant();
    eventPublisher.publishEvent(new MediaFileProbeTaskRequested(mediaFileId));
    if (!probeWorkers.hasCompatibleWorker(ProbeVersion.CURRENT)) {
      return notReady();
    }

    return awaitOutcome(mediaFileId, requestedAt);
  }

  private Outcome<MediaProbe, CreateStreamSessionRejection> awaitOutcome(
      UUID mediaFileId, Instant requestedAt) {
    var deadline = requestedAt.plus(properties.probeWaitLimit());
    while (true) {
      try (var nextEnd = attemptEnds.next(mediaFileId)) {
        var stored = reader.find(mediaFileId);
        if (stored.isPresent()) {
          return playable(stored.get());
        }

        if (hasEndedWithoutOutcome(mediaFileId, requestedAt)) {
          return notReady();
        }

        var remaining = Duration.between(clock.instant(), deadline);
        if (!remaining.isPositive() || !tryAwait(nextEnd, remaining)) {
          return notReady();
        }
      }
    }
  }

  // A failure saved before the request belongs to an earlier attempt, so the wait continues.
  private boolean hasEndedWithoutOutcome(UUID mediaFileId, Instant requestedAt) {
    return outcomes.findProbeStates(List.of(mediaFileId)).stream()
        .findFirst()
        .map(
            state ->
                state.requested().isEmpty()
                    || state
                        .failure()
                        .filter(failure -> !failure.failedAt().isBefore(requestedAt))
                        .isPresent())
        .orElse(true);
  }

  // Returns false when the thread is interrupted, after restoring its interrupt status.
  private static boolean tryAwait(ProbeAttemptEnds.NextEnd nextEnd, Duration remaining) {
    try {
      nextEnd.await(remaining);
      return true;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static Outcome<MediaProbe, CreateStreamSessionRejection> playable(
      PersistedProbeOutcome stored) {
    return switch (stored.outcome()) {
      case ProbeOutcome.Success success -> Outcome.accepted(success.mediaProbe());
      case ProbeOutcome.Failure(var reason) ->
          Outcome.rejected(new CreateStreamSessionRejection.ProbeFailed(reason));
    };
  }

  private static Outcome<MediaProbe, CreateStreamSessionRejection> notReady() {
    return Outcome.rejected(new CreateStreamSessionRejection.ProbeNotReady());
  }
}
