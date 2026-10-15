package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@Tag("UnitTest")
@DisplayName("Artwork Run Properties Tests")
class ArtworkRunPropertiesTest {

  private static final ApplicationContextRunner PACKAGED_CONFIGURATION =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(ArtworkRunPropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ArtworkRunProperties.class)
  static class ArtworkRunPropertiesConfiguration {}

  @Test
  @DisplayName("Should check results every two seconds when nothing is configured")
  void shouldCheckResultsEveryTwoSecondsWhenNothingIsConfigured() {
    var properties = ArtworkRunProperties.builder().build();

    assertThat(properties)
        .isEqualTo(
            ArtworkRunProperties.builder().resultCheckInterval(Duration.ofSeconds(2)).build());
  }

  @Test
  @DisplayName(
      "Should use the environment's result check interval when the packaged configuration reads it")
  void shouldUseTheEnvironmentsResultCheckIntervalWhenThePackagedConfigurationReadsIt() {
    PACKAGED_CONFIGURATION
        .withPropertyValues("ARTWORK_RESULT_CHECK_INTERVAL=500ms")
        .run(
            context ->
                assertThat(context.getBean(ArtworkRunProperties.class))
                    .isEqualTo(
                        ArtworkRunProperties.builder()
                            .resultCheckInterval(Duration.ofMillis(500))
                            .build()));
  }

  @Test
  @DisplayName("Should reject the settings when the result check interval is not positive")
  void shouldRejectTheSettingsWhenTheResultCheckIntervalIsNotPositive() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> ArtworkRunProperties.builder().resultCheckInterval(Duration.ZERO).build());
  }
}
