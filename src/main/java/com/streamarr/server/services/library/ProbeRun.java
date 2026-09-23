package com.streamarr.server.services.library;

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
    scheduler
        .schedule(mediaFileId)
        .ifPresent(
            inputs -> {
              firstRequestedAt.compareAndSet(null, requestedAt);
              requested.put(mediaFileId, new RequestedProbe(inputs, requestedAt));
            });
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
