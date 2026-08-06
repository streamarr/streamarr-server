package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface RuntimeStreamSessionRegistry {

  void save(StreamSession session);

  /**
   * Stamps a still-registered session's last-accessed time. A read-then-{@link #save} would
   * re-insert a session a concurrent destroy removed in between; this cannot, because it never adds
   * a key.
   */
  void touch(UUID sessionId, Instant accessedAt);

  Optional<StreamSession> findById(UUID sessionId);

  Optional<StreamSession> removeById(UUID sessionId);

  Collection<StreamSession> findAll();

  int count();
}
