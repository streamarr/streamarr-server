package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.streaming.StreamSessionRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class FakeStreamSessionRepository implements StreamSessionRepository {

  private final Map<UUID, StreamSession> sessions = new HashMap<>();

  @Override
  public void save(StreamSession session) {
    sessions.put(session.getSessionId(), session);
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
  public Collection<StreamSession> findAll() {
    return sessions.values();
  }

  @Override
  public int count() {
    return sessions.size();
  }
}
