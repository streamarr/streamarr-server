package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("UnitTest")
@DisplayName("Strict Jwt Expiry Validator Tests")
class StrictJwtExpiryValidatorTest {

  private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

  private final StrictJwtExpiryValidator validator =
      new StrictJwtExpiryValidator(Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  @DisplayName("Should accept token when expiry is in the future")
  void shouldAcceptTokenWhenExpiryIsInTheFuture() {
    var result = validator.validate(token(NOW.plusSeconds(1)));

    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  @DisplayName("Should reject token when now equals expiry")
  void shouldRejectTokenWhenNowEqualsExpiry() {
    var result = validator.validate(token(NOW));

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors())
        .anySatisfy(e -> assertThat(e.getDescription()).contains("expired"));
  }

  @Test
  @DisplayName("Should reject token when past expiry without grace")
  void shouldRejectTokenWhenPastExpiryWithoutGrace() {
    var result = validator.validate(token(NOW.minusMillis(1)));

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors())
        .anySatisfy(e -> assertThat(e.getDescription()).contains("expired"));
  }

  @Test
  @DisplayName("Should reject token when expiry claim missing")
  void shouldRejectTokenWhenExpiryClaimMissing() {
    var result = validator.validate(token(null));

    assertThat(result.hasErrors()).isTrue();
    // "expired" routes to EXPIRED_TOKEN (refresh-and-retry); a token with no expiry must route
    // to INVALID_TOKEN instead, so its description may not mention expiry.
    assertThat(result.getErrors())
        .allSatisfy(e -> assertThat(e.getDescription()).doesNotContain("expired"));
  }

  private static Jwt token(Instant expiresAt) {
    var builder =
        Jwt.withTokenValue("token")
            .header("alg", "ES256")
            .subject(UUID.randomUUID().toString())
            .issuedAt(NOW.minus(Duration.ofMinutes(5)));
    if (expiresAt != null) {
      builder.expiresAt(expiresAt);
    }
    return builder.build();
  }
}
