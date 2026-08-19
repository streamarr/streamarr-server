package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The step-up half of POST /api/auth/reauth (ADR 0024 §Fresh reauthentication): a live session and
 * a throttled, full-cost password verification, then the context for the replacement token. The
 * context preserves the presented token's Household context and selected Profile — the ceremony
 * replaces that token, it never re-derives the session. Argon2 runs here, outside any transaction.
 */
@Service
@RequiredArgsConstructor
public class ReauthenticationService {

  private final UserAccountRepository userAccountRepository;
  private final AuthSessionRepository authSessionRepository;
  private final AccountPasswordVerifier accountPasswordVerifier;

  public TokenContext reauthenticate(AuthenticatedIdentity identity, String password) {
    var session =
        authSessionRepository
            .findById(identity.authSessionId())
            .filter(live -> live.getRevokedAt() == null)
            .orElseThrow(AuthenticationRequiredException::new);
    var account =
        userAccountRepository
            .findById(identity.accountId())
            .orElseThrow(AuthenticationRequiredException::new);

    accountPasswordVerifier.verify(account, password);

    return TokenContext.builder()
        .account(account)
        .session(session)
        .contextHouseholdId(identity.contextHouseholdId())
        .profileId(identity.profileId())
        .build();
  }
}
