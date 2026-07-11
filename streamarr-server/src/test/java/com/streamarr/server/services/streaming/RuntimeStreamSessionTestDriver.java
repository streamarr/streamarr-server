package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.exceptions.SessionNotFoundException;
import java.time.Instant;
import java.util.UUID;

final class RuntimeStreamSessionTestDriver {

  private RuntimeStreamSessionTestDriver() {}

  static StreamSession create(
      StreamingService streamingService,
      RuntimeStreamSessionRegistry runtimeRegistry,
      UUID mediaFileId,
      UUID profileId,
      StreamingOptions options) {
    var streamSessionId = UUID.randomUUID();
    var reservation =
        runtimeRegistry
            .reserve(streamSessionId)
            .orElseThrow(() -> new SessionNotFoundException(streamSessionId));
    try {
      var session =
          streamingService.createSession(
              CreateRuntimeStreamSessionCommand.builder()
                  .streamSessionId(streamSessionId)
                  .mediaFileId(mediaFileId)
                  .profileId(profileId)
                  .options(options)
                  .initialLastAccessedAt(Instant.now())
                  .reservation(reservation)
                  .build());
      if (!runtimeRegistry.markRunning(reservation)) {
        throw new SessionNotFoundException(streamSessionId);
      }
      return session;
    } catch (RuntimeException exception) {
      runtimeRegistry.terminalize(streamSessionId);
      try {
        streamingService.terminateRuntime(streamSessionId);
      } catch (RuntimeException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      throw exception;
    } finally {
      runtimeRegistry.releaseReservation(reservation);
    }
  }
}
