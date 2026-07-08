package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Refresh Revocation Race Integration Tests")
class RefreshRevocationRaceIT extends AbstractIntegrationTest {

  private static final int ROUNDS = 25;

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
  @DisplayName("Should leave no active token on session when refresh races revocation")
  void shouldLeaveNoActiveTokenOnSessionWhenRefreshRacesRevocation() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());

    for (int round = 0; round < ROUNDS; round++) {
      var issued = refreshTokenService.createSession(account, "race-device");
      var sessionId = issued.session().getId();

      raceRefreshAgainstRevocation(issued.rawToken(), sessionId);

      // The session is revoked and its family fully swept: no ACTIVE token survives the race, so
      // the revoked session can never mint another access token.
      var session = authSessionRepository.findById(sessionId).orElseThrow();
      assertThat(session.getRevokedAt()).isNotNull();
      assertThat(activeTokenCountFor(sessionId)).isZero();
    }
  }

  @Test
  @DisplayName("Should reject redeeming a successor minted before revocation")
  void shouldRejectRedeemingASuccessorMintedBeforeRevocation() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var issued = refreshTokenService.createSession(account, "sequential-device");

    var rotated = (RefreshResult.Rotated) refreshTokenService.redeem(issued.rawToken());
    revokeAndSweep(issued.session().getId());

    // A successor handed out just before revocation must not keep the family alive.
    assertThatThrownBy(() -> refreshTokenService.redeem(rotated.rawRefreshToken()))
        .isInstanceOf(TokenReuseDetectedException.class);
    assertThat(activeTokenCountFor(issued.session().getId())).isZero();
  }

  private void raceRefreshAgainstRevocation(String rawToken, UUID sessionId) {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var startLatch = new CountDownLatch(1);
      var doneLatch = new CountDownLatch(2);
      var errors = new CopyOnWriteArrayList<Throwable>();

      executor.submit(
          guarded(startLatch, doneLatch, errors, () -> refreshTokenService.redeem(rawToken)));
      executor.submit(guarded(startLatch, doneLatch, errors, () -> revokeAndSweep(sessionId)));

      startLatch.countDown();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(doneLatch.getCount()).isZero());

      // Refresh may legitimately reject (it lost the race); nothing should fail unexpectedly —
      // no deadlock, and revocation never fails.
      assertThat(errors).isEmpty();
    }
  }

  /** Mirrors the atomic revoke-and-sweep that logout and password-change perform downstream. */
  private void revokeAndSweep(UUID sessionId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              authSessionRepository.revoke(
                  sessionId, SessionRevocationReason.LOGOUT, Instant.now());
              refreshTokenRepository.revokeAllForSession(sessionId);
            });
  }

  private Runnable guarded(
      CountDownLatch start,
      CountDownLatch done,
      CopyOnWriteArrayList<Throwable> errors,
      Runnable body) {
    return () -> {
      try {
        start.await();
        body.run();
      } catch (TokenReuseDetectedException | InvalidRefreshTokenException expected) {
        // The refresh lost the race to revocation — expected, not an error.
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException e) {
        errors.add(e);
      } finally {
        done.countDown();
      }
    };
  }

  private long activeTokenCountFor(UUID sessionId) {
    return refreshTokenRepository.findAll().stream()
        .filter(token -> sessionId.equals(token.getSessionId()))
        .filter(token -> token.getStatus() == RefreshTokenStatus.ACTIVE)
        .count();
  }
}
