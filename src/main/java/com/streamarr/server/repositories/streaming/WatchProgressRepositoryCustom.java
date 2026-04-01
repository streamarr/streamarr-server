package com.streamarr.server.repositories.streaming;

import java.time.Instant;
import java.util.UUID;

public interface WatchProgressRepositoryCustom {

  boolean upsertProgress(
      UUID userId,
      UUID mediaFileId,
      int positionSeconds,
      double percentComplete,
      int durationSeconds,
      Instant lastPlayedAt);

  boolean deleteIfNotWatched(UUID userId, UUID mediaFileId);
}
