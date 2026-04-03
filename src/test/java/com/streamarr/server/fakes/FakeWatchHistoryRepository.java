package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeWatchHistoryRepository extends FakeJpaRepository<WatchHistory>
    implements WatchHistoryRepository {

  @Override
  public Optional<WatchHistory> findFirstByUserIdAndCollectableIdOrderByWatchedAtDesc(
      UUID userId, UUID collectableId) {
    return database.values().stream()
        .filter(wh -> userId.equals(wh.getUserId()) && collectableId.equals(wh.getCollectableId()))
        .max(Comparator.comparing(WatchHistory::getWatchedAt));
  }

  @Override
  public List<WatchHistory> findByUserIdAndCollectableIdIn(
      UUID userId, Collection<UUID> collectableIds) {
    return database.values().stream()
        .filter(
            wh -> userId.equals(wh.getUserId()) && collectableIds.contains(wh.getCollectableId()))
        .toList();
  }
}
