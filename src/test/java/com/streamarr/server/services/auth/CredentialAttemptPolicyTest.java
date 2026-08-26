package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialKind;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Credential Attempt Policy Tests")
class CredentialAttemptPolicyTest {

  @Test
  @DisplayName("Should provide the standard limited policy for every credential kind")
  void shouldProvideTheStandardLimitedPolicyForEveryCredentialKind() {
    var provider = new StandardCredentialAttemptPolicyProvider();

    for (var kind : CredentialKind.values()) {
      assertThat(provider.policyFor(kind))
          .isEqualTo(
              new CredentialAttemptPolicy.Limited(
                  5, Duration.ofMinutes(15), Duration.ofMinutes(15)));
    }
  }

  @Test
  @DisplayName("Should support an unlimited credential policy")
  void shouldSupportAnUnlimitedCredentialPolicy() {
    CredentialAttemptPolicy policy = new CredentialAttemptPolicy.Unlimited();

    assertThat(policy).isInstanceOf(CredentialAttemptPolicy.Unlimited.class);
  }

  @Test
  @DisplayName("Should reject non-positive limited policy values")
  void shouldRejectNonPositiveLimitedPolicyValues() {
    assertThatThrownBy(
            () ->
                new CredentialAttemptPolicy.Limited(
                    0, Duration.ofMinutes(15), Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maximumFailures must be positive");
    assertThatThrownBy(
            () -> new CredentialAttemptPolicy.Limited(5, Duration.ZERO, Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("failureWindow must be positive and no longer than 24 hours");
    assertThatThrownBy(
            () -> new CredentialAttemptPolicy.Limited(5, Duration.ofMinutes(15), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("throttleDuration must be positive and no longer than 24 hours");
  }

  @Test
  @DisplayName("Should reject limited policy durations longer than one day")
  void shouldRejectLimitedPolicyDurationsLongerThanOneDay() {
    assertThatThrownBy(
            () ->
                new CredentialAttemptPolicy.Limited(
                    5, Duration.ofDays(1).plusNanos(1), Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("failureWindow must be positive and no longer than 24 hours");
    assertThatThrownBy(
            () ->
                new CredentialAttemptPolicy.Limited(
                    5, Duration.ofMinutes(15), Duration.ofDays(1).plusNanos(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("throttleDuration must be positive and no longer than 24 hours");
  }
}
