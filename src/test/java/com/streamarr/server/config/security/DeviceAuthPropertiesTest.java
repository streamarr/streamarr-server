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
  @DisplayName("Should accept the shipped defaults")
  void shouldAcceptShippedDefaults() {
    assertThat(validator.validate(defaultProperties().build())).isEmpty();
  }

  @ParameterizedTest(name = "Should reject a poll interval of {0} seconds")
  @ValueSource(ints = {0, 4, 301})
  @DisplayName("Should keep the poll interval inside the contract's bounds")
  void shouldKeepPollIntervalInsideContractsBounds(int pollIntervalSeconds) {
    var properties = defaultProperties().pollIntervalSeconds(pollIntervalSeconds).build();

    assertThat(validator.validate(properties)).isNotEmpty();
  }

  @ParameterizedTest(name = "Should reject a code TTL of {0}")
  @ValueSource(strings = {"PT30S", "PT31M"})
  @DisplayName("Should keep the code lifetime inside the contract's bounds")
  void shouldKeepCodeLifetimeInsideContractsBounds(String codeTtl) {
    var properties = defaultProperties().codeTtl(Duration.parse(codeTtl)).build();

    assertThat(validator.validate(properties)).isNotEmpty();
  }

  @ParameterizedTest(name = "Should reject verification path \"{0}\"")
  @ValueSource(strings = {"link", "/link?code=x", "/link#frag", "/../link"})
  @DisplayName("Should refuse a verification path that is not a bare absolute path")
  void shouldRefuseVerificationPathThatIsNotBareAbsolutePath(String verificationPath) {
    var properties = defaultProperties().verificationPath(verificationPath);

    assertThatThrownBy(properties::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("verification-path");
  }

  @Test
  @DisplayName("Should require a positive typed sweep interval")
  void shouldRequirePositiveTypedSweepInterval() {
    var properties = defaultProperties().sweepInterval(Duration.ZERO).build();

    assertThat(validator.validate(properties)).isNotEmpty();
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
