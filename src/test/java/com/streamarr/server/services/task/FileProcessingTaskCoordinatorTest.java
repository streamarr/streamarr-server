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
  @DisplayName("Should create pending task when file event received")
  void shouldCreatePendingTaskWhenFileEventReceived() {
    var path = Path.of("/media/movies/Test (2024).mkv");
    var libraryId = UUID.randomUUID();

    var task = coordinator.createTask(path, libraryId);

    assertThat(task.getFilepathUri()).isEqualTo(path.toAbsolutePath().toUri().toString());
    assertThat(task.getLibraryId()).isEqualTo(libraryId);
    assertThat(task.getStatus()).isEqualTo(FileProcessingTaskStatus.PENDING);
    assertThat(task.getCreatedOn()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("Should return existing task when duplicate event received")
  void shouldReturnExistingTaskWhenDuplicateEventReceived() {
    var path = Path.of("/media/movies/Duplicate (2024).mkv");
    var libraryId = UUID.randomUUID();

    var task1 = coordinator.createTask(path, libraryId);
    var task2 = coordinator.createTask(path, libraryId);

    assertThat(task1.getId()).isEqualTo(task2.getId());
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should set owner and lease when task claimed")
  void shouldSetOwnerAndLeaseWhenTaskClaimed() {
    var path = Path.of("/media/movies/Claim (2024).mkv");
    var libraryId = UUID.randomUUID();
    coordinator.createTask(path, libraryId);

    var claimed = coordinator.claimNextTask();

    assertThat(claimed).isPresent();
    assertThat(claimed.get().getOwnerInstanceId()).isEqualTo(coordinator.getInstanceId());
    assertThat(claimed.get().getLeaseExpiresAt()).isEqualTo(NOW.plus(LEASE_DURATION));
    assertThat(claimed.get().getStatus()).isEqualTo(FileProcessingTaskStatus.PROCESSING);
  }

  @Test
  @DisplayName("Should return empty when no tasks to claim")
  void shouldReturnEmptyWhenNoTasksToClaim() {
    var claimed = coordinator.claimNextTask();

    assertThat(claimed).isEmpty();
  }

  @Test
  @DisplayName("Should transition to completed when task completed")
  void shouldTransitionToCompletedWhenTaskCompleted() {
    var path = Path.of("/media/movies/Complete (2024).mkv");
    var libraryId = UUID.randomUUID();
    coordinator.createTask(path, libraryId);
    var claimed = coordinator.claimNextTask().orElseThrow();

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
    var libraryId = UUID.randomUUID();
    coordinator.createTask(path, libraryId);
    var claimed = coordinator.claimNextTask().orElseThrow();

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
    var libraryId = UUID.randomUUID();
    coordinator.createTask(path, libraryId);

    coordinator.cancelTask(path);

    assertThat(repository.count()).isZero();
  }

  @Test
  @DisplayName("Should not delete processing task when file is deleted")
  void shouldNotDeleteProcessingTaskWhenFileDeleted() {
    var path = Path.of("/media/movies/NoDelete (2024).mkv");
    var libraryId = UUID.randomUUID();
    coordinator.createTask(path, libraryId);
    coordinator.claimNextTask();

    coordinator.cancelTask(path);

    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should create task with null lease when file event received")
  void shouldCreateTaskWithNullLeaseWhenFileEventReceived() {
    var path = Path.of("/media/movies/NullLease (2024).mkv");
    var libraryId = UUID.randomUUID();

    var task = coordinator.createTask(path, libraryId);

    assertThat(task.getLeaseExpiresAt()).isNull();
  }

  @Test
  @DisplayName("Should reclaim task when lease is null")
  void shouldReclaimTaskWhenLeaseIsNull() {
    var path = Path.of("/media/movies/NullLease (2024).mkv");
    var libraryId = UUID.randomUUID();
    var task = coordinator.createTask(path, libraryId);

    var reclaimed = coordinator.reclaimOrphanedTasks(10);

    assertThat(reclaimed).hasSize(1);
    assertThat(reclaimed.getFirst().getId()).isEqualTo(task.getId());
  }

  @Test
  @DisplayName("Should extend lease expiration when heartbeat fires")
  void shouldExtendLeaseExpirationWhenHeartbeatFires() {
    var path = Path.of("/media/movies/Extend (2024).mkv");
    var libraryId = UUID.randomUUID();
    coordinator.createTask(path, libraryId);
    var claimed = coordinator.claimNextTask().orElseThrow();
    var originalLease = claimed.getLeaseExpiresAt();

    var laterClock = Clock.fixed(NOW.plusSeconds(30), ZoneId.of("UTC"));
    var laterCoordinator =
        new FileProcessingTaskCoordinator(repository, laterClock, LEASE_DURATION);

    laterCoordinator.extendLeases();

    var updated = repository.findById(claimed.getId()).orElseThrow();
    assertThat(updated.getLeaseExpiresAt()).isAfter(originalLease);
  }

  @Test
  @DisplayName("Should not extend lease when task is completed")
  void shouldNotExtendLeaseWhenTaskIsCompleted() {
    var completedTask =
        FileProcessingTask.builder()
            .filepathUri("/media/movies/Completed (2024).mkv")
            .libraryId(UUID.randomUUID())
            .status(FileProcessingTaskStatus.COMPLETED)
            .ownerInstanceId(coordinator.getInstanceId())
            .leaseExpiresAt(NOW.plusSeconds(30))
            .createdOn(NOW)
            .completedOn(NOW)
            .build();
    repository.save(completedTask);

    var originalLease = completedTask.getLeaseExpiresAt();

    var laterClock = Clock.fixed(NOW.plusSeconds(60), ZoneId.of("UTC"));
    var laterCoordinator =
        new FileProcessingTaskCoordinator(repository, laterClock, LEASE_DURATION);

    laterCoordinator.extendLeases();

    var afterExtend = repository.findById(completedTask.getId()).orElseThrow();
    assertThat(afterExtend.getLeaseExpiresAt()).isEqualTo(originalLease);
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
    var libraryId = UUID.randomUUID();
    coordinator.createTask(path, libraryId);
    var claimed = coordinator.claimNextTask().orElseThrow();

    var result = coordinator.complete(claimed.getId());

    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(FileProcessingTaskStatus.COMPLETED);
  }

  @Test
  @DisplayName("Should return task when failing existing task")
  void shouldReturnTaskWhenFailingExistingTask() {
    var path = Path.of("/media/movies/ReturnFail (2024).mkv");
    var libraryId = UUID.randomUUID();
    coordinator.createTask(path, libraryId);
    var claimed = coordinator.claimNextTask().orElseThrow();

    var result = coordinator.fail(claimed.getId(), "Processing error");

    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(FileProcessingTaskStatus.FAILED);
  }
}
