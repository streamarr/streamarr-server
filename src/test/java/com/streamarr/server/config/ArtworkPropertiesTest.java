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
@DisplayName("Artwork Properties Tests")
class ArtworkPropertiesTest {

  private static final ApplicationContextRunner PACKAGED_CONFIGURATION =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(ArtworkPropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ArtworkProperties.class)
  static class ArtworkPropertiesConfiguration {}

  @Test
  @DisplayName("Should run sixteen primary and four background threads when nothing is configured")
  void shouldRunSixteenPrimaryAndFourBackgroundThreadsWhenNothingIsConfigured() {
    var properties = ArtworkProperties.builder().build();

    assertThat(properties)
        .isEqualTo(
            ArtworkProperties.builder()
                .primaryThreads(16)
                .backgroundThreads(4)
                .resultCheckInterval(Duration.ofSeconds(2))
                .lockTimeout(Duration.ofSeconds(5))
                .build());
  }

  @Test
  @DisplayName(
      "Should use the environment's thread caps when the packaged configuration reads them")
  void shouldUseTheEnvironmentsThreadCapsWhenThePackagedConfigurationReadsThem() {
    PACKAGED_CONFIGURATION
        .withPropertyValues(
            "ARTWORK_PRIMARY_THREADS=8",
            "ARTWORK_BACKGROUND_THREADS=2",
            "ARTWORK_RESULT_CHECK_INTERVAL=500ms",
            "ARTWORK_LOCK_TIMEOUT=250ms")
        .run(
            context ->
                assertThat(context.getBean(ArtworkProperties.class))
                    .isEqualTo(
                        ArtworkProperties.builder()
                            .primaryThreads(8)
                            .backgroundThreads(2)
                            .resultCheckInterval(Duration.ofMillis(500))
                            .lockTimeout(Duration.ofMillis(250))
                            .build()));
  }

  @Test
  @DisplayName("Should reject the settings when the primary scheduler has no thread")
  void shouldRejectTheSettingsWhenThePrimarySchedulerHasNoThread() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ArtworkProperties.builder().primaryThreads(0).build());
  }

  @Test
  @DisplayName("Should reject the settings when the background scheduler has no thread")
  void shouldRejectTheSettingsWhenTheBackgroundSchedulerHasNoThread() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ArtworkProperties.builder().backgroundThreads(0).build());
  }

  @Test
  @DisplayName("Should reject the settings when the result check interval is not positive")
  void shouldRejectTheSettingsWhenTheResultCheckIntervalIsNotPositive() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ArtworkProperties.builder().resultCheckInterval(Duration.ZERO).build());
  }

  @Test
  @DisplayName("Should reject the settings when the lock timeout is shorter than a millisecond")
  void shouldRejectTheSettingsWhenTheLockTimeoutIsShorterThanAMillisecond() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ArtworkProperties.builder().lockTimeout(Duration.ofNanos(1)).build());
  }
}
