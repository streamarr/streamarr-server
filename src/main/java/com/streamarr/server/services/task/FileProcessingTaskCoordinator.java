package com.streamarr.server.services.task;

import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.repositories.task.FileProcessingTaskRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retires the watcher's legacy task rows. Probes themselves run through db-scheduler; this
 * coordinator only drains what earlier releases left in {@code file_processing_task}.
 */
@Slf4j
@Service
public class FileProcessingTaskCoordinator {

  private final FileProcessingTaskRepository repository;
  private final Clock clock;
  private final Duration leaseDuration;
  @Getter private final String instanceId;

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

  public List<FileProcessingTask> findLegacyTasks(Optional<UUID> afterId, int limit) {
    return repository.findLegacyTasks(afterId, limit);
  }

  public Optional<FileProcessingTask> complete(UUID taskId) {
    var result = repository.completeTask(taskId, clock.instant());

    result.ifPresentOrElse(
        task -> log.info("Completed task for: {}", task.getFilepathUri()),
        () -> log.debug("Task already deleted or not active, skipping completion: {}", taskId));

    return result;
  }

  public Optional<FileProcessingTask> fail(UUID taskId, String errorMessage) {
    var result = repository.failTask(taskId, errorMessage, clock.instant());

    result.ifPresentOrElse(
        task -> log.warn("Failed task for: {} with error: {}", task.getFilepathUri(), errorMessage),
        () -> log.debug("Task already deleted or not active, skipping failure: {}", taskId));

    return result;
  }

  @Transactional
  public void cancelTask(Path path) {
    var filepath = FilepathCodec.encode(path);
    repository.cancelTask(filepath);
    log.info("Cancelled task for: {}", filepath);
  }

  private static String generateInstanceId() {
    var hostname = resolveHostname();
    var pid = ManagementFactory.getRuntimeMXBean().getPid();
    return hostname + ":" + pid;
  }

  private static String resolveHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception _) {
      return "unknown";
    }
  }
}
