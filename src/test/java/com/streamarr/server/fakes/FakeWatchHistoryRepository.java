package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.repositories.streaming.StreamSessionCompletion;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
  public int batchInsert(UUID profileId, Collection<UUID> collectableIds, int durationSeconds) {
    var watchedAt = Instant.now();
    var saved = 0;
    for (var collectableId : collectableIds) {
      if (hasCompletionAt(profileId, collectableId, watchedAt)) {
        continue;
      }

      save(
          WatchHistory.builder()
              .profileId(profileId)
              .collectableId(collectableId)
              .watchedAt(watchedAt)
              .durationSeconds(durationSeconds)
              .build());
      saved++;
    }

    return saved;
  }

  private boolean hasCompletionAt(UUID profileId, UUID collectableId, Instant watchedAt) {
    return database.values().stream()
        .anyMatch(
            wh ->
                profileId.equals(wh.getProfileId())
                    && collectableId.equals(wh.getCollectableId())
                    && watchedAt.equals(wh.getWatchedAt()));
  }

  @Override
  public int saveStreamSessionCompletion(StreamSessionCompletion completion) {
    if (hasStreamSessionCompletion(completion.streamSessionId())) {
      return 0;
    }

    save(
        WatchHistory.builder()
            .streamSessionId(completion.streamSessionId())
            .profileId(completion.profileId())
            .collectableId(completion.collectableId())
            .watchedAt(Instant.now())
            .durationSeconds(completion.durationSeconds())
            .build());
    return 1;
  }

  public boolean hasStreamSessionCompletion(UUID streamSessionId) {
    return database.values().stream()
        .anyMatch(wh -> streamSessionId.equals(wh.getStreamSessionId()));
  }

  @Override
  public int dismissAll(UUID profileId, Collection<UUID> collectableIds) {
    var now = Instant.now();
    var dismissed =
        database.values().stream()
            .filter(
                wh ->
                    profileId.equals(wh.getProfileId())
                        && collectableIds.contains(wh.getCollectableId())
                        && wh.getDismissedAt() == null)
            .toList();
    dismissed.forEach(wh -> wh.setDismissedAt(now));
    return dismissed.size();
  }

  @Override
  public void reassignProfile(UUID fromProfileId, UUID toProfileId) {
    database.values().stream()
        .filter(wh -> fromProfileId.equals(wh.getProfileId()))
        .forEach(wh -> wh.setProfileId(toProfileId));
  }

  @Override
  public Map<UUID, Instant> findLastWatchedByItemIds(
      UUID profileId, CollectableScope scope, Collection<UUID> itemIds) {
    throw new UnsupportedOperationException("LAST_WATCHED not yet implemented in fake");
  }
}
