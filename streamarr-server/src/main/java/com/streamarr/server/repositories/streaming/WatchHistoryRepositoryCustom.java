package com.streamarr.server.repositories.streaming;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

public interface WatchHistoryRepositoryCustom {

  void batchInsert(
      UUID profileId, Collection<UUID> collectableIds, Instant watchedAt, int durationSeconds);

  void dismissAll(UUID profileId, Collection<UUID> collectableIds);

  void reassignProfile(UUID fromProfileId, UUID toProfileId);
}
