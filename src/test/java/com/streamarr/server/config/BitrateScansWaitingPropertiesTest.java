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
@DisplayName("Bitrate scans waiting properties tests")
class BitrateScansWaitingPropertiesTest {

  private static final ApplicationContextRunner PACKAGED_CONFIGURATION =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(BitrateScansWaitingPropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(BitrateScansWaitingProperties.class)
  static class BitrateScansWaitingPropertiesConfiguration {}

  @Test
  @DisplayName(
      "Should count once a minute, the OTLP registry's own publish step, when nothing sets a step")
  void shouldCountOnceAMinuteWhenNothingSetsAStep() {
    PACKAGED_CONFIGURATION.run(
        context ->
            assertThat(context.getBean(BitrateScansWaitingProperties.class).refreshInterval())
                .isEqualTo(Duration.ofMinutes(1)));
  }

  @Test
  @DisplayName("Should count once per metrics publish when the OTLP publish step is set")
  void shouldCountOncePerMetricsPublishWhenTheOtlpPublishStepIsSet() {
    PACKAGED_CONFIGURATION
        .withPropertyValues("management.otlp.metrics.export.step=10s")
        .run(
            context ->
                assertThat(context.getBean(BitrateScansWaitingProperties.class).refreshInterval())
                    .isEqualTo(Duration.ofSeconds(10)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"PT0S", "PT-1S"})
  @DisplayName("Should reject the refresh interval when it is not positive")
  void shouldRejectTheRefreshIntervalWhenItIsNotPositive(String interval) {
    var refreshInterval = Duration.parse(interval);

    assertThatThrownBy(() -> new BitrateScansWaitingProperties(refreshInterval))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }
}
