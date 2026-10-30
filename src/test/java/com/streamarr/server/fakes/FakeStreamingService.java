package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.StreamingService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FakeStreamingService implements StreamingService {

  private final FakeRuntimeStreamSessionRegistry registry;
  private final List<UUID> destroyedIds = new CopyOnWriteArrayList<>();
  private final Map<UUID, RuntimeException> accessFailures = new ConcurrentHashMap<>();

  public FakeStreamingService() {
    this(new FakeRuntimeStreamSessionRegistry());
  }

  public FakeStreamingService(FakeRuntimeStreamSessionRegistry registry) {
    this.registry = registry;
  }

  public void addSession(StreamSession session) {
    registry.save(session);
  }

  public void setSession(StreamSession session) {
    addSession(session);
  }

  public void failAccess(UUID sessionId, RuntimeException failure) {
    accessFailures.put(sessionId, failure);
  }

  public List<UUID> getDestroyedIds() {
    return List.copyOf(destroyedIds);
  }

  @Override
  public Outcome<StreamSession, CreateStreamSessionRejection> createSession(
      CreateStreamSessionCommand command) {
    throw new UnsupportedOperationException("Session creation is not configured in this fake");
  }

  @Override
  public Optional<StreamSession> accessSession(PlaybackRequest request) {
    var failure = accessFailures.get(request.streamSessionId());
    if (failure != null) {
      throw failure;
    }

    return registry.findById(request.streamSessionId());
  }

  @Override
  public void destroySession(UUID sessionId) {
    registry.removeById(sessionId);
    destroyedIds.add(sessionId);
  }

  @Override
  public void destroySession(UUID sessionId, UUID profileId) {
    destroySession(sessionId);
  }

  @Override
  public Collection<StreamSession> getAllSessions() {
    return registry.findAll();
  }

  @Override
  public int getActiveSessionCount() {
    return registry.count();
  }
}
