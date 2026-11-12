package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Instant;
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
   * System destroy of a session that no request reached since the cutoff; returns whether it
   * destroyed the session. A request that reaches the session first keeps it.
   */
  boolean destroySessionIfLastAccessedBefore(UUID sessionId, Instant cutoff);

  /**
   * Owner-checked destroy. A wrong-owner call is a silent no-op by design: an unowned session must
   * be indistinguishable from a missing one to conceal its existence.
   */
  void destroySession(UUID sessionId, UUID profileId);

  Collection<StreamSession> getAllSessions();

  int getActiveSessionCount();

  /**
   * The variant of the stream session that the player plays: the one whose segment it received
   * last; before it received one, the variant of its newest media-segment request; and before it
   * requested one, the variant that the stream session started for its playlist. After a withdrawal
   * of the lower variants, the lower variant that the player played then, until it receives a media
   * segment of the copy. Empty when none is known or once the stream session is gone.
   */
  Optional<String> playedVariantOf(UUID sessionId);

  /**
   * Withdraws the encoded lower variants beside the stream session's copy after one of them failed
   * (ADR 0050). The copy keeps playing in the same stream session: its multivariant playlist lists
   * the copy alone from here on, and a request for a withdrawn variant finds nothing. A stream
   * session without a copy, without lower variants or already gone stays as it is.
   */
  void withdrawLowerVariants(UUID sessionId, LowerVariantFailure failure);
}
