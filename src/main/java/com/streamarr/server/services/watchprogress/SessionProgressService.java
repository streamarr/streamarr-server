package com.streamarr.server.services.watchprogress;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.streaming.SaveWatchProgress;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.streaming.StreamSessionRepository;
import com.streamarr.server.services.watchprogress.events.ItemWatchedEvent;
import com.streamarr.server.services.watchprogress.events.SessionProgressChangedEvent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  @Transactional
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
      var context =
          new PlaybackContext(
              sessionId, mediaFileId, positionSeconds, percentComplete, durationSeconds);
      handleStoppedPlayback(userId, context);
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

  private record PlaybackContext(
      UUID sessionId,
      UUID mediaFileId,
      int positionSeconds,
      double percentComplete,
      int durationSeconds) {}

  private void handleStoppedPlayback(UUID userId, PlaybackContext ctx) {
    var remainingSeconds = ctx.durationSeconds() - ctx.positionSeconds();
    var decision =
        evaluateStopDecision(ctx.percentComplete(), remainingSeconds, ctx.durationSeconds());

    if (decision == StopDecision.DISCARD) {
      sessionProgressRepository.deleteBySessionId(ctx.sessionId());
      return;
    }

    if (decision == StopDecision.MARK_WATCHED) {
      sessionProgressRepository.deleteBySessionId(ctx.sessionId());

      var collectableId = resolveCollectableId(ctx.mediaFileId());
      watchStatusService.markWatched(userId, collectableId, Instant.now(), ctx.durationSeconds());

      eventPublisher.publishEvent(
          ItemWatchedEvent.builder()
              .sessionId(ctx.sessionId())
              .userId(userId)
              .mediaFileId(ctx.mediaFileId())
              .collectableId(collectableId)
              .build());
      return;
    }

    sessionProgressRepository.upsertProgress(
        SaveWatchProgress.builder()
            .sessionId(ctx.sessionId())
            .userId(userId)
            .mediaFileId(ctx.mediaFileId())
            .positionSeconds(ctx.positionSeconds())
            .percentComplete(ctx.percentComplete())
            .durationSeconds(ctx.durationSeconds())
            .build());

    eventPublisher.publishEvent(
        SessionProgressChangedEvent.builder()
            .sessionId(ctx.sessionId())
            .userId(userId)
            .mediaFileId(ctx.mediaFileId())
            .positionSeconds(ctx.positionSeconds())
            .percentComplete(ctx.percentComplete())
            .state(PlaybackState.STOPPED)
            .build());
  }

  private UUID resolveCollectableId(UUID mediaFileId) {
    return mediaFileRepository
        .findMediaIdByMediaFileId(mediaFileId)
        .orElseThrow(() -> new MediaFileNotFoundException(mediaFileId));
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
