package com.streamarr.server.services.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.domain.task.FileProcessingTaskStatus;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.task.FileProcessingTaskRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("FileProcessingTaskCoordinator Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileProcessingTaskCoordinatorIT extends AbstractIntegrationTest {

  @Autowired private FileProcessingTaskCoordinator coordinator;

  @Autowired private FileProcessingTaskRepository taskRepository;

  @Autowired private LibraryRepository libraryRepository;

  private Library testLibrary;

  @BeforeAll
  void setupLibrary() {
    testLibrary =
        libraryRepository.saveAndFlush(
            Library.builder()
                .name("Test Library")
                .filepath("/media/movies")
                .backend(LibraryBackend.LOCAL)
                .status(LibraryStatus.HEALTHY)
                .type(MediaType.MOVIE)
                .externalAgentStrategy(ExternalAgentStrategy.TMDB)
                .build());
  }

  @BeforeEach
  void cleanup() {
    taskRepository.deleteAll();
  }

  @Test
  @DisplayName("Should create pending task when file event received")
  void shouldCreatePendingTaskWhenFileEventReceived() {
    var path = Path.of("/media/movies/Test (2024).mkv");

    var task = coordinator.createTask(path, testLibrary.getId());

    assertThat(task.getId()).isNotNull();
    assertThat(task.getFilepath()).isEqualTo(path.toAbsolutePath().toString());
    assertThat(task.getLibraryId()).isEqualTo(testLibrary.getId());
    assertThat(task.getStatus()).isEqualTo(FileProcessingTaskStatus.PENDING);
  }

  @Test
  @DisplayName("Should return existing task when duplicate event received")
  void shouldReturnExistingTaskWhenDuplicateEventReceived() {
    var path = Path.of("/media/movies/Duplicate (2024).mkv");

    var task1 = coordinator.createTask(path, testLibrary.getId());
    var task2 = coordinator.createTask(path, testLibrary.getId());

    assertThat(task1.getId()).isEqualTo(task2.getId());
    assertThat(taskRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should transition to processing when task claimed")
  void shouldTransitionToProcessingWhenTaskClaimed() {
    var path = Path.of("/media/movies/Claim (2024).mkv");
    coordinator.createTask(path, testLibrary.getId());

    var claimed = coordinator.claimNextTask();

    assertThat(claimed).isPresent();
    assertThat(claimed.get().getStatus()).isEqualTo(FileProcessingTaskStatus.PROCESSING);
    assertThat(claimed.get().getOwnerInstanceId()).isEqualTo(coordinator.getInstanceId());
    assertThat(claimed.get().getLeaseExpiresAt()).isNotNull();
  }

  @Test
  @DisplayName("Should return empty when no tasks to claim")
  void shouldReturnEmptyWhenNoTasksToClaim() {
    var claimed = coordinator.claimNextTask();

    assertThat(claimed).isEmpty();
  }

  @Test
  @DisplayName("Should claim only once when multiple threads compete")
  void shouldClaimOnlyOnceWhenMultipleThreadsCompete() throws Exception {
    var path = Path.of("/media/movies/Concurrent (2024).mkv");
    coordinator.createTask(path, testLibrary.getId());

    var threadCount = 10;
    var executor = Executors.newFixedThreadPool(threadCount);
    var latch = new CountDownLatch(threadCount);
    var claimedCount = new AtomicInteger(0);
    var claimedIds = new ArrayList<UUID>();

    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            try {
              var claimed = coordinator.claimNextTask();
              if (claimed.isPresent()) {
                synchronized (claimedIds) {
                  claimedIds.add(claimed.get().getId());
                }
                claimedCount.incrementAndGet();
              }
            } finally {
              latch.countDown();
            }
          });
    }

    assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    assertThat(claimedCount.get()).isEqualTo(1);
    assertThat(claimedIds).hasSize(1);
  }

  @Test
  @DisplayName("Should transition to completed when task completed")
  void shouldTransitionToCompletedWhenTaskCompleted() {
    var path = Path.of("/media/movies/Complete (2024).mkv");
    coordinator.createTask(path, testLibrary.getId());
    var claimed = coordinator.claimNextTask().orElseThrow();

    coordinator.complete(claimed.getId());

    var completed = taskRepository.findById(claimed.getId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(FileProcessingTaskStatus.COMPLETED);
    assertThat(completed.getCompletedOn()).isNotNull();
    assertThat(completed.getOwnerInstanceId()).isNull();
    assertThat(completed.getLeaseExpiresAt()).isNull();
  }

  @Test
  @DisplayName("Should store error message when task failed")
  void shouldStoreErrorMessageWhenTaskFailed() {
    var path = Path.of("/media/movies/Fail (2024).mkv");
    coordinator.createTask(path, testLibrary.getId());
    var claimed = coordinator.claimNextTask().orElseThrow();

    coordinator.fail(claimed.getId(), "Test error");

    var failed = taskRepository.findById(claimed.getId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(FileProcessingTaskStatus.FAILED);
    assertThat(failed.getErrorMessage()).isEqualTo("Test error");
    assertThat(failed.getCompletedOn()).isNotNull();
  }

  @Test
  @DisplayName("Should allow new task for same path after completion")
  void shouldAllowNewTaskForSamePathAfterCompletion() {
    var path = Path.of("/media/movies/Reprocess (2024).mkv");

    var task1 = coordinator.createTask(path, testLibrary.getId());
    var claimed = coordinator.claimNextTask().orElseThrow();
    coordinator.complete(claimed.getId());

    var task2 = coordinator.createTask(path, testLibrary.getId());

    assertThat(task2.getId()).isNotEqualTo(task1.getId());
    assertThat(task2.getStatus()).isEqualTo(FileProcessingTaskStatus.PENDING);
  }

  @Test
  @DisplayName("Should cancel pending task when file is deleted")
  void shouldCancelPendingTaskWhenFileIsDeleted() {
    var path = Path.of("/media/movies/Delete (2024).mkv");
    coordinator.createTask(path, testLibrary.getId());

    coordinator.cancelTask(path);

    assertThat(taskRepository.count()).isZero();
  }

  @Test
  @DisplayName("Should not cancel processing task when file is deleted")
  void shouldNotCancelProcessingTaskWhenFileIsDeleted() {
    var path = Path.of("/media/movies/NoDelete (2024).mkv");
    coordinator.createTask(path, testLibrary.getId());
    coordinator.claimNextTask();

    coordinator.cancelTask(path);

    assertThat(taskRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should create single task when concurrent creates race")
  void shouldCreateSingleTaskWhenConcurrentCreatesRace() throws Exception {
    var path = Path.of("/media/movies/RaceCondition (2024).mkv");
    var threadCount = 10;
    var executor = Executors.newFixedThreadPool(threadCount);
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount);
    var createdTasks = new CopyOnWriteArrayList<FileProcessingTask>();
    var exceptions = new CopyOnWriteArrayList<Exception>();

    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              var task = coordinator.createTask(path, testLibrary.getId());
              createdTasks.add(task);
            } catch (Exception e) {
              exceptions.add(e);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(doneLatch.getCount()).isZero());

    executor.shutdown();

    assertThat(exceptions).isEmpty();
    assertThat(createdTasks).hasSize(threadCount);
    assertThat(createdTasks.stream().map(FileProcessingTask::getId).distinct()).hasSize(1);
    assertThat(taskRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should reclaim task when lease expired")
  void shouldReclaimTaskWhenLeaseExpired() {
    var path = Path.of("/media/movies/Orphan (2024).mkv");
    coordinator.createTask(path, testLibrary.getId());
    var claimed = coordinator.claimNextTask().orElseThrow();

    claimed.setLeaseExpiresAt(Instant.now().minus(Duration.ofMinutes(5)));
    taskRepository.save(claimed);

    var reclaimed = coordinator.reclaimOrphanedTasks(10);

    assertThat(reclaimed).hasSize(1);
    assertThat(reclaimed.getFirst().getStatus()).isEqualTo(FileProcessingTaskStatus.PENDING);
  }

  @Test
  @DisplayName("Should create task with null lease when file event received")
  void shouldCreateTaskWithNullLeaseWhenFileEventReceived() {
    var path = Path.of("/media/movies/NullLease (2024).mkv");

    var task = coordinator.createTask(path, testLibrary.getId());

    assertThat(task.getLeaseExpiresAt()).isNull();
  }

  @Test
  @DisplayName("Should reclaim task when lease is null")
  void shouldReclaimTaskWhenLeaseIsNull() {
    var path = Path.of("/media/movies/NullLease (2024).mkv");
    var task = coordinator.createTask(path, testLibrary.getId());

    var reclaimed = coordinator.reclaimOrphanedTasks(10);

    assertThat(reclaimed).hasSize(1);
    assertThat(reclaimed.getFirst().getId()).isEqualTo(task.getId());
  }
}
