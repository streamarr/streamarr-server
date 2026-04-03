package com.streamarr.server.repositories.streaming;

import java.util.UUID;

public interface SessionProgressRepositoryCustom {

  void upsertProgress(SaveProgressCommand command);

  void deleteBySessionId(UUID sessionId);
}
