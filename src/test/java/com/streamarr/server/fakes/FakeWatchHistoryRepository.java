package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeWatchHistoryRepository extends FakeJpaRepository<WatchHistory>
    implements WatchHistoryRepository {

  @Override
  public Optional<WatchHistory> findFirstByProfileIdAndCollectableIdOrderByWatchedAtDesc(
      UUID profileId, UUID collectableId) {
    return database.values().stream()
        .filter(
            wh ->
                profileId.equals(wh.getProfileId()) && collectableId.equals(wh.getCollectableId()))
        .max(Comparator.comparing(WatchHistory::getWatchedAt));
  }

  @Override
  public List<WatchHistory> findByProfileIdAndCollectableIdIn(
      UUID profileId, Collection<UUID> collectableIds) {
    return database.values().stream()
        .filter(
            wh ->
                profileId.equals(wh.getProfileId())
                    && collectableIds.contains(wh.getCollectableId()))
        .toList();
  }

  @Override
  public void batchInsert(
      UUID profileId, Collection<UUID> collectableIds, Instant watchedAt, int durationSeconds) {
    for (var collectableId : collectableIds) {
      save(
          WatchHistory.builder()
              .profileId(profileId)
              .collectableId(collectableId)
              .watchedAt(watchedAt)
              .durationSeconds(durationSeconds)
              .build());
    }
  }

  @Override
  public void dismissAll(UUID profileId, Collection<UUID> collectableIds) {
    var now = Instant.now();
    database.values().stream()
        .filter(
            wh ->
                profileId.equals(wh.getProfileId())
                    && collectableIds.contains(wh.getCollectableId())
                    && wh.getDismissedAt() == null)
        .forEach(wh -> wh.setDismissedAt(now));
  }

  @Override
  public void reassignProfile(UUID fromProfileId, UUID toProfileId) {
    database.values().stream()
        .filter(wh -> fromProfileId.equals(wh.getProfileId()))
        .forEach(wh -> wh.setProfileId(toProfileId));
  }
}
