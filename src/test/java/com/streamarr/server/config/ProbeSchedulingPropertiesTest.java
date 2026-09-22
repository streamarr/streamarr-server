package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@Tag("UnitTest")
@DisplayName("Probe Scheduling Properties Tests")
class ProbeSchedulingPropertiesTest {

  private static final ApplicationContextRunner PACKAGED_CONFIGURATION =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(ProbeSchedulingPropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ProbeSchedulingProperties.class)
  static class ProbeSchedulingPropertiesConfiguration {}

  @Test
  @DisplayName("Should retry busy workers after five seconds when no delay is configured")
  void shouldRetryBusyWorkersAfterFiveSecondsWhenNoDelayIsConfigured() {
    var properties = new ProbeSchedulingProperties(null);

    assertThat(properties.busyWorkerRetryDelay()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName(
      "Should retry busy workers after five seconds when the packaged configuration sets no delay")
  void shouldRetryBusyWorkersAfterFiveSecondsWhenPackagedConfigurationSetsNoDelay() {
    PACKAGED_CONFIGURATION.run(
        context ->
            assertThat(context.getBean(ProbeSchedulingProperties.class).busyWorkerRetryDelay())
                .isEqualTo(Duration.ofSeconds(5)));
  }

  @Test
  @DisplayName(
      "Should retry busy workers after the environment's delay when PROBE_BUSY_WORKER_RETRY_DELAY"
          + " is set")
  void shouldRetryBusyWorkersAfterEnvironmentDelayWhenProbeBusyWorkerRetryDelayIsSet() {
    PACKAGED_CONFIGURATION
        .withPropertyValues("PROBE_BUSY_WORKER_RETRY_DELAY=250ms")
        .run(
            context ->
                assertThat(context.getBean(ProbeSchedulingProperties.class).busyWorkerRetryDelay())
                    .isEqualTo(Duration.ofMillis(250)));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject the busy worker retry delay when it is not positive")
  void shouldRejectBusyWorkerRetryDelayWhenItIsNotPositive(long seconds) {
    var delay = Duration.ofSeconds(seconds);

    assertThatThrownBy(() -> new ProbeSchedulingProperties(delay))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Busy worker retry delay must be positive");
  }
}
