package com.streamarr.server.services.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.domain.task.FileProcessingTaskStatus;
import com.streamarr.server.fakes.FakeFileProcessingTaskRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("FileProcessingTaskCoordinator Unit Tests")
class FileProcessingTaskCoordinatorTest {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(60);
  private static final Instant NOW = Instant.parse("2024-01-15T10:00:00Z");

  private FakeFileProcessingTaskRepository repository;
  private Clock clock;
  private FileProcessingTaskCoordinator coordinator;

  @BeforeEach
  void setUp() {
    repository = new FakeFileProcessingTaskRepository();
    clock = Clock.fixed(NOW, ZoneId.of("UTC"));
    coordinator = new FileProcessingTaskCoordinator(repository, clock, LEASE_DURATION);
  }

  @Test
  @DisplayName("Should transition to completed when task completed")
  void shouldTransitionToCompletedWhenTaskCompleted() {
    var path = Path.of("/media/movies/Complete (2024).mkv");
    var claimed = repository.save(legacyTask(path).build());

    coordinator.complete(claimed.getId());

    var completed = repository.findById(claimed.getId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(FileProcessingTaskStatus.COMPLETED);
    assertThat(completed.getCompletedOn()).isEqualTo(NOW);
    assertThat(completed.getOwnerInstanceId()).isNull();
    assertThat(completed.getLeaseExpiresAt()).isNull();
  }

  @Test
  @DisplayName("Should store error message when task failed")
  void shouldStoreErrorMessageWhenTaskFailed() {
    var path = Path.of("/media/movies/Fail (2024).mkv");
    var claimed = repository.save(legacyTask(path).build());

    coordinator.fail(claimed.getId(), "Processing error");

    var failed = repository.findById(claimed.getId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(FileProcessingTaskStatus.FAILED);
    assertThat(failed.getErrorMessage()).isEqualTo("Processing error");
    assertThat(failed.getCompletedOn()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("Should delete pending task when file is deleted")
  void shouldDeletePendingTaskWhenFileDeleted() {
    var path = Path.of("/media/movies/Delete (2024).mkv");
    repository.save(
        legacyTask(path)
            .status(FileProcessingTaskStatus.PENDING)
            .ownerInstanceId(null)
            .leaseExpiresAt(null)
            .build());

    coordinator.cancelTask(path);

    assertThat(repository.count()).isZero();
  }

  @Test
  @DisplayName("Should not delete processing task when file is deleted")
  void shouldNotDeleteProcessingTaskWhenFileDeleted() {
    var path = Path.of("/media/movies/NoDelete (2024).mkv");
    repository.save(legacyTask(path).build());

    coordinator.cancelTask(path);

    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should return empty when completing deleted task")
  void shouldReturnEmptyWhenCompletingDeletedTask() {
    var nonExistentTaskId = UUID.randomUUID();

    var result = coordinator.complete(nonExistentTaskId);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when failing deleted task")
  void shouldReturnEmptyWhenFailingDeletedTask() {
    var nonExistentTaskId = UUID.randomUUID();

    var result = coordinator.fail(nonExistentTaskId, "Some error");

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return task when completing existing task")
  void shouldReturnTaskWhenCompletingExistingTask() {
    var path = Path.of("/media/movies/ReturnComplete (2024).mkv");
    var claimed = repository.save(legacyTask(path).build());

    var result = coordinator.complete(claimed.getId());

    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(FileProcessingTaskStatus.COMPLETED);
  }

  @Test
  @DisplayName("Should return task when failing existing task")
  void shouldReturnTaskWhenFailingExistingTask() {
    var path = Path.of("/media/movies/ReturnFail (2024).mkv");
    var claimed = repository.save(legacyTask(path).build());

    var result = coordinator.fail(claimed.getId(), "Processing error");

    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(FileProcessingTaskStatus.FAILED);
  }

  @Test
  @DisplayName("Should return empty when completing already completed task")
  void shouldReturnEmptyWhenCompletingAlreadyCompletedTask() {
    var path = Path.of("/media/movies/AlreadyCompleted (2024).mkv");
    var claimed = repository.save(legacyTask(path).build());
    coordinator.complete(claimed.getId());

    var result = coordinator.complete(claimed.getId());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when failing already failed task")
  void shouldReturnEmptyWhenFailingAlreadyFailedTask() {
    var path = Path.of("/media/movies/AlreadyFailed (2024).mkv");
    var claimed = repository.save(legacyTask(path).build());
    coordinator.fail(claimed.getId(), "First failure");

    var result = coordinator.fail(claimed.getId(), "Second failure");

    assertThat(result).isEmpty();
  }

  private FileProcessingTask.FileProcessingTaskBuilder legacyTask(Path path) {
    return FileProcessingTask.builder()
        .filepathUri(path.toAbsolutePath().toUri().toString())
        .libraryId(UUID.randomUUID())
        .status(FileProcessingTaskStatus.PROCESSING)
        .ownerInstanceId("previous-server")
        .leaseExpiresAt(NOW.plusSeconds(60))
        .createdOn(NOW);
  }
}
