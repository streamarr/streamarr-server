package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.services.task.FileProcessingTaskCoordinator;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import io.methvin.watcher.DirectoryChangeEvent;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class FileEventProcessor {

  private record StabilityToken() {}

  private record InFlightTask(Future<?> future, StabilityToken token) {}

  private final FileStabilityChecker fileStabilityChecker;
  private final LibraryManagementService libraryManagementService;
  private final IgnoredFileValidator ignoredFileValidator;
  private final FileProcessingTaskCoordinator taskCoordinator;

  private final ConcurrentHashMap<Path, InFlightTask> inFlightChecks = new ConcurrentHashMap<>();
  private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();

  private ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private List<Library> cachedLibraries = List.of();

  FileEventProcessor(
      FileStabilityChecker fileStabilityChecker,
      LibraryManagementService libraryManagementService,
      IgnoredFileValidator ignoredFileValidator,
      FileProcessingTaskCoordinator taskCoordinator) {
    this.fileStabilityChecker = fileStabilityChecker;
    this.libraryManagementService = libraryManagementService;
    this.ignoredFileValidator = ignoredFileValidator;
    this.taskCoordinator = taskCoordinator;
  }

  void handleFileEvent(DirectoryChangeEvent.EventType eventType, Path path) {
    switch (eventType) {
      case CREATE, MODIFY -> handleCreateOrModify(path);
      case DELETE -> handleDelete(path);
      case OVERFLOW -> log.warn("Watcher event buffer overflow, some events may have been lost");
    }
  }

  void reset(List<Library> libraries) {
    stateLock.writeLock().lock();
    try {
      executor.shutdownNow();
      inFlightChecks.clear();
      executor = Executors.newVirtualThreadPerTaskExecutor();
      cachedLibraries = List.copyOf(libraries);
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  void shutdown() {
    stateLock.writeLock().lock();
    try {
      executor.shutdownNow();
      inFlightChecks.clear();
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private void handleCreateOrModify(Path path) {
    if (ignoredFileValidator.shouldIgnore(path)) {
      log.debug("Ignoring file: {}", path);
      return;
    }

    var optionalLibraryId = resolveLibrary(path);
    if (optionalLibraryId.isEmpty()) {
      log.warn("No library matches path: {}", path);
      return;
    }

    try {
      scheduleStabilityCheck(path, optionalLibraryId.get());
    } catch (RejectedExecutionException e) {
      log.warn(
          "Executor shut down while scheduling stability check for: {}. "
              + "Any created task will be reclaimed by distributed lease recovery.",
          path);
    }
  }

  private void scheduleStabilityCheck(Path path, UUID libraryId) {
    stateLock.readLock().lock();
    try {
      var token = new StabilityToken();
      inFlightChecks.compute(
          path,
          (key, existing) -> {
            if (existing != null && !existing.future().isDone()) {
              log.debug("Stability check already in progress for: {}", path);
              return existing;
            }
            var task = taskCoordinator.createTask(key, libraryId);
            var future = executor.submit(() -> runStabilityCheckWithCleanup(key, token, task));
            return new InFlightTask(future, token);
          });
    } finally {
      stateLock.readLock().unlock();
    }
  }

  private void runStabilityCheckWithCleanup(Path path, StabilityToken token, FileProcessingTask task) {
    try {
      processStableFile(path, task);
    } finally {
      inFlightChecks.compute(
          path, (k, current) -> current != null && current.token() == token ? null : current);
    }
  }

  private void processStableFile(Path path, FileProcessingTask task) {
    log.info("Starting stability check for: {}", path);

    if (!fileStabilityChecker.awaitStability(path)) {
      log.warn("File did not stabilize: {}", path);
      taskCoordinator.fail(task, "File did not stabilize within timeout");
      return;
    }

    try {
      libraryManagementService.processDiscoveredFile(task.getLibraryId(), path);
      taskCoordinator.complete(task);
    } catch (Exception e) {
      log.error("Failed to process discovered file: {}", path, e);
      taskCoordinator.fail(task, Optional.ofNullable(e.getMessage()).orElse(e.toString()));
    }
  }

  private void handleDelete(Path path) {
    var inFlight = inFlightChecks.remove(path);
    if (inFlight != null) {
      inFlight.future().cancel(true);
      log.info("Cancelled in-flight check for deleted file: {}", path);
    }
    taskCoordinator.cancelTask(path);
    log.info("Watcher event type: DELETE -- filepath: {}", path);
  }

  private Optional<UUID> resolveLibrary(Path path) {
    stateLock.readLock().lock();
    try {
      var absolutePath = path.toAbsolutePath();
      var fs = absolutePath.getFileSystem();

      return cachedLibraries.stream()
          .filter(library -> absolutePath.startsWith(fs.getPath(library.getFilepath())))
          .max(Comparator.comparingInt(library -> library.getFilepath().length()))
          .map(Library::getId);
    } finally {
      stateLock.readLock().unlock();
    }
  }
}
