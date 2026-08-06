package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PasswordChangeCompletionService {

  private final UserAccountRepository accountRepository;
  private final AuthSessionRepository sessionRepository;
  private final RefreshTokenRepository tokenRepository;
  private final RefreshTokenService refreshTokenService;
  private final Clock clock;

  @Transactional
  public PasswordChangeResult complete(PasswordChangeCompletionCommand command) {
    if (!accountRepository.lockIfCredentialsUnchanged(
        command.accountId(), command.expectedPasswordHash())) {
      throw new AuthenticationRequiredException();
    }

    var account =
        accountRepository
            .findById(command.accountId())
            .orElseThrow(AuthenticationRequiredException::new);
    var callerSession =
        sessionRepository
            .lockById(command.sessionId())
            .filter(session -> session.getAccountId().equals(command.accountId()))
            .filter(session -> session.getRevokedAt() == null)
            .orElseThrow(AuthenticationRequiredException::new);

    account.setPasswordHash(command.newPasswordHash());
    accountRepository.save(account);

    var changedAt = clock.instant();
    sessionRepository
        .findByAccountId(account.getId())
        .forEach(
            session -> {
              sessionRepository.revoke(
                  session.getId(), SessionRevocationReason.PASSWORD_CHANGE, changedAt);
              tokenRepository.revokeAllForSession(session.getId(), changedAt);
            });

    var issued =
        refreshTokenService.createSession(
            CreateAuthSessionCommand.builder()
                .accountId(account.getId())
                .deviceName(callerSession.getDeviceName())
                .activeHouseholdId(callerSession.getActiveHouseholdId())
                .activeProfileId(callerSession.getActiveProfileId())
                .build());

    return PasswordChangeResult.builder()
        .account(account)
        .session(issued.session())
        .rawRefreshToken(issued.rawToken())
        .build();
  }
}
