package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.domain.task.FileProcessingTaskStatus;
import com.streamarr.server.fakes.FakeFileProcessingTaskRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.task.FileProcessingTaskCoordinator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Tag("UnitTest")
@DisplayName("Legacy probe recovery runtime")
class LegacyProbeTaskRecoveryTest {

  @TempDir Path directory;

  @Test
  @DisplayName(
      "Should automatically recover legacy work on a virtual thread when probes are enabled")
  void shouldAutomaticallyRecoverLegacyWorkOnVirtualThreadWhenProbesAreEnabled() {
    var tasks = new ObservedLegacyTasks();

    contextRunner(tasks)
        .withPropertyValues("db-scheduler.enabled=true", "task.legacy-recovery-interval-ms=10")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              await()
                  .atMost(Duration.ofSeconds(2))
                  .until(() -> tasks.executingThread.get() != null);
              assertThat(tasks.executingThread.get().isVirtual()).isTrue();
            });
  }

  @Test
  @DisplayName("Should keep one recovery pass active without blocking other scheduled work")
  void shouldKeepOneRecoveryPassActiveWithoutBlockingOtherScheduledWork() {
    var tasks = new BlockingLegacyTasks();
    var heartbeat = new ScheduledCounter();

    contextRunner(tasks)
        .withBean(ScheduledCounter.class, () -> heartbeat)
        .withPropertyValues("db-scheduler.enabled=true", "task.legacy-recovery-interval-ms=10")
        .run(
            context -> {
              try {
                assertThat(context).hasNotFailed();
                await().until(() -> tasks.reads.get() > 0);
                var ticks = heartbeat.ticks.get();
                await().until(() -> heartbeat.ticks.get() > ticks + 3);
                assertThat(tasks.reads.get()).isEqualTo(1);
              } finally {
                tasks.release.countDown();
              }
            });
  }

  @Test
  @DisplayName("Should disable legacy recovery when probe execution is disabled")
  void shouldDisableLegacyRecoveryWhenProbeExecutionIsDisabled() {
    contextRunner(new ObservedLegacyTasks())
        .withPropertyValues("db-scheduler.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(LegacyProbeTaskRecovery.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should leave unprocessed legacy work pending when the runtime shuts down")
  void shouldLeaveUnprocessedLegacyWorkPendingWhenRuntimeShutsDown(boolean preserveInterruption) {
    var tasks = new InterruptedLegacyBatch(preserveInterruption);
    var pending =
        tasks.save(
            FileProcessingTask.builder()
                .libraryId(UUID.randomUUID())
                .filepathUri(FilepathCodec.encode(directory.resolve("Missing.mkv")))
                .status(FileProcessingTaskStatus.PENDING)
                .build());

    contextRunner(tasks)
        .withPropertyValues("db-scheduler.enabled=true")
        .run(context -> await().until(() -> tasks.executingThread.get() != null));

    await().until(() -> !tasks.executingThread.get().isAlive());
    assertThat(tasks.findById(pending.getId()).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.PENDING);
  }

  private ApplicationContextRunner contextRunner(FakeFileProcessingTaskRepository tasks) {
    return new ApplicationContextRunner()
        .withUserConfiguration(LegacyProbeTaskRecovery.class, SchedulingConfiguration.class)
        .withBean(
            FileProcessingTaskCoordinator.class,
            () ->
                new FileProcessingTaskCoordinator(tasks, Clock.systemUTC(), Duration.ofMinutes(1)))
        .withBean(LibraryManagementService.class, () -> mock(LibraryManagementService.class))
        .withBean(
            FileSystem.class,
            FileSystems::getDefault,
            definition -> definition.setDestroyMethodName(""))
        .withBean(VideoExtensionValidator.class, VideoExtensionValidator::new);
  }

  private static class ObservedLegacyTasks extends FakeFileProcessingTaskRepository {

    private final AtomicReference<Thread> executingThread = new AtomicReference<>();

    @Override
    public List<FileProcessingTask> findLegacyTasks(Optional<UUID> afterId, int limit) {
      executingThread.set(Thread.currentThread());
      return List.of();
    }
  }

  private static class BlockingLegacyTasks extends FakeFileProcessingTaskRepository {

    private final AtomicInteger reads = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public List<FileProcessingTask> findLegacyTasks(Optional<UUID> afterId, int limit) {
      reads.incrementAndGet();
      try {
        release.await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }

      return List.of();
    }
  }

  private static class ScheduledCounter {

    private final AtomicInteger ticks = new AtomicInteger();

    @Scheduled(fixedDelay = 10)
    public void tick() {
      ticks.incrementAndGet();
    }
  }

  private static class InterruptedLegacyBatch extends FakeFileProcessingTaskRepository {

    private final AtomicReference<Thread> executingThread = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final boolean preserveInterruption;

    private InterruptedLegacyBatch(boolean preserveInterruption) {
      this.preserveInterruption = preserveInterruption;
    }

    @Override
    public List<FileProcessingTask> findLegacyTasks(Optional<UUID> afterId, int limit) {
      executingThread.set(Thread.currentThread());
      try {
        release.await();
      } catch (InterruptedException _) {
        if (preserveInterruption) {
          Thread.currentThread().interrupt();
        }
      }

      return super.findLegacyTasks(afterId, limit);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class SchedulingConfiguration {}
}
