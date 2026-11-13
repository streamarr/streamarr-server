package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Builder
public class SessionReaper {

  // Below this free share of the segment folder's file system, idle sessions go before they expire.
  private static final double MINIMUM_FREE_FRACTION = 0.10;
  private static final String EVICTION_METRIC = "streamarr.streaming.disk_pressure_evictions";

  private final StreamingService streamingService;
  private final StreamingProperties properties;
  private final ProducerLifecycleService producerLifecycle;
  private final PlaybackSessionService playbackSessionService;
  private final ServerSettingsService serverSettings;
  private final SegmentFolderSpace segmentFolderSpace;
  private final MeterRegistry meterRegistry;
  private final ContainedFailureMetrics containedFailures;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${streaming.reaper-interval-ms:15000}")
  public void reapSessions() {
    var now = clock.instant();
    var retention = readRetention();
    retention.ifPresent(duration -> reapExpiredPlayback(now, duration));
    // Without the retention nothing expires this sweep; suspension and eviction do not need it.
    Predicate<StreamSession> isExpired =
        session -> retention.filter(duration -> isExpired(session, now, duration)).isPresent();
    for (var session : streamingService.getAllSessions()) {
      try {
        processSession(session, now, isExpired);
      } catch (RuntimeException e) {
        log.error("Failed to reap session {}", session.getSessionId(), e);
      }
    }

    evictIdleSessionsUnderDiskPressure(now);
  }

  // Dead producers are not the reaper's concern: detection and recovery happen at request time in
  // SegmentDeliveryCoordinator, and a dead producer nobody watches is just an idle session.
  private void processSession(
      StreamSession session, Instant now, Predicate<StreamSession> isExpired) {
    if (isExpired.test(session)) {
      log.info("Reaping expired session {}", session.getSessionId());
      destroy(session.getSessionId());
      return;
    }

    if (isIdle(session, now) && session.hasActiveTranscodes()) {
      log.info("Suspending idle session {}", session.getSessionId());
      producerLifecycle.suspend(session.getSessionId());
    }
  }

  private Optional<Duration> readRetention() {
    try {
      return Optional.of(serverSettings.idleSegmentRetention());
    } catch (RuntimeException failure) {
      containedFailures.countRetentionReadFailure();
      log.warn("Failed to read the idle segment retention; no session expires this sweep", failure);
      return Optional.empty();
    }
  }

  private void evictIdleSessionsUnderDiskPressure(Instant now) {
    try {
      // A direct-play stream session stores no segments, so ending it frees no space.
      var oldestFirst =
          streamingService.getAllSessions().stream()
              .filter(session -> session.getDelivery() == StreamDelivery.HLS)
              .filter(session -> isIdle(session, now))
              .sorted(Comparator.comparing(StreamSession::getLastAccessedAt))
              .toList();
      for (var session : oldestFirst) {
        if (!isUnderDiskPressure()) {
          return;
        }

        evict(session.getSessionId());
      }
    } catch (UncheckedIOException failure) {
      containedFailures.countDiskSpaceReadFailure();
      log.warn("Failed to read the free space of the segment folder", failure);
    }
  }

  private boolean isUnderDiskPressure() {
    return segmentFolderSpace.freeFraction() < MINIMUM_FREE_FRACTION;
  }

  private void evict(UUID sessionId) {
    var lastAccessCutoff = clock.instant().minus(properties.sessionTimeout());
    try {
      // The removal is one step with request access, so a session a player resumed stays.
      if (!streamingService.destroySessionIfLastAccessedBefore(sessionId, lastAccessCutoff)) {
        return;
      }

      log.info("Evicted idle session {} to free disk space", sessionId);
      meterRegistry.counter(EVICTION_METRIC).increment();
    } catch (RuntimeException e) {
      log.error("Failed to evict session {}", sessionId, e);
    }

    closePlaybackSession(sessionId);
  }

  private void reapExpiredPlayback(Instant now, Duration retention) {
    try {
      playbackSessionService.reapExpired(now, retention);
    } catch (RuntimeException failure) {
      log.warn("Failed to reap expired logical playback sessions", failure);
    }
  }

  private void destroy(UUID streamSessionId) {
    closePlaybackSession(streamSessionId);
    streamingService.destroySession(streamSessionId);
  }

  private void closePlaybackSession(UUID streamSessionId) {
    try {
      playbackSessionService.closeOwningStream(streamSessionId);
    } catch (RuntimeException failure) {
      log.warn("Failed to close logical playback for expired stream {}", streamSessionId, failure);
    }
  }

  private boolean isExpired(StreamSession session, Instant now, Duration retention) {
    var idleSeconds = now.getEpochSecond() - session.getLastAccessedAt().getEpochSecond();
    return idleSeconds > retention.toSeconds();
  }

  private boolean isIdle(StreamSession session, Instant now) {
    var idleSeconds = now.getEpochSecond() - session.getLastAccessedAt().getEpochSecond();
    return idleSeconds > properties.sessionTimeout().toSeconds();
  }
}
