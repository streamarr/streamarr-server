package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.mutation.Outcome;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface StreamingService {

  Outcome<StreamSession, CreateStreamSessionRejection> createSession(
      CreateStreamSessionCommand command);

  Optional<StreamSession> accessSession(PlaybackRequest request);

  /** System destroy — no caller identity; reserved for the reaper, cleanup, and shutdown. */
  void destroySession(UUID sessionId);

  /**
   * Owner-checked destroy. A wrong-owner call is a silent no-op by design: an unowned session must
   * be indistinguishable from a missing one to conceal its existence.
   */
  void destroySession(UUID sessionId, UUID profileId);

  Collection<StreamSession> getAllSessions();

  int getActiveSessionCount();
}
