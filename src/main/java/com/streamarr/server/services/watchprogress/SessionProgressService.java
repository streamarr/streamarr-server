package com.streamarr.server.services.watchprogress;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.streaming.SaveWatchProgress;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.StreamSessionCompletion;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.watchprogress.events.WatchStateChangedEvent;
import java.util.Set;
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

  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final SessionProgressRepository sessionProgressRepository;
  private final WatchHistoryRepository watchHistoryRepository;
  private final MediaFileRepository mediaFileRepository;
  private final WatchProgressProperties properties;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public void reportStreamSessionTimeline(
      UUID profileId, UUID sessionId, int positionSeconds, PlaybackState state) {
    if (positionSeconds < 0) {
      return;
    }

    var session =
        runtimeRegistry
            .findById(sessionId)
            .orElseThrow(() -> new SessionNotFoundException(sessionId));
    // Return the same error for unowned and missing sessions to conceal their existence.
    // Log the ownership mismatch so operators can diagnose incorrect access attempts.
    if (!session.isOwnedBy(profileId)) {
      log.warn(
          "Timeline report for session {} rejected: profile {} does not own it",
          sessionId,
          profileId);
      throw new SessionNotFoundException(sessionId);
    }

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

    persistProgress(profileId, context);
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
      case DISCARD -> discardProgress(profileId, ctx);
      case MARK_WATCHED -> markSessionWatched(profileId, ctx);
      case PERSIST -> persistProgress(profileId, ctx);
    }
  }

  private void discardProgress(UUID profileId, PlaybackContext ctx) {
    if (sessionProgressRepository.deleteBySessionId(ctx.sessionId()) == 0) {
      return;
    }

    publishChange(profileId, ctx);
  }

  private void markSessionWatched(UUID profileId, PlaybackContext ctx) {
    var collectableId = resolveCollectableId(ctx.mediaFileId());
    var deleted = sessionProgressRepository.deleteBySessionId(ctx.sessionId());
    var saved =
        watchHistoryRepository.saveStreamSessionCompletion(
            StreamSessionCompletion.builder()
                .streamSessionId(ctx.sessionId())
                .profileId(profileId)
                .collectableId(collectableId)
                .durationSeconds(ctx.durationSeconds())
                .build());
    if (deleted + saved == 0) {
      return;
    }

    publishChange(profileId, collectableId);
  }

  private void persistProgress(UUID profileId, PlaybackContext ctx) {
    var changed =
        sessionProgressRepository.upsertProgress(
            SaveWatchProgress.builder()
                .sessionId(ctx.sessionId())
                .profileId(profileId)
                .mediaFileId(ctx.mediaFileId())
                .positionSeconds(ctx.positionSeconds())
                .percentComplete(ctx.percentComplete())
                .durationSeconds(ctx.durationSeconds())
                .build());
    if (!changed) {
      return;
    }

    publishChange(profileId, ctx);
  }

  private void publishChange(UUID profileId, PlaybackContext ctx) {
    publishChange(profileId, resolveCollectableId(ctx.mediaFileId()));
  }

  private void publishChange(UUID profileId, UUID collectableId) {
    eventPublisher.publishEvent(new WatchStateChangedEvent(profileId, Set.of(collectableId)));
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
