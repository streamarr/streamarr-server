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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
  @DisplayName("Should report only the first revocation and preserve its details")
  void shouldReportOnlyFirstRevocationAndPreserveItsDetails() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var session = saveSession();
    var firstRevokedAt = Instant.parse("2026-07-11T12:34:56Z");
    var secondRevokedAt = firstRevokedAt.plusSeconds(1);

    var firstRevoke =
        authSessionRepository.revoke(
            session.getId(), SessionRevocationReason.TOKEN_REUSE, firstRevokedAt);
    var secondRevoke =
        authSessionRepository.revoke(
            session.getId(), SessionRevocationReason.LOGOUT, secondRevokedAt);

    assertThat(firstRevoke).isTrue();
    assertThat(secondRevoke).isFalse();

    var revoked = authSessionRepository.findById(session.getId()).orElseThrow();
    assertThat(revoked.getRevokedAt()).isEqualTo(firstRevokedAt);
    assertThat(revoked.getRevokedReason()).isEqualTo(SessionRevocationReason.TOKEN_REUSE);
  }

  @Test
  @DisplayName("Should choose exactly one revocation when two revokers race")
  void shouldChooseExactlyOneRevocationWhenTwoRevokersRace() throws Exception {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var session = saveSession();
    var firstAttempt =
        new RevocationAttempt(
            SessionRevocationReason.TOKEN_REUSE, Instant.parse("2026-07-11T12:34:56Z"));
    var secondAttempt =
        new RevocationAttempt(
            SessionRevocationReason.LOGOUT, Instant.parse("2026-07-11T12:35:56Z"));
    var start = new CountDownLatch(1);

    boolean firstWon;
    boolean secondWon;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> revokeWhenReleased(session.getId(), firstAttempt, start));
      var second = executor.submit(() -> revokeWhenReleased(session.getId(), secondAttempt, start));

      start.countDown();
      firstWon = first.get(10, TimeUnit.SECONDS);
      secondWon = second.get(10, TimeUnit.SECONDS);
    }

    assertThat(List.of(firstWon, secondWon)).containsExactlyInAnyOrder(true, false);
    var winningAttempt = firstWon ? firstAttempt : secondAttempt;
    var revoked = authSessionRepository.findById(session.getId()).orElseThrow();
    assertThat(revoked.getRevokedAt()).isEqualTo(winningAttempt.revokedAt());
    assertThat(revoked.getRevokedReason()).isEqualTo(winningAttempt.reason());
  }

  @Test
  @DisplayName("Should revoke every unrevoked token when session family revoked")
  void shouldRevokeEveryUnrevokedTokenWhenSessionFamilyRevoked() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var session = saveSession();
    refreshTokenRepository.save(
        tokenBuilder(session).status(RefreshTokenStatus.ROTATED).rotatedAt(Instant.now()).build());
    refreshTokenRepository.save(tokenBuilder(session).status(RefreshTokenStatus.ACTIVE).build());

    refreshTokenRepository.revokeAllForSession(session.getId(), Instant.now());

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

  @Test
  @DisplayName("Should not report active token when expired")
  void shouldNotReportActiveTokenWhenExpired() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var expiredSession = saveSession();
    var currentSession = saveSession();
    var now = Instant.parse("2026-01-01T00:00:02Z");
    var expired =
        refreshTokenRepository.save(
            tokenBuilder(expiredSession)
                .status(RefreshTokenStatus.ACTIVE)
                .expiresAt(now.minusSeconds(1))
                .build());
    var current =
        refreshTokenRepository.save(
            tokenBuilder(currentSession)
                .status(RefreshTokenStatus.ACTIVE)
                .expiresAt(now.plusSeconds(1))
                .build());

    assertThat(
            refreshTokenRepository.isActiveToken(expiredSession.getId(), expired.getDigest(), now))
        .isFalse();
    assertThat(
            refreshTokenRepository.isActiveToken(currentSession.getId(), current.getDigest(), now))
        .isTrue();
  }

  private AuthSession saveSession() {
    return authSessionRepository.save(
        AuthSession.builder().accountId(account.getId()).deviceName("revocation-test").build());
  }

  private boolean revokeWhenReleased(
      UUID sessionId, RevocationAttempt attempt, CountDownLatch start) throws InterruptedException {
    start.await();
    return authSessionRepository.revoke(sessionId, attempt.reason(), attempt.revokedAt());
  }

  private RefreshToken.RefreshTokenBuilder<?, ?> tokenBuilder(AuthSession session) {
    return RefreshToken.builder()
        .sessionId(session.getId())
        .digest("digest-" + UUID.randomUUID())
        .expiresAt(Instant.now().plus(Duration.ofDays(30)));
  }

  private record RevocationAttempt(SessionRevocationReason reason, Instant revokedAt) {}
}
