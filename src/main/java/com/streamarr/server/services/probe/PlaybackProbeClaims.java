package com.streamarr.server.services.probe;

import com.streamarr.server.services.concurrency.NanoClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
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
 * and then until the probe's execution ends. Queue latency runs from the earliest still-waiting
 * playback request until the probe's execution starts. It includes scheduling delays and capacity
 * waits. Requests that stop waiting before execution starts contribute no sample. Queue latency
 * counts elapsed time, so a step of the system clock cannot distort or drop a sample.
 */
@Component
public class PlaybackProbeClaims {

  private static final String QUEUE_LATENCY_METRIC = "streamarr.playback.probe_queue_latency";

  private final NanoClock nanoClock;
  private final Timer queueLatency;
  private final AtomicInteger inProgress = new AtomicInteger();
  private final ConcurrentHashMap<UUID, Set<PlaybackProbeWait>> waiting = new ConcurrentHashMap<>();

  public PlaybackProbeClaims(@NonNull NanoClock nanoClock, @NonNull MeterRegistry meterRegistry) {
    this.nanoClock = nanoClock;
    queueLatency =
        Timer.builder(QUEUE_LATENCY_METRIC)
            .description(
                "Queue latency from the earliest waiting playback request until its probe execution"
                    + " starts")
            .register(meterRegistry);
  }

  /**
   * A playback request for the media file starts waiting for the file's probe. The wait ends when
   * the scheduler claims the probe or when the returned wait closes, whichever comes first, and
   * closing one request's wait never ends another's.
   */
  public PlaybackProbeWait waitStarted(@NonNull UUID mediaFileId) {
    var wait = new PlaybackProbeWait(mediaFileId, nanoClock.nanoTime());
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
      var now = nanoClock.nanoTime();
      waits.stream()
          .mapToLong(wait -> now - wait.startedNanos)
          .max()
          .ifPresent(longest -> queueLatency.record(Duration.ofNanos(longest)));
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
    private final long startedNanos;

    private PlaybackProbeWait(UUID mediaFileId, long startedNanos) {
      this.mediaFileId = mediaFileId;
      this.startedNanos = startedNanos;
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
