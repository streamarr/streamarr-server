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
  @DisplayName("Should use the default check intervals when nothing is configured")
  void shouldUseTheDefaultCheckIntervalsWhenNothingIsConfigured() {
    var properties = ArtworkRunProperties.builder().build();

    assertThat(properties)
        .isEqualTo(
            ArtworkRunProperties.builder()
                .resultCheckInterval(Duration.ofSeconds(2))
                .taskResultCheckInterval(Duration.ofMillis(100))
                .build());
  }

  @Test
  @DisplayName(
      "Should use the environment's check intervals when the packaged configuration reads them")
  void shouldUseTheEnvironmentsCheckIntervalsWhenThePackagedConfigurationReadsThem() {
    PACKAGED_CONFIGURATION
        .withPropertyValues(
            "ARTWORK_RESULT_CHECK_INTERVAL=500ms", "ARTWORK_TASK_RESULT_CHECK_INTERVAL=50ms")
        .run(
            context ->
                assertThat(context.getBean(ArtworkRunProperties.class))
                    .isEqualTo(
                        ArtworkRunProperties.builder()
                            .resultCheckInterval(Duration.ofMillis(500))
                            .taskResultCheckInterval(Duration.ofMillis(50))
                            .build()));
  }

  @Test
  @DisplayName("Should reject the settings when the result check interval is not positive")
  void shouldRejectTheSettingsWhenTheResultCheckIntervalIsNotPositive() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> ArtworkRunProperties.builder().resultCheckInterval(Duration.ZERO).build());
  }

  @Test
  @DisplayName("Should reject the settings when the task result check interval is not positive")
  void shouldRejectTheSettingsWhenTheTaskResultCheckIntervalIsNotPositive() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> ArtworkRunProperties.builder().taskResultCheckInterval(Duration.ZERO).build());
  }
}
