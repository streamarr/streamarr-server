package com.streamarr.server.services.watchprogress;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.streaming.PlaybackSnapshot;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.streaming.WatchProgressRepository;
import com.streamarr.server.services.streaming.StreamSessionRepository;
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
    var mediaFileId = session.getMediaFileId();

    var existing = watchProgressRepository.findByUserIdAndMediaFileId(userId, mediaFileId);

    if (existing.isPresent()) {
      var wp = existing.get();
      wp.setPositionSeconds(positionSeconds);
      wp.setPercentComplete(percentComplete);
      wp.setDurationSeconds(durationSeconds);
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
            .build();
    watchProgressRepository.save(wp);
  }
}
