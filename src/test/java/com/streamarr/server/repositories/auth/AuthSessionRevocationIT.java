package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.RefreshToken;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Auth Session Revocation Integration Tests")
class AuthSessionRevocationIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private AuthSessionRepository authSessionRepository;

  @Autowired private RefreshTokenRepository refreshTokenRepository;

  private UserAccount account;

  @AfterEach
  void deleteAccountAndCascades() {
    if (account != null) {
      userAccountRepository.deleteById(account.getId());
    }
  }

  @Test
  @DisplayName("Should bump session version once when revoked twice")
  void shouldBumpSessionVersionOnceWhenRevokedTwice() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var session = saveSession();
    var now = Instant.now();

    var firstRevoke =
        authSessionRepository.revoke(session.getId(), SessionRevocationReason.TOKEN_REUSE, now);
    var secondRevoke =
        authSessionRepository.revoke(session.getId(), SessionRevocationReason.LOGOUT, now);

    assertThat(firstRevoke).contains(1L);
    assertThat(secondRevoke).isEmpty();

    var revoked = authSessionRepository.findById(session.getId()).orElseThrow();
    assertThat(revoked.getSessionVersion()).isEqualTo(1L);
    assertThat(revoked.getRevokedAt()).isNotNull();
    assertThat(revoked.getRevokedReason()).isEqualTo(SessionRevocationReason.TOKEN_REUSE);
  }

  @Test
  @DisplayName("Should revoke every unrevoked token when session family revoked")
  void shouldRevokeEveryUnrevokedTokenWhenSessionFamilyRevoked() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var session = saveSession();
    refreshTokenRepository.save(
        tokenBuilder(session)
            .status(RefreshTokenStatus.ROTATED)
            .rotatedAt(Instant.now())
            .build());
    refreshTokenRepository.save(tokenBuilder(session).status(RefreshTokenStatus.ACTIVE).build());

    refreshTokenRepository.revokeAllForSession(session.getId());

    assertThat(refreshTokenRepository.findAll())
        .filteredOn(token -> session.getId().equals(token.getSessionId()))
        .allSatisfy(token -> assertThat(token.getStatus()).isEqualTo(RefreshTokenStatus.REVOKED));
  }

  @Test
  @DisplayName("Should not consume active token when expired")
  void shouldNotConsumeActiveTokenWhenExpired() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var session = saveSession();
    var expired =
        refreshTokenRepository.save(
            tokenBuilder(session)
                .status(RefreshTokenStatus.ACTIVE)
                .expiresAt(Instant.now().minus(Duration.ofDays(1)))
                .build());

    var consumed = refreshTokenRepository.consumeActiveToken(expired.getDigest(), Instant.now());

    assertThat(consumed).isZero();
    assertThat(refreshTokenRepository.findById(expired.getId()).orElseThrow().getStatus())
        .isEqualTo(RefreshTokenStatus.ACTIVE);
  }

  private AuthSession saveSession() {
    return authSessionRepository.save(
        AuthSession.builder().accountId(account.getId()).deviceName("revocation-test").build());
  }

  private RefreshToken.RefreshTokenBuilder<?, ?> tokenBuilder(AuthSession session) {
    return RefreshToken.builder()
        .sessionId(session.getId())
        .digest("digest-" + UUID.randomUUID())
        .expiresAt(Instant.now().plus(Duration.ofDays(30)));
  }
}
