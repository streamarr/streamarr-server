package com.streamarr.server.services.watchprogress;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.streaming.PlaybackSnapshot;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.streaming.WatchProgressRepository;
import com.streamarr.server.services.streaming.StreamSessionRepository;
import com.streamarr.server.services.watchprogress.events.MediaWatchedEvent;
import com.streamarr.server.services.watchprogress.events.PlaybackStoppedEvent;
import com.streamarr.server.services.watchprogress.events.TimelineReportedEvent;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;

@RequiredArgsConstructor
public class WatchProgressService {

  private final StreamSessionRepository sessionRepository;
  private final WatchProgressRepository watchProgressRepository;
  private final WatchProgressProperties properties;
  private final ApplicationEventPublisher eventPublisher;

  public void reportTimeline(
      UUID userId, UUID sessionId, int positionSeconds, PlaybackState state) {
    var session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new SessionNotFoundException(sessionId));

    session.updatePlayback(new PlaybackSnapshot(positionSeconds, state, Instant.now()));

    var durationSeconds = (int) session.getMediaProbe().duration().toSeconds();
    if (durationSeconds <= 0) {
      return;
    }

    var percentComplete = Math.min(positionSeconds * 100.0 / durationSeconds, 100.0);
    var remainingSeconds = durationSeconds - positionSeconds;
    var mediaFileId = session.getMediaFileId();

    if (state == PlaybackState.STOPPED) {
      eventPublisher.publishEvent(
          new PlaybackStoppedEvent(
              userId, sessionId, mediaFileId, positionSeconds, percentComplete));
    }

    var existing = watchProgressRepository.findByUserIdAndMediaFileId(userId, mediaFileId);
    if (existing.isPresent() && existing.get().isPlayed()) {
      return;
    }

    if (state == PlaybackState.STOPPED && percentComplete < properties.minResumePercent()) {
      watchProgressRepository.deleteByUserIdAndMediaFileId(userId, mediaFileId);
      return;
    }

    var watched =
        state == PlaybackState.STOPPED
            && (percentComplete >= properties.maxResumePercent()
                || remainingSeconds <= properties.maxRemainingSeconds());

    var lastPlayedAt = watched ? Instant.now() : null;
    var effectivePosition = watched ? 0 : positionSeconds;

    upsertProgress(
        userId, mediaFileId, effectivePosition, percentComplete, durationSeconds, lastPlayedAt);

    eventPublisher.publishEvent(
        new TimelineReportedEvent(userId, mediaFileId, effectivePosition, percentComplete, state));

    if (watched) {
      eventPublisher.publishEvent(new MediaWatchedEvent(userId, mediaFileId));
    }
  }

  private void upsertProgress(
      UUID userId,
      UUID mediaFileId,
      int positionSeconds,
      double percentComplete,
      int durationSeconds,
      Instant lastPlayedAt) {
    var existing = watchProgressRepository.findByUserIdAndMediaFileId(userId, mediaFileId);

    if (existing.isPresent()) {
      var wp = existing.get();
      wp.setPositionSeconds(positionSeconds);
      wp.setPercentComplete(percentComplete);
      wp.setDurationSeconds(durationSeconds);
      wp.setLastPlayedAt(lastPlayedAt);
      watchProgressRepository.save(wp);
      return;
    }

    var wp =
        WatchProgress.builder()
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .durationSeconds(durationSeconds)
            .lastPlayedAt(lastPlayedAt)
            .build();
    watchProgressRepository.save(wp);
  }
}
