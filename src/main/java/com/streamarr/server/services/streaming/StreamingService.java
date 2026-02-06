package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface StreamingService {

  StreamSession createSession(UUID mediaFileId, StreamingOptions options);

  Optional<StreamSession> accessSession(UUID sessionId);

  void destroySession(UUID sessionId);

  StreamSession seekSession(UUID sessionId, int positionSeconds);

  Collection<StreamSession> getAllSessions();

  int getActiveSessionCount();

  void resumeSessionIfNeeded(UUID sessionId, String segmentName);
}
