package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.services.auth.events.CounterBumpedEvent;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Refresh Token Service Tests")
class RefreshTokenServiceTest {

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeRefreshTokenRepository tokenRepository = new FakeRefreshTokenRepository();
  private final CapturingEventPublisher eventPublisher = new CapturingEventPublisher();

  private final AuthTokenProperties properties =
      AuthTokenProperties.builder()
          .signingKey("")
          .accessTokenTtl(Duration.ofMinutes(10))
          .refreshTokenTtl(Duration.ofDays(30))
          .rotationGrace(Duration.ofSeconds(30))
          .build();

  private final MutableClock clock = new MutableClock(currentTime);

  private final RefreshTokenService service =
      new RefreshTokenService(
          sessionRepository, tokenRepository, properties, clock, eventPublisher);

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
  @DisplayName("Should issue access without rotation when rotated token redeemed within grace")
  void shouldIssueAccessWithoutRotationWhenRotatedTokenRedeemedWithinGrace() {
    var issued = issueSession();
    service.redeem(issued.rawToken());

    advanceClock(Duration.ofSeconds(10));
    var replay = service.redeem(issued.rawToken());

    assertThat(replay).isInstanceOf(RefreshResult.GraceReplay.class);
    assertThat(replay.session().getId()).isEqualTo(issued.session().getId());
    assertThat(replay.session().getRevokedAt()).isNull();
    assertThat(tokenRepository.findAll())
        .filteredOn(token -> token.getStatus() == RefreshTokenStatus.ACTIVE)
        .hasSize(1);
    assertThat(eventPublisher.getEventsOfType(CounterBumpedEvent.class)).isEmpty();
  }

  @Test
  @DisplayName("Should treat exact rotation grace boundary as grace replay")
  void shouldTreatExactRotationGraceBoundaryAsGraceReplay() {
    var issued = issueSession();
    service.redeem(issued.rawToken());

    advanceClock(properties.rotationGrace());
    var replay = service.redeem(issued.rawToken());

    assertThat(replay).isInstanceOf(RefreshResult.GraceReplay.class);
    assertThat(replay.session().getRevokedAt()).isNull();
    assertThat(eventPublisher.getEventsOfType(CounterBumpedEvent.class)).isEmpty();
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

    assertThat(eventPublisher.getEventsOfType(CounterBumpedEvent.class))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.kind()).isEqualTo(CounterKind.SESSION);
              assertThat(event.key()).isEqualTo(session.getId().toString());
              assertThat(event.version()).isEqualTo(1L);
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
    assertThat(eventPublisher.getEventsOfType(CounterBumpedEvent.class)).isEmpty();
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

    // The session was already revoked, so the reuse path finds no version to bump: no event.
    assertThat(eventPublisher.getEventsOfType(CounterBumpedEvent.class)).isEmpty();
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
    assertThat(eventPublisher.getEventsOfType(CounterBumpedEvent.class)).isEmpty();
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
}
