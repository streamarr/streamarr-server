package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeVersionCounterReader;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("UnitTest")
@DisplayName("Token Version Validator Tests")
class TokenVersionValidatorTest {

  private final FakeVersionCounterReader reader = new FakeVersionCounterReader();
  private final TokenVersionCache cache = new TokenVersionCache(reader);
  private final TokenVersionValidator validator = new TokenVersionValidator(cache);

  private final UUID accountId = UUID.randomUUID();
  private final UUID sessionId = UUID.randomUUID();

  @Test
  @DisplayName("Should reject token when embedded version stale")
  void shouldRejectTokenWhenEmbeddedVersionStale() {
    reader.sessionVersions.put(sessionId, 4L);

    assertThat(validator.validate(tokenWithSessionVersion(4L)).hasErrors()).isFalse();
    assertThat(validator.validate(tokenWithSessionVersion(3L)).hasErrors()).isTrue();
  }

  @Test
  @DisplayName("Should reject token when counter unreadable")
  void shouldRejectTokenWhenCounterUnreadable() {
    // Absent counter row: fail closed.
    assertThat(validator.validate(tokenWithSessionVersion(0L)).hasErrors()).isTrue();

    // Reader blowing up (database error): fail closed, never fail open.
    reader.sessionVersions.put(sessionId, 0L);
    reader.failWith(new IllegalStateException("database unavailable"));
    assertThat(validator.validate(tokenWithSessionVersion(0L)).hasErrors()).isTrue();
  }

  private Jwt tokenWithSessionVersion(long sessionVersion) {
    var now = Instant.now();
    return Jwt.withTokenValue("test-token")
        .header("alg", "HS256")
        .subject(accountId.toString())
        .issuedAt(now)
        .expiresAt(now.plusSeconds(600))
        .claim(TokenClaims.SESSION_ID, sessionId.toString())
        .claim(TokenClaims.SESSION_VERSION, sessionVersion)
        .claim(TokenClaims.SCOPE, TokenScope.ACCOUNT.claimValue())
        .build();
  }
}
