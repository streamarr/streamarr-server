package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.PersistedProbeOutcome;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.concurrency.NanoClock;
import com.streamarr.server.services.events.library.MediaFileProbeTaskRequested;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.PlaybackProbeClaims;
import com.streamarr.server.services.probe.ProbeAttemptEnds;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

  private static final String WAIT_METRIC = "streamarr.playback.probe_wait";

  private final PersistedProbeReader reader;
  private final MediaFileContainerInfoRepository outcomes;
  private final ApplicationEventPublisher eventPublisher;
  private final ProbeWorkers probeWorkers;
  private final ProbeAttemptEnds attemptEnds;
  private final Clock clock;
  private final NanoClock nanoClock;
  private final StreamingProperties properties;
  private final MeterRegistry meterRegistry;
  private final PlaybackProbeClaims playbackProbeClaims;

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

    // A saved failure time keeps only microseconds, rounded to the nearest one. Truncating the
    // request time keeps a failure after the request from reading back as earlier.
    var requestedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    // The wait starts before the request, so a claim that follows the request at once finds it.
    try (var _ = playbackProbeClaims.waitStarted(mediaFileId)) {
      return requestAndAwaitOutcome(mediaFileId, requestedAt);
    }
  }

  private Outcome<MediaProbe, CreateStreamSessionRejection> requestAndAwaitOutcome(
      UUID mediaFileId, Instant requestedAt) {
    // The wait counts elapsed time, so a step of the system clock cannot lengthen the wait or
    // distort its timing.
    var waitStarted = nanoClock.nanoTime();
    eventPublisher.publishEvent(new MediaFileProbeTaskRequested(mediaFileId));
    if (!probeWorkers.hasCompatibleWorker(ProbeVersion.CURRENT)) {
      return notReady();
    }

    var outcome = awaitOutcome(mediaFileId, requestedAt, waitStarted);
    Timer.builder(WAIT_METRIC)
        .description("How long a playback request waited for its media file's probe")
        .tag("outcome", waitOutcome(outcome))
        .register(meterRegistry)
        .record(Duration.ofNanos(nanoClock.nanoTime() - waitStarted));
    return outcome;
  }

  private static String waitOutcome(Outcome<MediaProbe, CreateStreamSessionRejection> outcome) {
    return outcome.fold(_ -> "ready", PlaybackProbeService::rejectedWaitOutcome);
  }

  private static String rejectedWaitOutcome(List<CreateStreamSessionRejection> rejections) {
    if (rejections.stream().anyMatch(CreateStreamSessionRejection.ProbeFailed.class::isInstance)) {
      return "probe_failed";
    }

    return "not_ready";
  }

  private Outcome<MediaProbe, CreateStreamSessionRejection> awaitOutcome(
      UUID mediaFileId, Instant requestedAt, long waitStarted) {
    while (true) {
      try (var nextEnd = attemptEnds.next(mediaFileId)) {
        var stored = reader.find(mediaFileId);
        if (stored.isPresent()) {
          return playable(stored.get());
        }

        if (hasEndedWithoutOutcome(mediaFileId, requestedAt)) {
          return notReady();
        }

        var remaining = properties.probeWaitLimit().minusNanos(nanoClock.nanoTime() - waitStarted);
        if (!remaining.isPositive()) {
          return storedOrNotReady(mediaFileId);
        }

        var waited = awaitEnd(nextEnd, remaining);
        if (waited == AttemptWait.INTERRUPTED) {
          return notReady();
        }

        if (waited == AttemptWait.TIMED_OUT) {
          return storedOrNotReady(mediaFileId);
        }
      }
    }
  }

  // The limit has passed. An outcome stored since the last read still starts playback.
  private Outcome<MediaProbe, CreateStreamSessionRejection> storedOrNotReady(UUID mediaFileId) {
    return reader
        .find(mediaFileId)
        .map(PlaybackProbeService::playable)
        .orElseGet(PlaybackProbeService::notReady);
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

  private enum AttemptWait {
    ENDED,
    TIMED_OUT,
    INTERRUPTED
  }

  // Restores the thread's interrupt status when the wait is interrupted.
  private static AttemptWait awaitEnd(ProbeAttemptEnds.NextEnd nextEnd, Duration remaining) {
    try {
      if (nextEnd.await(remaining)) {
        return AttemptWait.ENDED;
      }

      return AttemptWait.TIMED_OUT;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return AttemptWait.INTERRUPTED;
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
