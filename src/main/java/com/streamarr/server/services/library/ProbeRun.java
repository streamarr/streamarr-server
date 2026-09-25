package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.RequestedProbe;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The probes one scan or file discovery requested, with the inputs whose outcome each file needs.
 * Probes requested later for the same files, such as by watcher events, are not part of the run.
 */
public final class ProbeRun {

  private final MediaFileProbeTaskScheduler scheduler;
  private final Clock clock;
  private final Map<UUID, RequestedProbe> requested = new ConcurrentHashMap<>();
  private final AtomicReference<Instant> firstRequestedAt = new AtomicReference<>();

  ProbeRun(MediaFileProbeTaskScheduler scheduler, Clock clock) {
    this.scheduler = scheduler;
    this.clock = clock;
  }

  /**
   * Requests the media file's probe unless its stored outcome already matches the source. A probe
   * whose latest attempt failed is retried at once, and the run waits for that retry.
   */
  public void request(UUID mediaFileId) {
    var requestedAt = clock.instant();
    scheduler.schedule(mediaFileId).ifPresent(inputs -> track(mediaFileId, inputs, requestedAt));
  }

  /**
   * Requests the probe like {@link #request(UUID)} with a snapshot the caller already observed, so
   * the source is not read again. A source that cannot be read by then fails its probe attempt.
   */
  public void request(UUID mediaFileId, SourceFileSnapshot observed) {
    var requestedAt = clock.instant();
    track(mediaFileId, scheduler.schedule(mediaFileId, observed), requestedAt);
  }

  private void track(UUID mediaFileId, ProbeInputs inputs, Instant requestedAt) {
    firstRequestedAt.accumulateAndGet(requestedAt, ProbeRun::earlier);
    requested.put(mediaFileId, new RequestedProbe(inputs, requestedAt));
  }

  // Requests read the clock before scheduling, so a later request can finish scheduling first.
  private static Instant earlier(Instant current, Instant candidate) {
    if (current == null || candidate.isBefore(current)) {
      return candidate;
    }

    return current;
  }

  Set<UUID> mediaFileIds() {
    return Set.copyOf(requested.keySet());
  }

  Map<UUID, RequestedProbe> requested() {
    return Map.copyOf(requested);
  }

  Optional<Instant> firstRequestedAt() {
    return Optional.ofNullable(firstRequestedAt.get());
  }
}
