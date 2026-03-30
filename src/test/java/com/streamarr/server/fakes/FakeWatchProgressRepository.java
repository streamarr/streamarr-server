package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.repositories.streaming.WatchProgressRepository;
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
}
