package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Refresh Rotation Concurrency Integration Tests")
class RefreshRotationConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private AuthSessionRepository authSessionRepository;

  @Autowired private RefreshTokenRepository refreshTokenRepository;

  private UserAccount account;

  @AfterEach
  void deleteAccountAndCascades() {
    if (account != null) {
      // FK cascades sweep auth_session and refresh_token rows.
      userAccountRepository.deleteById(account.getId());
    }
  }

  @Test
  @DisplayName("Should rotate exactly once when same token redeemed concurrently")
  void shouldRotateExactlyOnceWhenSameTokenRedeemedConcurrently() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var issued = refreshTokenService.createSession(account, "race-device");

    var threadCount = 2;
    var executor = Executors.newFixedThreadPool(threadCount);
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount);
    var results = new CopyOnWriteArrayList<RefreshResult>();
    var exceptions = new CopyOnWriteArrayList<Exception>();

    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              results.add(refreshTokenService.redeem(issued.rawToken()));
            } catch (Exception e) {
              exceptions.add(e);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(doneLatch.getCount()).isZero());

    executor.shutdown();

    // Both callers recover the same successor: one genuine rotation, one grace replay.
    assertThat(exceptions).isEmpty();
    assertThat(results).hasSize(threadCount);
    assertThat(results).filteredOn(RefreshResult.Rotated.class::isInstance).hasSize(1);
    assertThat(results).filteredOn(RefreshResult.Replayed.class::isInstance).hasSize(1);

    var rotation =
        (RefreshResult.Rotated)
            results.stream()
                .filter(RefreshResult.Rotated.class::isInstance)
                .findFirst()
                .orElseThrow();
    var replay =
        (RefreshResult.Replayed)
            results.stream()
                .filter(RefreshResult.Replayed.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThat(replay.rawRefreshToken()).isEqualTo(rotation.rawRefreshToken());

    var sessionId = issued.session().getId();
    var tokens =
        refreshTokenRepository.findAll().stream()
            .filter(token -> sessionId.equals(token.getSessionId()))
            .toList();
    assertThat(tokens).hasSize(2);
    assertThat(tokens)
        .filteredOn(token -> token.getStatus() == RefreshTokenStatus.ACTIVE)
        .hasSize(1);

    var session = authSessionRepository.findById(sessionId).orElseThrow();
    assertThat(session.getRevokedAt()).isNull();
  }

  @Test
  @DisplayName("Should not recover consumed successor when earlier token replayed within grace")
  void shouldNotRecoverConsumedSuccessorWhenEarlierTokenReplayedWithinGrace() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var issued = refreshTokenService.createSession(account, "late-response-device");
    var firstRotation = (RefreshResult.Rotated) refreshTokenService.redeem(issued.rawToken());
    refreshTokenService.redeem(firstRotation.rawRefreshToken());

    var replay = refreshTokenService.redeem(issued.rawToken());

    assertThat(replay).isInstanceOf(RefreshResult.SupersededReplay.class);
    assertThat(replay.session().getId()).isEqualTo(issued.session().getId());
  }
}
