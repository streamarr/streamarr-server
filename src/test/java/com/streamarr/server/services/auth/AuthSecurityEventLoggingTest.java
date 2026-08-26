package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.support.LogCapture;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("UnitTest")
@DisplayName("Authentication Security Event Logging Tests")
class AuthSecurityEventLoggingTest {

  @Test
  @DisplayName("Should log warning when refresh token reuse revokes session")
  void shouldLogWarningWhenRefreshTokenReuseRevokesSession() {
    var currentTime = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    var clock = new MutableClock(currentTime);
    var sessionRepository = new FakeAuthSessionRepository();
    var tokenRepository = new FakeRefreshTokenRepository();
    var service =
        new RefreshTokenService(
            sessionRepository,
            tokenRepository,
            AuthTokenProperties.builder()
                .signingKey("")
                .accessTokenTtl(Duration.ofMinutes(10))
                .refreshTokenTtl(Duration.ofDays(30))
                .rotationGrace(Duration.ofSeconds(30))
                .build(),
            clock,
            new TokenReuseRevoker(
                new TokenReuseRevocationWriter(sessionRepository, tokenRepository)));
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var issued = service.createSession(account, "security-log-test");
    service.redeem(issued.rawToken());
    currentTime.updateAndGet(instant -> instant.plusSeconds(31));
    var replayedToken = issued.rawToken();

    try (var logs = LogCapture.forClass(RefreshTokenService.class)) {
      assertThatThrownBy(() -> service.redeem(replayedToken))
          .isInstanceOf(TokenReuseDetectedException.class);

      assertThat(logs.events()).anyMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
    }
  }

  @Test
  @DisplayName("Should log warning when identity claims rejected")
  void shouldLogWarningWhenIdentityClaimsRejected() {
    var accountId = UUID.randomUUID();
    var validator = new TokenIdentityValidator();
    var malformedToken =
        Jwt.withTokenValue("sensitive-token")
            .header("alg", "ES256")
            .subject(accountId.toString())
            .claim(TokenClaims.SCOPE, "not-a-real-scope")
            .build();

    // Fail closed AND loud: a systemic issuer/parser mismatch would otherwise reject every
    // token in the fleet with zero server-side signal.
    try (var logs = LogCapture.forClass(TokenIdentityValidator.class)) {
      assertThat(validator.validate(malformedToken).hasErrors()).isTrue();

      assertThat(logs.events())
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                    .contains(accountId.toString())
                    .doesNotContain("sensitive-token");
              });
    }
  }
}
