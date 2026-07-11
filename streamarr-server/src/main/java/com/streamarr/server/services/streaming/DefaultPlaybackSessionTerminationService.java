package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSessionTerminalReason;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DefaultPlaybackSessionTerminationService implements PlaybackSessionTerminationService {

  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final StreamSessionLifecycleTransactions lifecycleTransactions;
  private final StreamSessionCleanup cleanup;
  private final StreamSessionTransactionRetry transactionRetry;
  private final Clock clock;

  @Override
  public void destroy(UUID streamSessionId, UUID profileId) {
    var ownedSession =
        runtimeRegistry.findById(streamSessionId).filter(session -> session.isOwnedBy(profileId));
    if (ownedSession.isEmpty()) {
      return;
    }

    var termination =
        StreamSessionTermination.builder()
            .streamSessionId(streamSessionId)
            .reason(StreamSessionTerminalReason.OWNER_DESTROY)
            .terminalAt(clock.instant())
            .build();
    transactionRetry.execute(() -> lifecycleTransactions.terminalize(termination));
    cleanup.cleanup(streamSessionId);
  }
}
