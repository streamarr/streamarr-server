package com.streamarr.server.services.probe;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NonNull;
import org.springframework.stereotype.Component;

/**
 * The probes that playback requests wait for, from the request until the scheduler claims the probe
 * and then until the probe's execution ends. The scheduler claims a playback probe only once a
 * worker has a free unit for it, so the time until the claim is the time that playback waits for a
 * free unit.
 */
@Component
public class PlaybackProbeClaims {

  private static final String UNIT_WAIT_METRIC = "streamarr.playback.probe_unit_wait";

  private final Clock clock;
  private final Timer unitWait;
  private final AtomicInteger inProgress = new AtomicInteger();
  private final ConcurrentHashMap<UUID, Waiting> waiting = new ConcurrentHashMap<>();

  public PlaybackProbeClaims(@NonNull Clock clock, @NonNull MeterRegistry meterRegistry) {
    this.clock = clock;
    unitWait =
        Timer.builder(UNIT_WAIT_METRIC)
            .description(
                "How long a playback request waited until a worker had a free unit for its probe")
            .register(meterRegistry);
  }

  /** A playback request for the media file starts waiting for the file's probe. */
  public void waitStarted(@NonNull UUID mediaFileId, @NonNull Instant requestedAt) {
    waiting.merge(
        mediaFileId,
        new Waiting(requestedAt, 1),
        (current, _) -> new Waiting(current.since(), current.requests() + 1));
  }

  /** A playback request for the media file stops waiting, with or without the probe's outcome. */
  public void waitEnded(@NonNull UUID mediaFileId) {
    waiting.computeIfPresent(
        mediaFileId,
        (_, current) -> {
          if (current.requests() <= 1) {
            return null;
          }

          return new Waiting(current.since(), current.requests() - 1);
        });
  }

  /**
   * The scheduler claimed a probe that playback waits for. The probe counts as in progress until
   * the returned claim closes, when the probe's execution ends.
   */
  public ClaimedPlaybackProbe claimed(@NonNull UUID mediaFileId) {
    var since = waiting.remove(mediaFileId);
    if (since != null) {
      unitWait.record(Duration.between(since.since(), clock.instant()));
    }

    inProgress.incrementAndGet();
    return new ClaimedPlaybackProbe();
  }

  /** The playback probe executions that started and have not ended. */
  public int inProgress() {
    return inProgress.get();
  }

  public final class ClaimedPlaybackProbe implements AutoCloseable {

    private final AtomicBoolean ended = new AtomicBoolean();

    private ClaimedPlaybackProbe() {}

    @Override
    public void close() {
      if (ended.compareAndSet(false, true)) {
        inProgress.decrementAndGet();
      }
    }
  }

  private record Waiting(Instant since, int requests) {}
}
