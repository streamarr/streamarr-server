package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Reauthentication Freshness Tests")
class ReauthenticationFreshnessTest {

  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
  private static final Duration WINDOW = Duration.ofMinutes(5);

  private final ReauthenticationFreshness freshness =
      new ReauthenticationFreshness(
          AuthTokenProperties.builder().reauthenticationWindow(WINDOW).build(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  @DisplayName("Should be fresh only inside the window")
  void shouldBeFreshOnlyInsideWindow() {
    assertThat(freshness.isFresh(identityReauthenticatedAt(NOW))).isTrue();
    assertThat(freshness.isFresh(identityReauthenticatedAt(NOW.minus(WINDOW).plusSeconds(1))))
        .isTrue();
    assertThat(freshness.isFresh(identityReauthenticatedAt(NOW.minus(WINDOW)))).isFalse();
  }

  @Test
  @DisplayName("Should never be fresh without a claim")
  void shouldNeverBeFreshWithoutClaim() {
    assertThat(freshness.isFresh(AuthenticatedIdentityFixture.accountScopedBuilder().build()))
        .isFalse();
  }

  @Test
  @DisplayName("Should never trust a future-dated claim")
  void shouldNeverTrustFutureDatedClaim() {
    assertThat(freshness.isFresh(identityReauthenticatedAt(NOW.plusSeconds(30)))).isFalse();
  }

  private static AuthenticatedIdentity identityReauthenticatedAt(Instant at) {
    return AuthenticatedIdentityFixture.accountScopedBuilder()
        .reauthenticatedAt(Optional.ofNullable(at))
        .build();
  }
}
