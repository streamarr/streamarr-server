package com.streamarr.server.repositories.streaming;

import java.util.Collection;
import java.util.UUID;

public interface SessionProgressRepositoryCustom {

  void upsertProgress(SaveWatchProgress progress);

  void deleteBySessionId(UUID sessionId);

  void deleteByUserIdAndMediaFileIds(UUID userId, Collection<UUID> mediaFileIds);
}
