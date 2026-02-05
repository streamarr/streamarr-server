package com.streamarr.server.services.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.task.FileProcessingTaskStatus;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.task.FileProcessingTaskRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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

    assertThat(task).isNotNull();
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
  @DisplayName("Should claim task and transition to PROCESSING")
  void shouldClaimTaskAndTransitionToProcessing() {
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
  @DisplayName("Should prevent concurrent claims of same task with SELECT FOR UPDATE SKIP LOCKED")
  void shouldPreventConcurrentClaimsWithSkipLocked() throws Exception {
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
  @DisplayName("Should complete task successfully")
  void shouldCompleteTaskSuccessfully() {
    var path = Path.of("/media/movies/Complete (2024).mkv");
    coordinator.createTask(path, testLibrary.getId());
    var claimed = coordinator.claimNextTask().orElseThrow();

    coordinator.complete(claimed);

    var completed = taskRepository.findById(claimed.getId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(FileProcessingTaskStatus.COMPLETED);
    assertThat(completed.getCompletedOn()).isNotNull();
    assertThat(completed.getOwnerInstanceId()).isNull();
    assertThat(completed.getLeaseExpiresAt()).isNull();
  }

  @Test
  @DisplayName("Should fail task with error message")
  void shouldFailTaskWithErrorMessage() {
    var path = Path.of("/media/movies/Fail (2024).mkv");
    coordinator.createTask(path, testLibrary.getId());
    var claimed = coordinator.claimNextTask().orElseThrow();

    coordinator.fail(claimed, "Test error");

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
    coordinator.complete(claimed);

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
}
