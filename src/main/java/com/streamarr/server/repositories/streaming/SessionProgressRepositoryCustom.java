package com.streamarr.server.repositories.streaming;

import com.streamarr.server.domain.streaming.SessionProgress;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface SessionProgressRepositoryCustom {

  boolean upsertProgress(SaveWatchProgress progress);

  Optional<SessionProgress> findMostRecentByProfileIdAndMediaFileId(
      UUID profileId, UUID mediaFileId);

  void deleteBySessionId(UUID sessionId);

  void deleteByProfileIdAndMediaFileIds(UUID profileId, Collection<UUID> mediaFileIds);

  void reassignProfile(UUID fromProfileId, UUID toProfileId);
}
