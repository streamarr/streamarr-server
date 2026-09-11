package com.streamarr.server.services.library;

import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.task.FileProcessingTaskCoordinator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Builder
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    prefix = "db-scheduler",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class LegacyProbeTaskRecovery implements AutoCloseable {

  private final FileProcessingTaskCoordinator coordinator;
  private final LibraryManagementService libraryManagementService;
  private final FileSystem fileSystem;
  private final VideoExtensionValidator extensionValidator;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicBoolean recovering = new AtomicBoolean();

  @Scheduled(fixedDelayString = "${task.legacy-recovery-interval-ms:60000}")
  public synchronized void scheduleRecovery() {
    if (executor.isShutdown() || !recovering.compareAndSet(false, true)) {
      return;
    }

    executor.execute(this::runRecovery);
  }

  private void runRecovery() {
    try {
      recover();
    } catch (RuntimeException exception) {
      log.warn("Could not enumerate legacy probe tasks", exception);
    } finally {
      recovering.set(false);
    }
  }

  public void recover() {
    var afterId = Optional.<UUID>empty();
    while (!executor.isShutdown() && !Thread.currentThread().isInterrupted()) {
      var tasks = coordinator.findLegacyTasks(afterId, 100);
      if (tasks.isEmpty()) {
        return;
      }

      for (var task : tasks) {
        recover(task);
      }

      afterId = Optional.of(tasks.getLast().getId());
    }
  }

  private void recover(FileProcessingTask task) {
    if (executor.isShutdown() || Thread.currentThread().isInterrupted()) {
      return;
    }

    try {
      var path = FilepathCodec.decode(fileSystem, task.getFilepathUri());
      var attributes = Files.readAttributes(path, BasicFileAttributes.class);
      var extension = FilenameUtils.getExtension(path.getFileName().toString());
      if (!attributes.isRegularFile() || !extensionValidator.validate(extension)) {
        coordinator.fail(task.getId(), "Source is not a supported media file");
        return;
      }

      libraryManagementService.processDiscoveredFile(task.getLibraryId(), path);
      coordinator.complete(task.getId());
    } catch (NoSuchFileException _) {
      coordinator.fail(task.getId(), "Source file no longer exists");
    } catch (IOException | RuntimeException exception) {
      log.warn("Could not recover legacy task {}", task.getId(), exception);
    }
  }

  @Override
  @PreDestroy
  public synchronized void close() {
    executor.shutdownNow();
  }
}
