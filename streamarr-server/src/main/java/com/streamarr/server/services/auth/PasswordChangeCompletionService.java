package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
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
  private final SessionRevocationService sessionRevocationService;
  private final RefreshTokenService refreshTokenService;
  private final SessionScopeService sessionScopeService;
  private final AccessTokenIssuer accessTokenIssuer;
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
    var deviceName = callerSession.getDeviceName();
    var householdId = callerSession.getActiveHouseholdId();
    var profileId = callerSession.getActiveProfileId();
    var sessionIds = sessionRepository.lockIdsByAccountIdOrderById(command.accountId());

    account.setPasswordHash(command.newPasswordHash());
    var changedAt = clock.instant();
    sessionIds.forEach(
        sessionId ->
            sessionRevocationService.revoke(
                sessionId, SessionRevocationReason.PASSWORD_CHANGE, changedAt));

    var issued =
        refreshTokenService.createSession(
            CreateAuthSessionCommand.builder()
                .accountId(account.getId())
                .deviceName(deviceName)
                .activeHouseholdId(householdId)
                .activeProfileId(profileId)
                .build());
    sessionRepository.flush();
    var context = sessionScopeService.revalidateStoredContext(account, issued.session());
    var accessToken = accessTokenIssuer.issue(context);

    return PasswordChangeResult.builder()
        .accessToken(accessToken)
        .rawRefreshToken(issued.rawToken())
        .build();
  }
}
