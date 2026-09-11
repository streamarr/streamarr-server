package com.streamarr.server.services.task;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Instant;
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
                .filepathUri("file:///media/movies")
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
  @DisplayName("Should transition to completed when task completed")
  void shouldTransitionToCompletedWhenTaskCompleted() {
    var path = Path.of("/media/movies/Complete (2024).mkv");
    var claimed = taskRepository.save(legacyTask(path).build());

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
    var claimed = taskRepository.save(legacyTask(path).build());

    coordinator.fail(claimed.getId(), "Test error");

    var failed = taskRepository.findById(claimed.getId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(FileProcessingTaskStatus.FAILED);
    assertThat(failed.getErrorMessage()).isEqualTo("Test error");
    assertThat(failed.getCompletedOn()).isNotNull();
  }

  @Test
  @DisplayName("Should cancel pending task when file is deleted")
  void shouldCancelPendingTaskWhenFileIsDeleted() {
    var path = Path.of("/media/movies/Delete (2024).mkv");
    taskRepository.save(
        legacyTask(path)
            .status(FileProcessingTaskStatus.PENDING)
            .ownerInstanceId(null)
            .leaseExpiresAt(null)
            .build());

    coordinator.cancelTask(path);

    assertThat(taskRepository.count()).isZero();
  }

  @Test
  @DisplayName("Should not cancel processing task when file is deleted")
  void shouldNotCancelProcessingTaskWhenFileIsDeleted() {
    var path = Path.of("/media/movies/NoDelete (2024).mkv");
    taskRepository.save(legacyTask(path).build());

    coordinator.cancelTask(path);

    assertThat(taskRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should return empty when completing already completed task")
  void shouldReturnEmptyWhenCompletingAlreadyCompletedTask() {
    var path = Path.of("/media/movies/AlreadyCompleted (2024).mkv");
    var claimed = taskRepository.save(legacyTask(path).build());
    coordinator.complete(claimed.getId());

    var result = coordinator.complete(claimed.getId());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when failing already failed task")
  void shouldReturnEmptyWhenFailingAlreadyFailedTask() {
    var path = Path.of("/media/movies/AlreadyFailed (2024).mkv");
    var claimed = taskRepository.save(legacyTask(path).build());
    coordinator.fail(claimed.getId(), "First failure");

    var result = coordinator.fail(claimed.getId(), "Second failure");

    assertThat(result).isEmpty();
  }

  private FileProcessingTask.FileProcessingTaskBuilder legacyTask(Path path) {
    return FileProcessingTask.builder()
        .filepathUri(path.toAbsolutePath().toUri().toString())
        .libraryId(testLibrary.getId())
        .status(FileProcessingTaskStatus.PROCESSING)
        .ownerInstanceId("previous-server")
        .leaseExpiresAt(Instant.now().plusSeconds(60))
        .createdOn(Instant.now());
  }
}
