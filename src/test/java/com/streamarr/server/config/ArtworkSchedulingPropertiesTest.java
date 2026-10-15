package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@Tag("UnitTest")
@DisplayName("Artwork Scheduling Properties Tests")
class ArtworkSchedulingPropertiesTest {

  private static final ApplicationContextRunner PACKAGED_CONFIGURATION =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(ArtworkSchedulingPropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ArtworkSchedulingProperties.class)
  static class ArtworkSchedulingPropertiesConfiguration {}

  @Test
  @DisplayName("Should run sixteen primary and four background threads when nothing is configured")
  void shouldRunSixteenPrimaryAndFourBackgroundThreadsWhenNothingIsConfigured() {
    var properties = ArtworkSchedulingProperties.builder().build();

    assertThat(properties)
        .isEqualTo(
            ArtworkSchedulingProperties.builder().primaryThreads(16).backgroundThreads(4).build());
  }

  @Test
  @DisplayName(
      "Should use the environment's thread caps when the packaged configuration reads them")
  void shouldUseTheEnvironmentsThreadCapsWhenThePackagedConfigurationReadsThem() {
    PACKAGED_CONFIGURATION
        .withPropertyValues("ARTWORK_PRIMARY_THREADS=8", "ARTWORK_BACKGROUND_THREADS=2")
        .run(
            context ->
                assertThat(context.getBean(ArtworkSchedulingProperties.class))
                    .isEqualTo(
                        ArtworkSchedulingProperties.builder()
                            .primaryThreads(8)
                            .backgroundThreads(2)
                            .build()));
  }

  @Test
  @DisplayName("Should reject the settings when the primary scheduler has no thread")
  void shouldRejectTheSettingsWhenThePrimarySchedulerHasNoThread() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ArtworkSchedulingProperties.builder().primaryThreads(0).build());
  }

  @Test
  @DisplayName("Should reject the settings when the background scheduler has no thread")
  void shouldRejectTheSettingsWhenTheBackgroundSchedulerHasNoThread() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ArtworkSchedulingProperties.builder().backgroundThreads(0).build());
  }
}
