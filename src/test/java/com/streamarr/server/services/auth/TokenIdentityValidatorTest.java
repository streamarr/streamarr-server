package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.streamarr.server.support.LogCapture;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("UnitTest")
@DisplayName("Token Identity Validator Tests")
class TokenIdentityValidatorTest {

  @Test
  @DisplayName("Should reject the token without logging its value when identity claims are invalid")
  void shouldRejectTokenWithoutLoggingItsValueWhenIdentityClaimsAreInvalid() {
    var validator = new TokenIdentityValidator();
    var malformedToken =
        Jwt.withTokenValue("sensitive-token")
            .header("alg", "ES256")
            .subject(UUID.randomUUID().toString())
            .claim(TokenClaims.SCOPE, "not-a-real-scope")
            .build();

    try (var logs = LogCapture.forClass(TokenIdentityValidator.class)) {
      assertThat(validator.validate(malformedToken).hasErrors()).isTrue();

      // Fail closed AND loud: a systemic issuer/parser mismatch would otherwise reject every
      // token in the fleet with zero server-side signal. The warning is that signal's only trace
      // until a metric replaces it.
      assertThat(logs.events()).extracting(ILoggingEvent::getLevel).contains(Level.WARN);
      assertThat(logs.renderedEvents())
          .allSatisfy(event -> assertThat(event).doesNotContain("sensitive-token"));
    }
  }
}
