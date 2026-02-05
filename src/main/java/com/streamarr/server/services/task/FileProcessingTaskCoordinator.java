package com.streamarr.server.services.task;

import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.domain.task.FileProcessingTaskStatus;
import com.streamarr.server.repositories.task.FileProcessingTaskRepository;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class FileProcessingTaskCoordinator {

  private static final List<FileProcessingTaskStatus> ACTIVE_STATUSES =
      List.of(FileProcessingTaskStatus.PENDING, FileProcessingTaskStatus.PROCESSING);

  private final FileProcessingTaskRepository repository;
  private final Clock clock;
  private final Duration leaseDuration;
  private final String instanceId;

  @Autowired
  public FileProcessingTaskCoordinator(
      FileProcessingTaskRepository repository,
      Clock clock,
      @Value("${task.coordinator.lease-duration-seconds:60}") int leaseDurationSeconds) {
    this(repository, clock, Duration.ofSeconds(leaseDurationSeconds));
  }

  public FileProcessingTaskCoordinator(
      FileProcessingTaskRepository repository, Clock clock, Duration leaseDuration) {
    this.repository = repository;
    this.clock = clock;
    this.leaseDuration = leaseDuration;
    this.instanceId = generateInstanceId();
  }

  public FileProcessingTask createTask(Path path, UUID libraryId) {
    var filepath = path.toAbsolutePath().toString();

    var existing = repository.findByFilepathAndStatusIn(filepath, ACTIVE_STATUSES);
    if (existing.isPresent()) {
      log.debug("Task already exists for filepath: {}", filepath);
      return existing.get();
    }

    var task =
        FileProcessingTask.builder()
            .filepath(filepath)
            .libraryId(libraryId)
            .status(FileProcessingTaskStatus.PENDING)
            .createdOn(clock.instant())
            .build();

    try {
      return repository.save(task);
    } catch (DataIntegrityViolationException e) {
      log.debug("Concurrent task creation detected for filepath: {}", filepath);
      return repository
          .findByFilepathAndStatusIn(filepath, ACTIVE_STATUSES)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Task should exist after constraint violation for: " + filepath, e));
    }
  }

  public Optional<FileProcessingTask> claimNextTask() {
    var leaseExpiresAt = clock.instant().plus(leaseDuration);
    return repository.claimNextTask(instanceId, leaseExpiresAt);
  }

  public List<FileProcessingTask> reclaimOrphanedTasks(int limit) {
    var now = clock.instant();
    var leaseExpiresAt = now.plus(leaseDuration);
    return repository.reclaimOrphanedTasks(instanceId, leaseExpiresAt, now, limit);
  }

  @Scheduled(fixedDelayString = "${task.coordinator.heartbeat-interval-ms:15000}")
  public void extendLeases() {
    var newLeaseExpiresAt = clock.instant().plus(leaseDuration);
    var count = repository.extendLeases(instanceId, newLeaseExpiresAt);
    if (count > 0) {
      log.debug("Extended leases for {} tasks", count);
    }
  }

  public void complete(UUID taskId) {
    var task =
        repository
            .findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    task.setStatus(FileProcessingTaskStatus.COMPLETED);
    task.setCompletedOn(clock.instant());
    task.setOwnerInstanceId(null);
    task.setLeaseExpiresAt(null);
    repository.save(task);
    log.info("Completed task for: {}", task.getFilepath());
  }

  public void fail(UUID taskId, String errorMessage) {
    var task =
        repository
            .findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    task.setStatus(FileProcessingTaskStatus.FAILED);
    task.setErrorMessage(errorMessage);
    task.setCompletedOn(clock.instant());
    task.setOwnerInstanceId(null);
    task.setLeaseExpiresAt(null);
    repository.save(task);
    log.warn("Failed task for: {} with error: {}", task.getFilepath(), errorMessage);
  }

  @Transactional
  public void cancelTask(Path path) {
    var filepath = path.toAbsolutePath().toString();
    repository.deleteByFilepathAndStatusIn(filepath, List.of(FileProcessingTaskStatus.PENDING));
    log.info("Cancelled pending task for: {}", filepath);
  }

  public String getInstanceId() {
    return instanceId;
  }

  private static String generateInstanceId() {
    var hostname = resolveHostname();
    var pid = ManagementFactory.getRuntimeMXBean().getPid();
    return hostname + ":" + pid;
  }

  private static String resolveHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
