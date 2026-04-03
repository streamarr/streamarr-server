package com.streamarr.server.repositories.streaming;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

public interface WatchHistoryRepositoryCustom {

  void batchInsert(
      UUID userId, Collection<UUID> collectableIds, Instant watchedAt, int durationSeconds);

  void dismissAll(UUID userId, Collection<UUID> collectableIds);
}
