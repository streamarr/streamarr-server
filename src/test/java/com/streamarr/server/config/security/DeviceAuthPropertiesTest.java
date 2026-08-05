package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Device Auth Properties Tests")
class DeviceAuthPropertiesTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("Should accept the configuration when every property is within bounds")
  void shouldAcceptConfigurationWhenEveryPropertyWithinBounds() {
    assertThat(validator.validate(defaultProperties().build())).isEmpty();
  }

  @ParameterizedTest(name = "Should reject a poll interval of {0} seconds")
  @ValueSource(ints = {0, 4, 301})
  @DisplayName("Should reject the poll interval when it is outside the contract's bounds")
  void shouldRejectPollIntervalWhenOutsideContractBounds(int pollIntervalSeconds) {
    var properties = defaultProperties().pollIntervalSeconds(pollIntervalSeconds).build();

    assertThat(validator.validate(properties)).isNotEmpty();
  }

  @ParameterizedTest(name = "Should accept a poll interval of {0} seconds")
  @ValueSource(ints = {5, 300})
  @DisplayName("Should accept the poll interval when it is at the contract's bounds")
  void shouldAcceptPollIntervalWhenAtContractBounds(int pollIntervalSeconds) {
    var properties = defaultProperties().pollIntervalSeconds(pollIntervalSeconds).build();

    assertThat(validator.validate(properties)).isEmpty();
  }

  @ParameterizedTest(name = "Should reject a code TTL of {0}")
  @ValueSource(strings = {"PT30S", "PT31M"})
  @DisplayName("Should reject the code lifetime when it is outside the contract's bounds")
  void shouldRejectCodeLifetimeWhenOutsideContractBounds(String codeTtl) {
    var properties = defaultProperties().codeTtl(Duration.parse(codeTtl)).build();

    assertThat(validator.validate(properties)).isNotEmpty();
  }

  @ParameterizedTest(name = "Should accept a code TTL of {0}")
  @ValueSource(strings = {"PT1M", "PT30M"})
  @DisplayName("Should accept the code lifetime when it is at the contract's bounds")
  void shouldAcceptCodeLifetimeWhenAtContractBounds(String codeTtl) {
    var properties = defaultProperties().codeTtl(Duration.parse(codeTtl)).build();

    assertThat(validator.validate(properties)).isEmpty();
  }

  @ParameterizedTest(name = "Should reject verification path \"{0}\"")
  @ValueSource(strings = {"link", "/link?code=x", "/link#frag", "/../link"})
  @DisplayName("Should refuse the verification path when it is not a bare absolute path")
  void shouldRefuseVerificationPathWhenNotBareAbsolutePath(String verificationPath) {
    var properties = defaultProperties().verificationPath(verificationPath);

    assertThatThrownBy(properties::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("verification-path");
  }

  @Test
  @DisplayName("Should reject the sweep interval when it is not positive")
  void shouldRejectSweepIntervalWhenNotPositive() {
    var properties = defaultProperties().sweepInterval(Duration.ZERO).build();

    assertThat(validator.validate(properties)).isNotEmpty();
  }

  @Test
  @DisplayName("Should reject the issuance limit when it is not positive")
  void shouldRejectIssuanceLimitWhenNotPositive() {
    var properties = defaultProperties().maxOutstandingCodes(0).build();

    assertThat(validator.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("maxOutstandingCodes");
  }

  @Test
  @DisplayName("Should reject the guessing limit when it is not positive")
  void shouldRejectGuessingLimitWhenNotPositive() {
    var properties = defaultProperties().maxGuessAttempts(0).build();

    assertThat(validator.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("maxGuessAttempts");
  }

  @Test
  @DisplayName("Should reject the guess window when it is missing")
  void shouldRejectGuessWindowWhenMissing() {
    var properties = defaultProperties().guessWindow(null).build();

    assertThat(validator.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("guessWindow");
  }

  @Test
  @DisplayName("Should reject the guess window when it is shorter than one second")
  void shouldRejectGuessWindowWhenShorterThanOneSecond() {
    var properties = defaultProperties().guessWindow(Duration.ofMillis(999)).build();

    assertThat(validator.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("guessWindow");
  }

  @Test
  @DisplayName("Should accept the guess window when it is exactly one second")
  void shouldAcceptGuessWindowWhenExactlyOneSecond() {
    var properties = defaultProperties().guessWindow(Duration.ofSeconds(1)).build();

    assertThat(validator.validate(properties)).isEmpty();
  }

  private static DeviceAuthProperties.DeviceAuthPropertiesBuilder defaultProperties() {
    return DeviceAuthProperties.builder()
        .codeTtl(Duration.ofMinutes(10))
        .pollIntervalSeconds(5)
        .verificationPath("/link")
        .maxOutstandingCodes(50)
        .maxGuessAttempts(5)
        .guessWindow(Duration.ofMinutes(15))
        .sweepInterval(Duration.ofMinutes(15));
  }
}
