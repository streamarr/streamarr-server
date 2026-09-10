package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.ProbeClaim;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeFileProcessingTaskRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.FileStabilityChecker;
import com.streamarr.server.services.library.ProbeTaskDispatcher;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.task.FileProcessingTaskCoordinator;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Tag("UnitTest")
@DisplayName("Probe task dispatcher configuration tests")
class ProbeTaskDispatcherConfigurationTest {

  @TempDir Path directory;

  private final ProbeTasks repository = new ProbeTasks();
  private final CountDownLatch release = new CountDownLatch(1);
  private final ProbeOutcome outcome = new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA);
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(
              ProbeTaskDispatcherConfiguration.class, SchedulingConfiguration.class)
          .withBean(
              FileProcessingTaskCoordinator.class,
              () ->
                  new FileProcessingTaskCoordinator(
                      repository, Clock.systemUTC(), Duration.ofSeconds(60)))
          .withBean(
              FileSystem.class,
              FileSystems::getDefault,
              definition -> definition.setDestroyMethodName(""))
          .withBean(FileStabilityChecker.class, () -> _ -> true)
          .withBean(
              PersistedProbeReader.class, () -> new PersistedProbeReader(_ -> Optional.empty()))
          .withBean(
              FfprobeService.class,
              () ->
                  _ -> {
                    try {
                      release.await();
                      return outcome;
                    } catch (InterruptedException exception) {
                      Thread.currentThread().interrupt();
                      throw new ProbeExecutionException(exception);
                    }
                  });

  @Test
  @DisplayName("Should poll and renew claimed work when probe execution is explicitly enabled")
  void shouldPollAndRenewClaimedWorkWhenProbeExecutionIsExplicitlyEnabled() throws Exception {
    var path = Files.writeString(directory.resolve("movie.mkv"), "media");
    var request =
        ProbeRequest.builder()
            .mediaFileId(UUID.randomUUID())
            .libraryId(UUID.randomUUID())
            .filepathUri(FilepathCodec.encode(path))
            .snapshot(
                new SourceFileSnapshot(
                    Files.size(path), Files.getLastModifiedTime(path).toInstant()))
            .probeVersion(ProbeVersion.CURRENT)
            .build();
    var claim =
        ProbeClaim.builder()
            .taskId(UUID.randomUUID())
            .claimId(UUID.randomUUID())
            .request(request)
            .leaseExpiresAt(Instant.now().plusSeconds(60))
            .build();
    repository.available.add(claim);
    var next =
        ProbeClaim.builder()
            .taskId(UUID.randomUUID())
            .claimId(UUID.randomUUID())
            .request(request)
            .leaseExpiresAt(claim.leaseExpiresAt())
            .build();
    repository.available.add(next);
    try {
      contextRunner
          .withPropertyValues(
              "task.probe.enabled=true",
              "task.probe.poll-interval-ms=10",
              "task.coordinator.heartbeat-interval-ms=10",
              "task.probe.max-concurrent=1")
          .run(
              context -> {
                assertThat(context).hasNotFailed().hasSingleBean(ProbeTaskDispatcher.class);
                await().untilAsserted(() -> assertThat(repository.renewed.get()).isPositive());
                assertThat(repository.available).containsExactly(next);
                repository.available.clear();
                release.countDown();
                await()
                    .untilAsserted(
                        () ->
                            assertThat(repository.published.get())
                                .isEqualTo(
                                    ProbePublication.builder()
                                        .claim(claim)
                                        .snapshot(request.snapshot())
                                        .probeVersion(ProbeVersion.CURRENT)
                                        .outcome(outcome)
                                        .build()));
              });
    } finally {
      release.countDown();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class SchedulingConfiguration {}

  @Test
  @DisplayName("Should reject zero execution slots when probe execution is enabled")
  void shouldRejectZeroExecutionSlotsWhenProbeExecutionIsEnabled() {
    contextRunner
        .withPropertyValues("task.probe.enabled=true", "task.probe.max-concurrent=0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("Should use the default capacity when probe execution is enabled without a limit")
  void shouldUseTheDefaultCapacityWhenProbeExecutionIsEnabledWithoutALimit() {
    contextRunner
        .withPropertyValues("task.probe.enabled=true")
        .run(
            context -> assertThat(context).hasNotFailed().hasSingleBean(ProbeTaskDispatcher.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"false"})
  @NullSource
  @DisplayName("Should leave the runtime absent when probe execution is not enabled")
  void shouldLeaveTheRuntimeAbsentWhenProbeExecutionIsNotEnabled(String enabled) {
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(ProbeTaskDispatcherConfiguration.class);
    if (enabled != null) {
      runner = runner.withPropertyValues("task.probe.enabled=" + enabled);
    }

    runner.run(
        context -> assertThat(context).hasNotFailed().doesNotHaveBean(ProbeTaskDispatcher.class));
  }

  private static class ProbeTasks extends FakeFileProcessingTaskRepository {
    private final ConcurrentLinkedQueue<ProbeClaim> available = new ConcurrentLinkedQueue<>();
    private final AtomicInteger renewed = new AtomicInteger();
    private final AtomicReference<ProbePublication> published = new AtomicReference<>();

    @Override
    public Optional<ProbeClaim> claimProbeTask(String instanceId, Instant leaseExpiresAt) {
      return Optional.ofNullable(available.poll());
    }

    @Override
    public boolean renewProbe(ProbeClaim claim, Instant leaseExpiresAt) {
      renewed.incrementAndGet();
      return true;
    }

    @Override
    public boolean publishProbe(ProbePublication publication) {
      published.set(publication);
      return true;
    }
  }
}
