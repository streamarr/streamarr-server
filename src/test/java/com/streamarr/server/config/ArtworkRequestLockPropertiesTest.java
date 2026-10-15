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
@DisplayName("Artwork Request Lock Properties Tests")
class ArtworkRequestLockPropertiesTest {

  private static final ApplicationContextRunner PACKAGED_CONFIGURATION =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(ArtworkRequestLockPropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ArtworkRequestLockProperties.class)
  static class ArtworkRequestLockPropertiesConfiguration {}

  @Test
  @DisplayName("Should wait five seconds for the request lock when nothing is configured")
  void shouldWaitFiveSecondsForTheRequestLockWhenNothingIsConfigured() {
    var properties = ArtworkRequestLockProperties.builder().build();

    assertThat(properties)
        .isEqualTo(ArtworkRequestLockProperties.builder().timeout(Duration.ofSeconds(5)).build());
  }

  @Test
  @DisplayName("Should use the environment's lock timeout when the packaged configuration reads it")
  void shouldUseTheEnvironmentsLockTimeoutWhenThePackagedConfigurationReadsIt() {
    PACKAGED_CONFIGURATION
        .withPropertyValues("ARTWORK_LOCK_TIMEOUT=250ms")
        .run(
            context ->
                assertThat(context.getBean(ArtworkRequestLockProperties.class))
                    .isEqualTo(
                        ArtworkRequestLockProperties.builder()
                            .timeout(Duration.ofMillis(250))
                            .build()));
  }

  @Test
  @DisplayName("Should reject the settings when the lock timeout is shorter than a millisecond")
  void shouldRejectTheSettingsWhenTheLockTimeoutIsShorterThanAMillisecond() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> ArtworkRequestLockProperties.builder().timeout(Duration.ofNanos(1)).build());
  }
}
