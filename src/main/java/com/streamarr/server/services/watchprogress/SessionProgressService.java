package com.streamarr.server.services.watchprogress;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.streaming.SaveWatchProgress;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.streaming.StreamSessionRepository;
import com.streamarr.server.services.watchprogress.events.ItemWatchedEvent;
import com.streamarr.server.services.watchprogress.events.SessionProgressChangedEvent;
import java.time.Instant;
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

  @Transactional
  public void reportStreamSessionTimeline(
      UUID profileId, UUID sessionId, int positionSeconds, PlaybackState state) {
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
    var context =
        new PlaybackContext(
            sessionId, session.getMediaFileId(), positionSeconds, percentComplete, durationSeconds);

    if (state == PlaybackState.STOPPED) {
      handleStoppedPlayback(profileId, context);
      return;
    }

    persistProgress(profileId, context, state);
  }

  private record PlaybackContext(
      UUID sessionId,
      UUID mediaFileId,
      int positionSeconds,
      double percentComplete,
      int durationSeconds) {}

  private void handleStoppedPlayback(UUID profileId, PlaybackContext ctx) {
    var remainingSeconds = ctx.durationSeconds() - ctx.positionSeconds();
    var decision =
        evaluateStopDecision(ctx.percentComplete(), remainingSeconds, ctx.durationSeconds());

    switch (decision) {
      case DISCARD -> sessionProgressRepository.deleteBySessionId(ctx.sessionId());
      case MARK_WATCHED -> markSessionWatched(profileId, ctx);
      case PERSIST -> persistProgress(profileId, ctx, PlaybackState.STOPPED);
    }
  }

  private void markSessionWatched(UUID profileId, PlaybackContext ctx) {
    sessionProgressRepository.deleteBySessionId(ctx.sessionId());

    var collectableId = resolveCollectableId(ctx.mediaFileId());
    watchStatusService.markWatched(
        profileId,
        collectableId,
        CollectableScope.DIRECT_MEDIA,
        Instant.now(),
        ctx.durationSeconds());

    eventPublisher.publishEvent(
        ItemWatchedEvent.builder()
            .sessionId(ctx.sessionId())
            .profileId(profileId)
            .mediaFileId(ctx.mediaFileId())
            .collectableId(collectableId)
            .build());
  }

  private void persistProgress(UUID profileId, PlaybackContext ctx, PlaybackState state) {
    sessionProgressRepository.upsertProgress(
        SaveWatchProgress.builder()
            .sessionId(ctx.sessionId())
            .profileId(profileId)
            .mediaFileId(ctx.mediaFileId())
            .positionSeconds(ctx.positionSeconds())
            .percentComplete(ctx.percentComplete())
            .durationSeconds(ctx.durationSeconds())
            .build());

    eventPublisher.publishEvent(
        SessionProgressChangedEvent.builder()
            .sessionId(ctx.sessionId())
            .profileId(profileId)
            .mediaFileId(ctx.mediaFileId())
            .positionSeconds(ctx.positionSeconds())
            .percentComplete(ctx.percentComplete())
            .state(state)
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
