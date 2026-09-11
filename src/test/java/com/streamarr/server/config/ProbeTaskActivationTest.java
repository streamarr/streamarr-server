package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.streamarr.server.fakes.FakeFileProcessingTaskRepository;
import com.streamarr.server.services.library.LegacyProbeTaskRecovery;
import com.streamarr.server.services.library.LibraryManagementService;
import com.streamarr.server.services.task.FileProcessingTaskCoordinator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("UnitTest")
@DisplayName("Legacy probe task recovery activation")
class ProbeTaskActivationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(LegacyProbeTaskRecovery.class)
          .withBean(
              FileProcessingTaskCoordinator.class,
              () ->
                  new FileProcessingTaskCoordinator(
                      new FakeFileProcessingTaskRepository(),
                      Clock.systemUTC(),
                      Duration.ofMinutes(1)))
          .withBean(LibraryManagementService.class, () -> mock(LibraryManagementService.class))
          .withBean(
              FileSystem.class,
              FileSystems::getDefault,
              definition -> definition.setDestroyMethodName(""))
          .withBean(VideoExtensionValidator.class, VideoExtensionValidator::new);

  @Test
  @DisplayName("Should enable legacy recovery with the application configuration")
  void shouldEnableLegacyRecoveryWithApplicationConfiguration() {
    contextRunner.run(
        context -> assertThat(context).hasNotFailed().hasSingleBean(LegacyProbeTaskRecovery.class));
  }

  @Test
  @DisplayName("Should leave legacy recovery disabled in shared test contexts")
  void shouldLeaveLegacyRecoveryDisabledInSharedTestContexts() {
    contextRunner
        .withPropertyValues("spring.profiles.active=test")
        .run(
            context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(LegacyProbeTaskRecovery.class));
  }
}
