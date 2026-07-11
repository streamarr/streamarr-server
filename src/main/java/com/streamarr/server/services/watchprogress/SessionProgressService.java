package com.streamarr.server.services.watchprogress;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.streaming.SaveWatchProgress;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.streaming.StreamSessionLifecycleTransactions;
import com.streamarr.server.services.streaming.StreamSessionRepository;
import com.streamarr.server.services.watchprogress.events.ItemWatchedEvent;
import com.streamarr.server.services.watchprogress.events.SessionProgressChangedEvent;
import com.streamarr.server.services.watchprogress.events.StreamSessionTimelineCommittedEvent;
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
  private final StreamSessionLifecycleTransactions lifecycleTransactions;
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
    // Unowned reads as missing — same SessionNotFound as a vanished session, never an
    // ownership error (no existence oracle); the warn keeps the miss visible to operators.
    if (!session.isOwnedBy(profileId)) {
      log.warn(
          "Timeline report for session {} rejected: profile {} does not own it",
          sessionId,
          profileId);
      throw new SessionNotFoundException(sessionId);
    }

    var committedAccess =
        lifecycleTransactions
            .touchIfActiveAndOwnedBy(sessionId, profileId)
            .orElseThrow(() -> new SessionNotFoundException(sessionId));

    var durationSeconds = (int) session.getMediaProbe().duration().toSeconds();
    if (durationSeconds > 0) {
      var percentComplete = Math.min(positionSeconds * 100.0 / durationSeconds, 100.0);
      var context =
          new PlaybackContext(
              sessionId,
              session.getMediaFileId(),
              positionSeconds,
              percentComplete,
              durationSeconds);

      if (state == PlaybackState.STOPPED) {
        handleStoppedPlayback(profileId, context);
      } else {
        persistProgress(profileId, context, state);
      }
    }

    eventPublisher.publishEvent(
        StreamSessionTimelineCommittedEvent.builder()
            .sessionId(sessionId)
            .positionSeconds(positionSeconds)
            .state(state)
            .accessedAt(committedAccess)
            .build());
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
