package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Loads the Account and its live session under lock for a context change. */
@Component
@RequiredArgsConstructor
class LiveSessions {

  private final UserAccountRepository userAccountRepository;
  private final AuthSessionRepository sessionRepository;

  UserAccount loadAccount(UUID accountId) {
    return userAccountRepository
        .findById(accountId)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  /**
   * A missing, foreign, or revoked session reads identically as unauthenticated (oracle-free). The
   * row is locked FOR UPDATE so a concurrent revoke cannot interleave between this read and the
   * selection's write: the revoke has either already committed (revokedAt set — rejected here) or
   * it blocks until this transaction commits and then applies on top.
   */
  AuthSession lockLiveSession(UUID accountId, UUID sessionId) {
    return sessionRepository
        .lockById(sessionId)
        .filter(session -> session.getAccountId().equals(accountId))
        .filter(session -> session.getRevokedAt() == null)
        .orElseThrow(AuthenticationRequiredException::new);
  }
}
