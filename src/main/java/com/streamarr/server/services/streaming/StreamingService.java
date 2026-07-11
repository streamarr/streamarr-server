package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StreamingService {

  StreamSession createSession(CreateRuntimeStreamSessionCommand command);

  Optional<StreamSession> accessSession(UUID sessionId);

  /** System destroy — no caller identity; reserved for the reaper, cleanup, and shutdown. */
  void destroySession(UUID sessionId);

  /** Idempotent runtime fence used after durable authority has already become terminal. */
  default boolean terminateRuntime(UUID sessionId) {
    destroySession(sessionId);
    return true;
  }

  /** Stops only process-local state; durable authority and segment retention remain unchanged. */
  default void shutdownRuntime() {
    List.copyOf(getAllSessions()).forEach(session -> terminateRuntime(session.getSessionId()));
  }

  /**
   * Owner-checked destroy. A wrong-owner call is a silent no-op by design: an unowned session must
   * be indistinguishable from a missing one (no existence oracle).
   */
  void destroySession(UUID sessionId, UUID profileId);

  Collection<StreamSession> getAllSessions();

  int getActiveSessionCount();

  void resumeSessionIfNeeded(UUID sessionId, String segmentName);
}
