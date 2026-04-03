package com.streamarr.server.services.watchprogress;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.streaming.SaveWatchProgress;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.streaming.StreamSessionRepository;
import com.streamarr.server.services.watchprogress.events.SessionProgressChangedEvent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionProgressService {

  private final StreamSessionRepository sessionRepository;
  private final SessionProgressRepository sessionProgressRepository;
  private final MediaFileRepository mediaFileRepository;
  private final WatchProgressProperties properties;
  private final WatchStatusService watchStatusService;
  private final ApplicationEventPublisher eventPublisher;

  public Optional<SessionProgress> getProgress(UUID userId, UUID mediaFileId) {
    return sessionProgressRepository.findMostRecentByUserIdAndMediaFileId(userId, mediaFileId);
  }

  public void reportStreamSessionTimeline(
      UUID userId, UUID sessionId, int positionSeconds, PlaybackState state) {
    if (positionSeconds < 0) {
      return;
    }

    var session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new SessionNotFoundException(sessionId));

    session.updatePlaybackState(positionSeconds, state);

    var durationSeconds = (int) session.getMediaProbe().duration().toSeconds();
    if (durationSeconds <= 0) {
      return;
    }

    var percentComplete = Math.min(positionSeconds * 100.0 / durationSeconds, 100.0);
    var mediaFileId = session.getMediaFileId();

    if (state == PlaybackState.STOPPED) {
      handleStoppedPlayback(
          userId, sessionId, mediaFileId, positionSeconds, percentComplete, durationSeconds);
      return;
    }

    sessionProgressRepository.upsertProgress(
        SaveWatchProgress.builder()
            .sessionId(sessionId)
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .durationSeconds(durationSeconds)
            .build());

    eventPublisher.publishEvent(
        SessionProgressChangedEvent.builder()
            .sessionId(sessionId)
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .state(state)
            .build());
  }

  private void handleStoppedPlayback(
      UUID userId,
      UUID sessionId,
      UUID mediaFileId,
      int positionSeconds,
      double percentComplete,
      int durationSeconds) {
    var remainingSeconds = durationSeconds - positionSeconds;
    var decision = evaluateStopDecision(percentComplete, remainingSeconds, durationSeconds);

    if (decision == StopDecision.DISCARD) {
      sessionProgressRepository.deleteBySessionId(sessionId);
      return;
    }

    if (decision == StopDecision.MARK_WATCHED) {
      sessionProgressRepository.deleteBySessionId(sessionId);

      var collectableId = resolveCollectableId(mediaFileId);
      watchStatusService.markWatched(userId, collectableId, Instant.now(), durationSeconds);

      eventPublisher.publishEvent(
          SessionProgressChangedEvent.builder()
              .sessionId(sessionId)
              .userId(userId)
              .mediaFileId(mediaFileId)
              .collectableId(collectableId)
              .positionSeconds(0)
              .percentComplete(100.0)
              .state(PlaybackState.STOPPED)
              .build());
      return;
    }

    // PERSIST — save position for resume
    sessionProgressRepository.upsertProgress(
        SaveWatchProgress.builder()
            .sessionId(sessionId)
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .durationSeconds(durationSeconds)
            .build());

    eventPublisher.publishEvent(
        SessionProgressChangedEvent.builder()
            .sessionId(sessionId)
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .state(PlaybackState.STOPPED)
            .build());
  }

  private UUID resolveCollectableId(UUID mediaFileId) {
    return mediaFileRepository
        .findMediaIdByMediaFileId(mediaFileId)
        .orElseThrow(() -> new IllegalStateException("MediaFile not found for id: " + mediaFileId));
  }

  private enum StopDecision {
    DISCARD,
    MARK_WATCHED,
    PERSIST
  }

  private StopDecision evaluateStopDecision(
      double percentComplete, int remainingSeconds, int durationSeconds) {
    if (isBelowResumeThreshold(percentComplete)) {
      return StopDecision.DISCARD;
    }

    if (hasReachedWatchedThreshold(percentComplete, remainingSeconds, durationSeconds)) {
      return StopDecision.MARK_WATCHED;
    }

    return StopDecision.PERSIST;
  }

  private boolean isBelowResumeThreshold(double percentComplete) {
    return percentComplete < properties.minPlayedPercent();
  }

  private boolean hasReachedWatchedThreshold(
      double percentComplete, int remainingSeconds, int durationSeconds) {
    return percentComplete >= properties.maxPlayedPercent()
        || (durationSeconds > properties.maxRemainingSeconds()
            && remainingSeconds <= properties.maxRemainingSeconds());
  }
}
