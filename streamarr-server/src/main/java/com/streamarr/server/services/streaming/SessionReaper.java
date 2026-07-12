package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionReaper {

  private final StreamingService streamingService;
  private final PlaybackTranscodeJobService playbackTranscodeJobService;
  private final StreamingProperties properties;
  private final TranscodeCapacityTracker transcodeCapacityTracker;

  @Scheduled(fixedDelayString = "${streaming.reaper-interval-ms:15000}")
  public void reapSessions() {
    var now = Instant.now();
    for (var session : streamingService.getAllSessions()) {
      processSession(session, now);
    }
  }

  private void processSession(StreamSession session, Instant now) {
    var capacityClaim = transcodeCapacityTracker.activeClaim(session.getSessionId());
    switch (playbackTranscodeJobService.inspectActive(session.getSessionId())) {
      case ActiveTranscodeJobInspection.None _ ->
          capacityClaim.ifPresent(transcodeCapacityTracker::releaseActive);
      case ActiveTranscodeJobInspection.Unavailable(var jobRef) ->
          log.warn(
              "Unable to inspect active transcode for session {} job {}",
              session.getSessionId(),
              jobRef);
      case ActiveTranscodeJobInspection.Observed(var observation, _) ->
          processObservation(
              session, now, new CapacityObservation(observation.state(), capacityClaim));
    }
  }

  private void processObservation(
      StreamSession session, Instant now, CapacityObservation observation) {
    var state = observation.state();
    if (state == TranscodeJobState.ADMITTING || state == TranscodeJobState.RUNNING) {
      if (isIdle(session, now)) {
        suspendSession(session.getSessionId(), observation.claim());
      }
      return;
    }
    observation.claim().ifPresent(transcodeCapacityTracker::releaseActive);
    if (state == TranscodeJobState.FAILED) {
      log.warn(
          "Transcode failed for session {}; missing output may be replaced",
          session.getSessionId());
    }
  }

  private boolean isIdle(StreamSession session, Instant now) {
    var idleSeconds = now.getEpochSecond() - session.getLastAccessedAt().getEpochSecond();
    return idleSeconds > properties.sessionTimeout().toSeconds();
  }

  private void suspendSession(
      UUID sessionId, Optional<TranscodeCapacityTracker.ActiveClaim> capacityClaim) {
    log.info("Suspending idle session {}", sessionId);
    if (playbackTranscodeJobService.suspend(sessionId) == RuntimeTranscodeCleanup.PENDING) {
      log.warn("Suspension cleanup remains pending for session {}", sessionId);
      return;
    }
    capacityClaim.ifPresent(transcodeCapacityTracker::releaseActive);
  }

  private record CapacityObservation(
      TranscodeJobState state, Optional<TranscodeCapacityTracker.ActiveClaim> claim) {}
}
