package com.streamarr.server.repositories.streaming;

import java.util.Collection;
import java.util.UUID;

public interface SessionProgressRepositoryCustom {

  /**
   * Returns true when the upsert inserted the row or changed its values. Saves nothing once the
   * stream session has saved its completion, so a report that arrives after the completing stop
   * cannot bring the session's progress back.
   */
  boolean upsertProgress(SaveWatchProgress progress);

  /** Returns the number of rows deleted. */
  int deleteBySessionId(UUID sessionId);

  /** Returns the number of rows deleted. */
  int deleteByProfileIdAndMediaFileIds(UUID profileId, Collection<UUID> mediaFileIds);

  void reassignProfile(UUID fromProfileId, UUID toProfileId);
}
