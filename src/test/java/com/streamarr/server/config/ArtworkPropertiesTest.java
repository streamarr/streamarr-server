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
    var properties = new ArtworkProperties(null, null, null);

    assertThat(properties).isEqualTo(new ArtworkProperties(16, 4, Duration.ofSeconds(2)));
  }

  @Test
  @DisplayName(
      "Should use the environment's thread caps when the packaged configuration reads them")
  void shouldUseTheEnvironmentsThreadCapsWhenThePackagedConfigurationReadsThem() {
    PACKAGED_CONFIGURATION
        .withPropertyValues(
            "ARTWORK_PRIMARY_THREADS=8",
            "ARTWORK_BACKGROUND_THREADS=2",
            "ARTWORK_RESULT_CHECK_INTERVAL=500ms")
        .run(
            context ->
                assertThat(context.getBean(ArtworkProperties.class))
                    .isEqualTo(new ArtworkProperties(8, 2, Duration.ofMillis(500))));
  }

  @Test
  @DisplayName("Should reject the settings when the primary scheduler has no thread")
  void shouldRejectTheSettingsWhenThePrimarySchedulerHasNoThread() {
    assertThatIllegalArgumentException().isThrownBy(() -> new ArtworkProperties(0, 4, null));
  }

  @Test
  @DisplayName("Should reject the settings when the background scheduler has no thread")
  void shouldRejectTheSettingsWhenTheBackgroundSchedulerHasNoThread() {
    assertThatIllegalArgumentException().isThrownBy(() -> new ArtworkProperties(16, 0, null));
  }

  @Test
  @DisplayName("Should reject the settings when the result check interval is not positive")
  void shouldRejectTheSettingsWhenTheResultCheckIntervalIsNotPositive() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ArtworkProperties(16, 4, Duration.ZERO));
  }
}
