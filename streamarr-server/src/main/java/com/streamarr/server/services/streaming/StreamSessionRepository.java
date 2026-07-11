package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface StreamSessionRepository {

  void save(StreamSession session);

  Optional<StreamSession> findById(UUID sessionId);

  Optional<StreamSession> removeById(UUID sessionId);

  Collection<StreamSession> findAll();

  int count();
}
