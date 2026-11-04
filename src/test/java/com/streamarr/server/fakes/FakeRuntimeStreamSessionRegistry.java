package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class FakeRuntimeStreamSessionRegistry implements RuntimeStreamSessionRegistry {

  private final Map<UUID, StreamSession> sessions = new ConcurrentHashMap<>();
  private Runnable beforeNextRemoval = () -> {};

  /** Runs once just before the next removal decides, as a request that arrives then would. */
  public void beforeNextRemoval(Runnable callback) {
    beforeNextRemoval = callback;
  }

  @Override
  public void save(StreamSession session) {
    sessions.put(session.getSessionId(), session);
  }

  @Override
  public void touch(UUID sessionId, Instant accessedAt) {
    sessions.computeIfPresent(
        sessionId,
        (_, session) -> {
          session.setLastAccessedAt(accessedAt);
          return session;
        });
  }

  @Override
  public Optional<StreamSession> findById(UUID sessionId) {
    return Optional.ofNullable(sessions.get(sessionId));
  }

  @Override
  public Optional<StreamSession> removeById(UUID sessionId) {
    runBeforeRemoval();
    return Optional.ofNullable(sessions.remove(sessionId));
  }

  @Override
  public boolean removeIfLastAccessedBefore(UUID sessionId, Instant cutoff) {
    runBeforeRemoval();
    var removed = new AtomicBoolean();
    sessions.computeIfPresent(
        sessionId,
        (_, session) -> {
          if (!session.getLastAccessedAt().isBefore(cutoff)) {
            return session;
          }

          removed.set(true);
          return null;
        });
    return removed.get();
  }

  private void runBeforeRemoval() {
    var callback = beforeNextRemoval;
    beforeNextRemoval = () -> {};
    callback.run();
  }

  @Override
  public Collection<StreamSession> findAll() {
    return Collections.unmodifiableCollection(sessions.values());
  }

  @Override
  public int count() {
    return sessions.size();
  }
}
