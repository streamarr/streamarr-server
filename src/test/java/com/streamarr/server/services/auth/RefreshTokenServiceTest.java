package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Refresh Token Service Tests")
class RefreshTokenServiceTest {

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeRefreshTokenRepository tokenRepository = new FakeRefreshTokenRepository();

  private final AuthTokenProperties properties =
      AuthTokenProperties.builder()
          .signingKey("")
          .accessTokenTtl(Duration.ofMinutes(10))
          .refreshTokenTtl(Duration.ofDays(30))
          .rotationGrace(Duration.ofSeconds(30))
          .build();

  private final MutableClock clock = new MutableClock(currentTime);

  private final TokenReuseRevoker tokenReuseRevoker =
      new TokenReuseRevoker(new TokenReuseRevocationWriter(sessionRepository, tokenRepository));

  private final RefreshTokenService service =
      new RefreshTokenService(
          sessionRepository, tokenRepository, properties, clock, tokenReuseRevoker);

  @Test
  @DisplayName("Should rotate when active token redeemed")
  void shouldRotateWhenActiveTokenRedeemed() {
    var issued = issueSession();

    var result = service.redeem(issued.rawToken());

    assertThat(result).isInstanceOf(RefreshResult.Rotated.class);
    var rotated = (RefreshResult.Rotated) result;
    assertThat(rotated.rawRefreshToken()).isNotEqualTo(issued.rawToken());
    assertThat(rotated.session().getId()).isEqualTo(issued.session().getId());

    var tokens = tokenRepository.findAll();
    assertThat(tokens).hasSize(2);
    assertThat(tokens)
        .filteredOn(token -> token.getStatus() == RefreshTokenStatus.ACTIVE)
        .hasSize(1);
    assertThat(tokens)
        .filteredOn(token -> token.getStatus() == RefreshTokenStatus.ROTATED)
        .singleElement()
        .satisfies(consumed -> assertThat(consumed.getRotatedAt()).isEqualTo(currentTime.get()));
  }

  @Test
  @DisplayName("Should return same successor when rotated token redeemed within grace")
  void shouldReturnSameSuccessorWhenRotatedTokenRedeemedWithinGrace() {
    var issued = issueSession();
    var rotation = (RefreshResult.Rotated) service.redeem(issued.rawToken());

    advanceClock(Duration.ofSeconds(10));
    var replay = (RefreshResult.Replayed) service.redeem(issued.rawToken());

    assertThat(replay.rawRefreshToken()).isEqualTo(rotation.rawRefreshToken());
    assertThat(replay.session().getId()).isEqualTo(issued.session().getId());
    assertThat(replay.session().getRevokedAt()).isNull();
    assertThat(tokenRepository.findAll())
        .filteredOn(token -> token.getStatus() == RefreshTokenStatus.ACTIVE)
        .hasSize(1);
  }

  @Test
  @DisplayName("Should treat exact rotation grace boundary as grace replay")
  void shouldTreatExactRotationGraceBoundaryAsGraceReplay() {
    var issued = issueSession();
    service.redeem(issued.rawToken());

    advanceClock(properties.rotationGrace());
    var replay = service.redeem(issued.rawToken());

    assertThat(replay).isInstanceOf(RefreshResult.Replayed.class);
    assertThat(replay.session().getRevokedAt()).isNull();
  }

  @Test
  @DisplayName("Should not return stale successor when earlier token replayed within grace")
  void shouldNotReturnStaleSuccessorWhenEarlierTokenReplayedWithinGrace() {
    var issued = issueSession();
    var firstRotation = (RefreshResult.Rotated) service.redeem(issued.rawToken());
    service.redeem(firstRotation.rawRefreshToken());

    var replay = service.redeem(issued.rawToken());

    assertThat(replay).isInstanceOf(RefreshResult.SupersededReplay.class);
    assertThat(replay.session().getId()).isEqualTo(issued.session().getId());
    assertThat(replay.session().getRevokedAt()).isNull();
  }

  @Test
  @DisplayName("Should classify expired successor as superseded replay within grace")
  void shouldClassifyExpiredSuccessorAsSupersededReplayWithinGrace() {
    var shortLivedService =
        serviceWith(
            AuthTokenProperties.builder()
                .signingKey("")
                .accessTokenTtl(Duration.ofMinutes(10))
                .refreshTokenTtl(Duration.ofSeconds(1))
                .rotationGrace(Duration.ofSeconds(10))
                .build());
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var issued = shortLivedService.createSession(account, "test-device");
    var rotation = (RefreshResult.Rotated) shortLivedService.redeem(issued.rawToken());

    advanceClock(Duration.ofSeconds(2));
    var replay = shortLivedService.redeem(issued.rawToken());

    assertThat(replay).isInstanceOf(RefreshResult.SupersededReplay.class);
    assertThat(replay.session().getRevokedAt()).isNull();
    assertThatThrownBy(() -> shortLivedService.redeem(rotation.rawRefreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  @DisplayName("Should revoke family when consumed token redeemed after grace")
  void shouldRevokeFamilyWhenConsumedTokenRedeemedAfterGrace() {
    var issued = issueSession();
    service.redeem(issued.rawToken());

    advanceClock(Duration.ofSeconds(31));
    var stolenToken = issued.rawToken();

    assertThatThrownBy(() -> service.redeem(stolenToken))
        .isInstanceOf(TokenReuseDetectedException.class);

    var session = sessionRepository.findById(issued.session().getId()).orElseThrow();
    assertThat(session.getRevokedAt()).isNotNull();
    assertThat(session.getRevokedReason()).isEqualTo(SessionRevocationReason.TOKEN_REUSE);
    assertThat(session.getSessionVersion()).isEqualTo(1L);

    assertThat(tokenRepository.findAll())
        .allSatisfy(token -> assertThat(token.getStatus()).isEqualTo(RefreshTokenStatus.REVOKED));
  }

  @Test
  @DisplayName("Should log token reuse with a safe session identifier")
  void shouldLogTokenReuseWithSafeSessionIdentifier() {
    var issued = issueSession();
    service.redeem(issued.rawToken());
    advanceClock(Duration.ofSeconds(31));
    var replayedToken = issued.rawToken();
    var logger = (Logger) LoggerFactory.getLogger(RefreshTokenService.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      assertThatThrownBy(() -> service.redeem(replayedToken))
          .isInstanceOf(TokenReuseDetectedException.class);
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains(issued.session().getId().toString())
                  .doesNotContain(replayedToken);
            });
  }

  @Test
  @DisplayName("Should reject redemption when token unknown")
  void shouldRejectRedemptionWhenTokenUnknown() {
    assertThatThrownBy(() -> service.redeem("never-issued-token"))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  @DisplayName("Should reject redemption without revocation when active token expired")
  void shouldRejectRedemptionWithoutRevocationWhenActiveTokenExpired() {
    var issued = issueSession();

    advanceClock(Duration.ofDays(31));
    var expiredToken = issued.rawToken();

    assertThatThrownBy(() -> service.redeem(expiredToken))
        .isInstanceOf(InvalidRefreshTokenException.class);

    var session = sessionRepository.findById(issued.session().getId()).orElseThrow();
    assertThat(session.getRevokedAt()).isNull();
  }

  @Test
  @DisplayName("Should reject redemption when session revoked")
  void shouldRejectRedemptionWhenSessionRevoked() {
    var issued = issueSession();
    sessionRepository.revoke(
        issued.session().getId(), SessionRevocationReason.LOGOUT, currentTime.get());

    var rawToken = issued.rawToken();
    assertThatThrownBy(() -> service.redeem(rawToken))
        .isInstanceOf(TokenReuseDetectedException.class);

    // No successor is minted onto a revoked session, and nothing was rotated.
    assertThat(tokenRepository.findAll()).hasSize(1);
    assertThat(tokenRepository.findAll())
        .allSatisfy(token -> assertThat(token.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE));
  }

  @Test
  @DisplayName("Should treat grace replay as theft when session revoked")
  void shouldTreatGraceReplayAsTheftWhenSessionRevoked() {
    var issued = issueSession();
    service.redeem(issued.rawToken());
    sessionRepository.revoke(
        issued.session().getId(), SessionRevocationReason.ADMIN_REVOCATION, currentTime.get());

    advanceClock(Duration.ofSeconds(5));
    var replayedToken = issued.rawToken();

    assertThatThrownBy(() -> service.redeem(replayedToken))
        .isInstanceOf(TokenReuseDetectedException.class);
  }

  @Test
  @DisplayName("Should treat rotated token as theft when rotation timestamp missing")
  void shouldTreatRotatedTokenAsTheftWhenRotationTimestampMissing() {
    var issued = issueSession();
    service.redeem(issued.rawToken());
    tokenRepository.findAll().stream()
        .filter(token -> token.getStatus() == RefreshTokenStatus.ROTATED)
        .forEach(token -> token.setRotatedAt(null));

    var replayedToken = issued.rawToken();

    assertThatThrownBy(() -> service.redeem(replayedToken))
        .isInstanceOf(TokenReuseDetectedException.class);
  }

  @Test
  @DisplayName("Should reject redemption when session row missing")
  void shouldRejectRedemptionWhenSessionRowMissing() {
    var issued = issueSession();
    sessionRepository.deleteById(issued.session().getId());
    var rawToken = issued.rawToken();

    assertThatThrownBy(() -> service.redeem(rawToken))
        .isInstanceOf(InvalidRefreshTokenException.class);

    assertThat(tokenRepository.findAll())
        .singleElement()
        .satisfies(token -> assertThat(token.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE));
  }

  @Test
  @DisplayName("Should keep reissued session live when an earlier refresh finishes afterward")
  void shouldKeepReissuedSessionLiveWhenEarlierRefreshFinishesAfterward() throws Exception {
    var pausingSessions = new PausingAuthSessionRepository();
    var tokens = new FakeRefreshTokenRepository();
    var revoker = new TokenReuseRevoker(new TokenReuseRevocationWriter(pausingSessions, tokens));
    var racingService =
        new RefreshTokenService(pausingSessions, tokens, properties, clock, revoker);
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var issued = racingService.createSession(account, "test-device");

    pausingSessions.pauseNextLock();
    IssuedRefreshToken reissued;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var refresh = executor.submit(() -> racingService.redeem(issued.rawToken()));
      assertThat(pausingSessions.lockEntered.await(10, TimeUnit.SECONDS)).isTrue();

      reissued = racingService.reissueFor(issued.session());
      pausingSessions.releaseLock.countDown();

      assertThatThrownBy(() -> refresh.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(InvalidRefreshTokenException.class);
    } finally {
      pausingSessions.releaseLock.countDown();
    }

    assertThat(pausingSessions.findById(issued.session().getId()).orElseThrow().getRevokedAt())
        .isNull();
    assertThat(tokens.findAll())
        .filteredOn(token -> token.getStatus() == RefreshTokenStatus.ACTIVE)
        .hasSize(1);
    assertThat(racingService.redeem(reissued.rawToken())).isInstanceOf(RefreshResult.Rotated.class);
  }

  @Test
  @DisplayName("Should fail fast when SHA-256 unavailable")
  void shouldFailFastWhenSha256Unavailable() {
    try (var digests = mockStatic(MessageDigest.class)) {
      digests
          .when(() -> MessageDigest.getInstance("SHA-256"))
          .thenThrow(new NoSuchAlgorithmException("unavailable"));

      assertThatThrownBy(() -> service.redeem("raw-token"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("SHA-256");
    }
  }

  private IssuedRefreshToken issueSession() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    return service.createSession(account, "test-device");
  }

  private void advanceClock(Duration duration) {
    currentTime.updateAndGet(instant -> instant.plus(duration));
  }

  private RefreshTokenService serviceWith(AuthTokenProperties tokenProperties) {
    return new RefreshTokenService(
        sessionRepository, tokenRepository, tokenProperties, clock, tokenReuseRevoker);
  }

  private static final class PausingAuthSessionRepository extends FakeAuthSessionRepository {

    private final CountDownLatch lockEntered = new CountDownLatch(1);
    private final CountDownLatch releaseLock = new CountDownLatch(1);
    private volatile boolean pause;

    private void pauseNextLock() {
      pause = true;
    }

    @Override
    public Optional<com.streamarr.server.domain.auth.AuthSession> lockById(UUID sessionId) {
      if (pause) {
        pause = false;
        lockEntered.countDown();
        try {
          if (!releaseLock.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("refresh lock was not released");
          }
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
          throw new AssertionError("refresh lock interrupted");
        }
      }
      return super.lockById(sessionId);
    }
  }
}
