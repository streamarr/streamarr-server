package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.DeviceBoundSessionException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Verifies reauthentication outside a transaction and preserves the presented token context. */
@Service
@RequiredArgsConstructor
public class ReauthenticationService {

  private final UserAccountRepository userAccountRepository;
  private final AuthSessionRepository authSessionRepository;
  private final AccountPasswordVerifier accountPasswordVerifier;

  public TokenContext reauthenticate(AuthenticatedIdentity identity, String password) {
    if (identity.deviceBound()) {
      // A TV never steps up: fresh-reauthentication work is for people at their own keyboard.
      throw new DeviceBoundSessionException();
    }
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
        .profileId(Optional.ofNullable(identity.profileId()))
        .build();
  }
}
