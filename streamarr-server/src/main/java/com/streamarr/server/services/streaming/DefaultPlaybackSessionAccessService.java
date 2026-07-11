package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.repositories.streaming.PlaybackRequestAuthority;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DefaultPlaybackSessionAccessService implements PlaybackSessionAccessService {

  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final StreamSessionLifecycleTransactions lifecycleTransactions;
  private final StreamSessionTransactionRetry transactionRetry;

  @Override
  public Optional<StreamSession> access(UUID requestedSessionId, AuthenticatedIdentity identity) {
    var runtimeSession = runtimeRegistry.findById(requestedSessionId);
    if (runtimeSession.isEmpty()) {
      return Optional.empty();
    }
    if (!locallyMatches(requestedSessionId, identity)) {
      return Optional.empty();
    }

    var committedAccess =
        transactionRetry.execute(
            () ->
                lifecycleTransactions.touchIfPlaybackRequestMatches(
                    authority(requestedSessionId, identity)));
    if (committedAccess.isEmpty()) {
      return Optional.empty();
    }

    runtimeRegistry.mirrorCommittedAccess(requestedSessionId, committedAccess.orElseThrow());
    return runtimeSession;
  }

  private boolean locallyMatches(UUID requestedSessionId, AuthenticatedIdentity identity) {
    return identity.scope() == TokenScope.PLAYBACK
        && requestedSessionId.equals(identity.streamSessionId())
        && identity.householdId() != null
        && identity.profileId() != null;
  }

  private PlaybackRequestAuthority authority(
      UUID requestedSessionId, AuthenticatedIdentity identity) {
    return PlaybackRequestAuthority.builder()
        .streamSessionId(requestedSessionId)
        .authSessionId(identity.sessionId())
        .accountId(identity.accountId())
        .householdId(identity.householdId())
        .profileId(identity.profileId())
        .build();
  }
}
