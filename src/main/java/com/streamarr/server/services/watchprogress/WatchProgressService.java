package com.streamarr.server.services.watchprogress;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.streaming.PlaybackSnapshot;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.streaming.WatchProgressRepository;
import com.streamarr.server.services.streaming.StreamSessionRepository;
import com.streamarr.server.services.watchprogress.events.MediaWatchedEvent;
import com.streamarr.server.services.watchprogress.events.PlaybackStoppedEvent;
import com.streamarr.server.services.watchprogress.events.TimelineReportedEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;

@RequiredArgsConstructor
public class WatchProgressService {

  private final StreamSessionRepository sessionRepository;
  private final WatchProgressRepository watchProgressRepository;
  private final MediaFileRepository mediaFileRepository;
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

  public Optional<WatchProgress> getProgress(UUID userId, UUID mediaFileId) {
    return watchProgressRepository.findByUserIdAndMediaFileId(userId, mediaFileId);
  }

  public Map<UUID, WatchProgress> getProgressForMediaFiles(
      UUID userId, Collection<UUID> mediaFileIds) {
    return watchProgressRepository.findByUserIdAndMediaFileIdIn(userId, mediaFileIds).stream()
        .collect(Collectors.toMap(WatchProgress::getMediaFileId, wp -> wp));
  }

  public WatchStatus getWatchStatusForCollectable(UUID userId, UUID collectableId) {
    var mediaFiles = mediaFileRepository.findByMediaId(collectableId);
    if (mediaFiles.isEmpty()) {
      return WatchStatus.UNWATCHED;
    }

    var mediaFileIds = mediaFiles.stream().map(MediaFile::getId).toList();
    var progressMap = getProgressForMediaFiles(userId, mediaFileIds);

    var watchedCount = 0;
    var inProgressCount = 0;
    for (var mf : mediaFiles) {
      var wp = progressMap.get(mf.getId());
      if (wp == null) {
        continue;
      }

      if (wp.isPlayed()) {
        watchedCount++;
      } else if (wp.getPositionSeconds() > 0) {
        inProgressCount++;
      }
    }

    return deriveWatchStatus(mediaFiles.size(), watchedCount, inProgressCount);
  }

  public void resetProgress(UUID userId, UUID collectableId) {
    var mediaFileIds =
        mediaFileRepository.findByMediaId(collectableId).stream().map(MediaFile::getId).toList();

    for (var mediaFileId : mediaFileIds) {
      watchProgressRepository.deleteByUserIdAndMediaFileId(userId, mediaFileId);
    }
  }

  public static WatchStatus deriveWatchStatus(
      int totalItems, int watchedCount, int inProgressCount) {
    if (totalItems > 0 && watchedCount == totalItems) {
      return WatchStatus.WATCHED;
    }

    if (watchedCount > 0 || inProgressCount > 0) {
      return WatchStatus.IN_PROGRESS;
    }

    return WatchStatus.UNWATCHED;
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
