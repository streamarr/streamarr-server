package com.streamarr.server.repositories.streaming;

import com.streamarr.server.domain.streaming.CollectableScope;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface WatchHistoryRepositoryCustom {

  /** Stamps each completion with the database clock, the clock that stamps session progress. */
  void batchInsert(UUID profileId, Collection<UUID> collectableIds, int durationSeconds);

  void dismissAll(UUID profileId, Collection<UUID> collectableIds);

  void reassignProfile(UUID fromProfileId, UUID toProfileId);

  /** Returns the last-watched time of each given item that the Profile has watched. */
  Map<UUID, Instant> findLastWatchedByItemIds(
      UUID profileId, CollectableScope scope, Collection<UUID> itemIds);
}
