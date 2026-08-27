package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Credential Code Properties Tests")
class CredentialCodePropertiesTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("Should reject every lifetime when the properties are unset")
  void shouldRejectEveryLifetimeWhenPropertiesAreUnset() {
    var properties = CredentialCodeProperties.builder().build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactlyInAnyOrder("invitationTtl", "passwordResetTtl", "replacementLockTimeout");
  }

  @Test
  @DisplayName("Should reject an invitation lifetime shorter than five minutes")
  void shouldRejectInvitationLifetimeWhenShorterThanFiveMinutes() {
    var properties = validProperties().invitationTtl(Duration.ofMinutes(5).minusNanos(1)).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("invitationTtl");
  }

  @Test
  @DisplayName("Should reject a password reset lifetime shorter than five minutes")
  void shouldRejectPasswordResetLifetimeWhenShorterThanFiveMinutes() {
    var properties =
        validProperties().passwordResetTtl(Duration.ofMinutes(5).minusNanos(1)).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("passwordResetTtl");
  }

  @Test
  @DisplayName("Should accept credential lifetimes when they are five minutes")
  void shouldAcceptCredentialLifetimesWhenTheyAreFiveMinutes() {
    var properties =
        validProperties()
            .invitationTtl(Duration.ofMinutes(5))
            .passwordResetTtl(Duration.ofMinutes(5))
            .build();

    assertThat(VALIDATOR.validate(properties)).isEmpty();
  }

  @Test
  @DisplayName("Should reject a replacement lock timeout shorter than one millisecond")
  void shouldRejectReplacementLockTimeoutWhenShorterThanOneMillisecond() {
    var properties = validProperties().replacementLockTimeout(Duration.ofNanos(999_999)).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("replacementLockTimeout");
  }

  @Test
  @DisplayName("Should accept a one millisecond replacement lock timeout")
  void shouldAcceptReplacementLockTimeoutWhenOneMillisecond() {
    var properties = validProperties().replacementLockTimeout(Duration.ofMillis(1)).build();

    assertThat(VALIDATOR.validate(properties)).isEmpty();
  }

  private static CredentialCodeProperties.CredentialCodePropertiesBuilder validProperties() {
    return CredentialCodeProperties.builder()
        .invitationTtl(Duration.ofDays(7))
        .passwordResetTtl(Duration.ofHours(1))
        .replacementLockTimeout(Duration.ofSeconds(5));
  }
}
