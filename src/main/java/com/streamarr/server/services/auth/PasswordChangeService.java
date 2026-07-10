package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR 0016: a password change invalidates every token for the account. Other sessions are revoked
 * with PASSWORD_CHANGE; the caller's session bumps its version (killing its outstanding tokens) but
 * stays alive and leaves with fresh credentials — the user is never logged out by their own change.
 */
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

  private final UserAccountRepository userAccountRepository;
  private final AuthSessionRepository sessionRepository;
  private final RefreshTokenRepository tokenRepository;
  private final RefreshTokenService refreshTokenService;
  private final PasswordEncoder passwordEncoder;
  private final java.time.Clock clock;

  @Transactional
  public PasswordChangeResult changePassword(ChangePasswordCommand command) {
    var account =
        userAccountRepository
            .findById(command.accountId())
            .orElseThrow(AuthenticationRequiredException::new);
    if (!userAccountRepository.lockIfCredentialsUnchanged(
        account.getId(), account.getPasswordHash())) {
      throw new AuthenticationRequiredException();
    }

    // NIST re-authentication for a security action: the current password is required.
    if (!passwordEncoder.matches(command.currentPassword(), account.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }

    account.setPasswordHash(passwordEncoder.encode(command.newPassword()));
    userAccountRepository.save(account);

    var now = clock.instant();
    var sessions = sessionRepository.findByAccountId(account.getId());
    for (var session : sessions) {
      if (session.getId().equals(command.sessionId())) {
        continue;
      }
      // These managed entities are used by id only. The jOOQ revoke below bumps the row's
      // session_version underneath them, so getSessionVersion()/getRevokedAt() would read stale
      // first-level-cache state — and mutating them would flush that stale state back over the
      // jOOQ write. Don't read or set counter/revocation fields on them here (see AGENTS.md).
      sessionRepository.revoke(session.getId(), SessionRevocationReason.PASSWORD_CHANGE, now);
      tokenRepository.revokeAllForSession(session.getId(), now);
    }

    var callerSession =
        sessions.stream()
            .filter(session -> session.getId().equals(command.sessionId()))
            .findFirst()
            .orElseThrow(AuthenticationRequiredException::new);

    var bumpedVersion =
        sessionRepository
            .bumpVersion(callerSession.getId(), now)
            .orElseThrow(AuthenticationRequiredException::new);
    // Align the managed entity with the SQL bump so the fresh access token carries the new sv.
    callerSession.setSessionVersion(bumpedVersion);

    var issued = refreshTokenService.reissueFor(callerSession);

    return PasswordChangeResult.builder()
        .account(account)
        .session(callerSession)
        .rawRefreshToken(issued.rawToken())
        .build();
  }
}
