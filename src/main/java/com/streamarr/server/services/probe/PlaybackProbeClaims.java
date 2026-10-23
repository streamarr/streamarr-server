package com.streamarr.server.services.probe;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
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
  private final ConcurrentHashMap<UUID, Set<PlaybackProbeWait>> waiting = new ConcurrentHashMap<>();

  public PlaybackProbeClaims(@NonNull Clock clock, @NonNull MeterRegistry meterRegistry) {
    this.clock = clock;
    unitWait =
        Timer.builder(UNIT_WAIT_METRIC)
            .description(
                "How long a playback request waited until a worker had a free unit for its probe")
            .register(meterRegistry);
  }

  /**
   * A playback request for the media file starts waiting for the file's probe. The wait ends when
   * the scheduler claims the probe or when the returned wait closes, whichever comes first, and
   * closing one request's wait never ends another's.
   */
  public PlaybackProbeWait waitStarted(@NonNull UUID mediaFileId, @NonNull Instant requestedAt) {
    var wait = new PlaybackProbeWait(mediaFileId, requestedAt);
    waiting.merge(mediaFileId, Set.of(wait), PlaybackProbeClaims::union);
    return wait;
  }

  /**
   * The scheduler claimed a probe that playback waits for. The probe counts as in progress until
   * the returned claim closes, when the probe's execution ends.
   */
  public ClaimedPlaybackProbe claimed(@NonNull UUID mediaFileId) {
    var waits = waiting.remove(mediaFileId);
    if (waits != null) {
      waits.stream()
          .map(wait -> wait.since)
          .min(Comparator.naturalOrder())
          .ifPresent(since -> unitWait.record(Duration.between(since, clock.instant())));
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

  private static Set<PlaybackProbeWait> union(
      Set<PlaybackProbeWait> current, Set<PlaybackProbeWait> added) {
    var waits = new HashSet<>(current);
    waits.addAll(added);
    return Set.copyOf(waits);
  }

  /** One playback request's wait for a probe. */
  public final class PlaybackProbeWait implements AutoCloseable {

    private final UUID mediaFileId;
    private final Instant since;

    private PlaybackProbeWait(UUID mediaFileId, Instant since) {
      this.mediaFileId = mediaFileId;
      this.since = since;
    }

    @Override
    public void close() {
      waiting.computeIfPresent(
          mediaFileId,
          (_, waits) -> {
            if (!waits.contains(this)) {
              return waits;
            }

            var remaining = new HashSet<>(waits);
            remaining.remove(this);
            if (remaining.isEmpty()) {
              return null;
            }

            return Set.copyOf(remaining);
          });
    }
  }
}
