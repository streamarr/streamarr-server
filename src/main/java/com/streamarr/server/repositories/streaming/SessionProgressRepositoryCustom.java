package com.streamarr.server.repositories.streaming;

import java.util.UUID;

public interface SessionProgressRepositoryCustom {

  boolean upsertProgress(SaveProgressCommand command);

  boolean deleteIfNotWatched(UUID userId, UUID mediaFileId);
}
