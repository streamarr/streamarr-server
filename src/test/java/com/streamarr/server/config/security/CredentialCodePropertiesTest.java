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
  @DisplayName("Should use a seven day invitation lifetime when it is omitted")
  void shouldUseSevenDayInvitationLifetimeWhenOmitted() {
    var properties = new CredentialCodeProperties(null, null);

    assertThat(properties.invitationTtl()).isEqualTo(Duration.ofDays(7));
  }

  @Test
  @DisplayName("Should use a one hour password reset lifetime when it is omitted")
  void shouldUseOneHourPasswordResetLifetimeWhenOmitted() {
    var properties = new CredentialCodeProperties(null, null);

    assertThat(properties.passwordResetTtl()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  @DisplayName("Should reject an invitation lifetime shorter than five minutes")
  void shouldRejectInvitationLifetimeWhenShorterThanFiveMinutes() {
    var properties =
        new CredentialCodeProperties(Duration.ofMinutes(5).minusNanos(1), Duration.ofHours(1));

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("invitationTtl");
  }

  @Test
  @DisplayName("Should reject a password reset lifetime shorter than five minutes")
  void shouldRejectPasswordResetLifetimeWhenShorterThanFiveMinutes() {
    var properties =
        new CredentialCodeProperties(Duration.ofDays(7), Duration.ofMinutes(5).minusNanos(1));

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("passwordResetTtl");
  }

  @Test
  @DisplayName("Should accept credential lifetimes when they are five minutes")
  void shouldAcceptCredentialLifetimesWhenTheyAreFiveMinutes() {
    var properties = new CredentialCodeProperties(Duration.ofMinutes(5), Duration.ofMinutes(5));

    assertThat(VALIDATOR.validate(properties)).isEmpty();
  }

  @Test
  @DisplayName("Should use a five second replacement lock timeout when it is omitted")
  void shouldUseFiveSecondReplacementLockTimeoutWhenOmitted() {
    var properties = new CredentialCodeProperties(null, null);

    assertThat(properties.replacementLockTimeout()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("Should reject a replacement lock timeout shorter than one millisecond")
  void shouldRejectReplacementLockTimeoutWhenShorterThanOneMillisecond() {
    var properties = new CredentialCodeProperties(null, null, Duration.ofNanos(999_999));

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("replacementLockTimeout");
  }

  @Test
  @DisplayName("Should accept a one millisecond replacement lock timeout")
  void shouldAcceptReplacementLockTimeoutWhenOneMillisecond() {
    var properties = new CredentialCodeProperties(null, null, Duration.ofMillis(1));

    assertThat(VALIDATOR.validate(properties)).isEmpty();
  }
}
