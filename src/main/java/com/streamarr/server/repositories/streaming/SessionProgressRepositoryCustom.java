package com.streamarr.server.repositories.streaming;

import java.util.Collection;
import java.util.UUID;

public interface SessionProgressRepositoryCustom {

  boolean upsertProgress(SaveWatchProgress progress);

  void deleteBySessionId(UUID sessionId);

  void deleteByUserIdAndMediaFileIds(UUID userId, Collection<UUID> mediaFileIds);
}
