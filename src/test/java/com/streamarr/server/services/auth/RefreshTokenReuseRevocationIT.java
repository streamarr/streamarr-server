package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Refresh Token Reuse Revocation Integration Tests")
class RefreshTokenReuseRevocationIT extends AbstractIntegrationTest {

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private AuthSessionRepository authSessionRepository;

  @Autowired private RefreshTokenRepository refreshTokenRepository;

  @Autowired private PlatformTransactionManager transactionManager;

  private UserAccount account;

  @AfterEach
  void deleteAccountAndCascades() {
    if (account != null) {
      userAccountRepository.deleteById(account.getId());
    }
  }

  @Test
  @DisplayName("Should persist family revocation when reuse detected despite thrown exception")
  void shouldPersistFamilyRevocationWhenReuseDetectedDespiteThrownException() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var issued = refreshTokenService.createSession(account, "reuse-device");
    var sessionId = issued.session().getId();

    refreshTokenService.redeem(issued.rawToken());
    backdateRotatedTokenPastGrace(sessionId);

    var replayedToken = issued.rawToken();
    assertThatThrownBy(() -> refreshTokenService.redeem(replayedToken))
        .isInstanceOf(TokenReuseDetectedException.class);

    var session = authSessionRepository.findById(sessionId).orElseThrow();
    assertThat(session.getRevokedAt()).isNotNull();
    assertThat(session.getRevokedReason()).isEqualTo(SessionRevocationReason.TOKEN_REUSE);
    assertThat(refreshTokenRepository.findAll())
        .filteredOn(token -> sessionId.equals(token.getSessionId()))
        .isNotEmpty()
        .allSatisfy(token -> assertThat(token.getStatus()).isEqualTo(RefreshTokenStatus.REVOKED));
  }

  @Test
  @DisplayName("Should persist reuse revocation when redemption joins outer transaction")
  void shouldPersistReuseRevocationWhenRedemptionJoinsOuterTransaction() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var issued = refreshTokenService.createSession(account, "nested-transaction-device");
    var sessionId = issued.session().getId();

    refreshTokenService.redeem(issued.rawToken());
    backdateRotatedTokenPastGrace(sessionId);

    var transaction = new TransactionTemplate(transactionManager);
    var replayedToken = issued.rawToken();
    assertThatThrownBy(
            () -> transaction.executeWithoutResult(_ -> refreshTokenService.redeem(replayedToken)))
        .isInstanceOf(TokenReuseDetectedException.class);

    var session = authSessionRepository.findById(sessionId).orElseThrow();
    assertThat(session.getRevokedAt()).isNotNull();
    assertThat(session.getRevokedReason()).isEqualTo(SessionRevocationReason.TOKEN_REUSE);
    assertThat(refreshTokenRepository.findAll())
        .filteredOn(token -> sessionId.equals(token.getSessionId()))
        .isNotEmpty()
        .allSatisfy(token -> assertThat(token.getStatus()).isEqualTo(RefreshTokenStatus.REVOKED));
  }

  private void backdateRotatedTokenPastGrace(UUID sessionId) {
    var rotated =
        refreshTokenRepository.findAll().stream()
            .filter(token -> sessionId.equals(token.getSessionId()))
            .filter(token -> token.getStatus() == RefreshTokenStatus.ROTATED)
            .findFirst()
            .orElseThrow();
    rotated.setRotatedAt(Instant.now().minus(Duration.ofHours(1)));
    refreshTokenRepository.save(rotated);
  }
}
