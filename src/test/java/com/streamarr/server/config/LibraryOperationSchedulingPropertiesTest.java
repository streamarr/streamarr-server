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
@DisplayName("Library Operation Scheduling Properties Tests")
class LibraryOperationSchedulingPropertiesTest {

  private static final ApplicationContextRunner PACKAGED_CONFIGURATION =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(LibraryOperationSchedulingPropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(LibraryOperationSchedulingProperties.class)
  static class LibraryOperationSchedulingPropertiesConfiguration {}

  @Test
  @DisplayName("Should run eight library operations at once when nothing is configured")
  void shouldRunEightLibraryOperationsAtOnceWhenNothingIsConfigured() {
    var properties = LibraryOperationSchedulingProperties.builder().build();

    assertThat(properties.threads()).isEqualTo(8);
  }

  @Test
  @DisplayName("Should use the environment's thread cap when the packaged configuration reads it")
  void shouldUseTheEnvironmentsThreadCapWhenThePackagedConfigurationReadsIt() {
    PACKAGED_CONFIGURATION
        .withPropertyValues("LIBRARY_OPERATION_THREADS=3")
        .run(
            context ->
                assertThat(context.getBean(LibraryOperationSchedulingProperties.class).threads())
                    .isEqualTo(3));
  }

  @Test
  @DisplayName(
      "Should drain library operations for 30 seconds at shutdown when nothing is configured")
  void shouldDrainLibraryOperationsForThirtySecondsAtShutdownWhenNothingIsConfigured() {
    var properties = LibraryOperationSchedulingProperties.builder().build();

    assertThat(properties.shutdownDrainTimeout()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName(
      "Should use the environment's shutdown drain timeout when the packaged configuration reads"
          + " it")
  void shouldUseTheEnvironmentsShutdownDrainTimeoutWhenThePackagedConfigurationReadsIt() {
    PACKAGED_CONFIGURATION
        .withPropertyValues("LIBRARY_OPERATION_SHUTDOWN_DRAIN_TIMEOUT=12s")
        .run(
            context ->
                assertThat(
                        context
                            .getBean(LibraryOperationSchedulingProperties.class)
                            .shutdownDrainTimeout())
                    .isEqualTo(Duration.ofSeconds(12)));
  }

  @Test
  @DisplayName("Should reject the settings when the shutdown drain timeout is not positive")
  void shouldRejectTheSettingsWhenTheShutdownDrainTimeoutIsNotPositive() {
    var noDrain =
        LibraryOperationSchedulingProperties.builder().shutdownDrainTimeout(Duration.ZERO);
    var negativeDrain =
        LibraryOperationSchedulingProperties.builder().shutdownDrainTimeout(Duration.ofSeconds(-1));

    assertThatIllegalArgumentException().isThrownBy(noDrain::build);
    assertThatIllegalArgumentException().isThrownBy(negativeDrain::build);
  }

  @Test
  @DisplayName("Should reject the settings when the scheduler has no thread")
  void shouldRejectTheSettingsWhenTheSchedulerHasNoThread() {
    var noThreads = LibraryOperationSchedulingProperties.builder().threads(0);

    assertThatIllegalArgumentException().isThrownBy(noThreads::build);
  }
}
