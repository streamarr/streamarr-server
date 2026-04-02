package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.repositories.streaming.WatchProgressRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeWatchProgressRepository extends FakeJpaRepository<WatchProgress>
    implements WatchProgressRepository {

  @Override
  public Optional<WatchProgress> findByUserIdAndMediaFileId(UUID userId, UUID mediaFileId) {
    return database.values().stream()
        .filter(wp -> userId.equals(wp.getUserId()) && mediaFileId.equals(wp.getMediaFileId()))
        .findFirst();
  }

  @Override
  public List<WatchProgress> findByUserIdAndMediaFileIdIn(
      UUID userId, Collection<UUID> mediaFileIds) {
    return database.values().stream()
        .filter(wp -> userId.equals(wp.getUserId()) && mediaFileIds.contains(wp.getMediaFileId()))
        .toList();
  }

  @Override
  public void deleteByUserIdAndMediaFileId(UUID userId, UUID mediaFileId) {
    database
        .entrySet()
        .removeIf(
            entry ->
                userId.equals(entry.getValue().getUserId())
                    && mediaFileId.equals(entry.getValue().getMediaFileId()));
  }

  @Override
  public void deleteByUserIdAndMediaFileIdIn(UUID userId, Collection<UUID> mediaFileIds) {
    database
        .entrySet()
        .removeIf(
            entry ->
                userId.equals(entry.getValue().getUserId())
                    && mediaFileIds.contains(entry.getValue().getMediaFileId()));
  }

  @Override
  public boolean upsertProgress(
      UUID userId,
      UUID mediaFileId,
      int positionSeconds,
      double percentComplete,
      int durationSeconds,
      Instant lastPlayedAt) {
    var existing = findByUserIdAndMediaFileId(userId, mediaFileId);
    if (existing.isPresent()) {
      var wp = existing.get();
      if (wp.isPlayed()) {
        return false;
      }
      wp.setPositionSeconds(positionSeconds);
      wp.setPercentComplete(percentComplete);
      wp.setDurationSeconds(durationSeconds);
      wp.setLastPlayedAt(lastPlayedAt);
      return true;
    }
    save(
        WatchProgress.builder()
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .durationSeconds(durationSeconds)
            .lastPlayedAt(lastPlayedAt)
            .build());
    return true;
  }

  @Override
  public boolean deleteIfNotWatched(UUID userId, UUID mediaFileId) {
    var existing = findByUserIdAndMediaFileId(userId, mediaFileId);
    if (existing.isPresent() && existing.get().isPlayed()) {
      return false;
    }
    return database
        .entrySet()
        .removeIf(
            entry ->
                userId.equals(entry.getValue().getUserId())
                    && mediaFileId.equals(entry.getValue().getMediaFileId()));
  }
}
