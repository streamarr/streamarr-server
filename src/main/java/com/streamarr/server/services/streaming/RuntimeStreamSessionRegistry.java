package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface RuntimeStreamSessionRegistry extends StreamSessionRepository {

  Optional<RuntimeSessionReservation> reserve(UUID sessionId);

  boolean attach(RuntimeSessionReservation reservation, StreamSession session);

  Optional<RuntimeTranscodeStart> beginTranscodeStart(UUID sessionId);

  boolean completeTranscodeStart(RuntimeTranscodeStart start);

  void abortTranscodeStart(RuntimeTranscodeStart start);

  void finishRejectedTranscodeStart(RuntimeTranscodeStart start, boolean stopped);

  boolean markRunning(RuntimeSessionReservation reservation);

  void releaseReservation(RuntimeSessionReservation reservation);

  boolean terminalize(UUID sessionId);

  void markRuntimeStopped(UUID sessionId);

  boolean releaseTerminal(UUID sessionId);

  void awaitTranscodeStarts(UUID sessionId);

  Collection<UUID> fenceAll();

  void mirrorCommittedAccess(UUID sessionId, Instant accessedAt);
}
