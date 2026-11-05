package com.streamarr.server.services.streaming.local;

import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class InMemoryStreamSessionRegistry implements RuntimeStreamSessionRegistry {

  private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();

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
  public void updatePlaybackState(UUID sessionId, int positionSeconds, PlaybackState state) {
    sessions.computeIfPresent(
        sessionId,
        (_, session) -> {
          session.updatePlaybackState(positionSeconds, state);
          return session;
        });
  }

  @Override
  public Optional<StreamSession> findById(UUID sessionId) {
    return Optional.ofNullable(sessions.get(sessionId));
  }

  @Override
  public Optional<StreamSession> removeById(UUID sessionId) {
    return Optional.ofNullable(sessions.remove(sessionId));
  }

  @Override
  public boolean removeIfLastAccessedBefore(UUID sessionId, Instant cutoff) {
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

  @Override
  public Collection<StreamSession> findAll() {
    return Collections.unmodifiableCollection(sessions.values());
  }

  @Override
  public int count() {
    return sessions.size();
  }
}
