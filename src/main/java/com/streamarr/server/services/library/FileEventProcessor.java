package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.ProbeTaskSchedulingException;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import io.methvin.watcher.DirectoryChangeEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
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
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.util.backoff.ExponentialBackOff;

@Slf4j
class FileEventProcessor {

  // identity-based cancellation token
  private record StabilityToken() {}

  private record InFlightTask(Future<?> future, StabilityToken token) {}

  private final FileStabilityChecker fileStabilityChecker;
  private final LibraryManagementService libraryManagementService;
  private final IgnoredFileValidator ignoredFileValidator;

  private final ConcurrentHashMap<Path, InFlightTask> inFlightChecks = new ConcurrentHashMap<>();
  private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();

  private ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private List<Library> cachedLibraries = List.of();

  FileEventProcessor(
      FileStabilityChecker fileStabilityChecker,
      LibraryManagementService libraryManagementService,
      IgnoredFileValidator ignoredFileValidator) {
    this.fileStabilityChecker = fileStabilityChecker;
    this.libraryManagementService = libraryManagementService;
    this.ignoredFileValidator = ignoredFileValidator;
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
    if (Files.isDirectory(path)) {
      log.debug("Ignoring directory: {}", path);
      return;
    }

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
    } catch (RejectedExecutionException _) {
      log.warn("Executor shut down while scheduling stability check for: {}", path);
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
            var future = executor.submit(() -> runStabilityCheckWithCleanup(key, token, libraryId));
            return new InFlightTask(future, token);
          });
    } finally {
      stateLock.readLock().unlock();
    }
  }

  private void runStabilityCheckWithCleanup(Path path, StabilityToken token, UUID libraryId) {
    try {
      processWithRetry(path, libraryId);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException exception) {
      log.error("Failed to process discovered file: {}", path, exception);
    } finally {
      inFlightChecks.compute(
          path, (k, current) -> current != null && current.token() == token ? null : current);
    }
  }

  private void processWithRetry(Path path, UUID libraryId) throws InterruptedException {
    // Enqueue failures precede db-scheduler's durable retry boundary, so retain this in-flight
    // path.
    var backOff = new ExponentialBackOff().start();
    while (!Thread.currentThread().isInterrupted() && !tryProcessStableFile(path, libraryId)) {
      Thread.sleep(backOff.nextBackOff());
    }
  }

  private boolean tryProcessStableFile(Path path, UUID libraryId) {
    try {
      processStableFile(path, libraryId);
      return true;
    } catch (RuntimeException exception) {
      if (!(exception instanceof ProbeTaskSchedulingException)
          && ExceptionUtils.indexOfType(exception, SQLException.class) < 0) {
        throw exception;
      }

      log.warn("Retaining discovered file for retry after scheduling failure: {}", path, exception);
      return false;
    }
  }

  private void processStableFile(Path path, UUID libraryId) {
    log.info("Starting stability check for: {}", path);

    if (!fileStabilityChecker.waitForStability(path)) {
      log.warn("File did not stabilize: {}", path);
      return;
    }

    libraryManagementService.processDiscoveredFile(libraryId, path);
  }

  private void handleDelete(Path path) {
    var inFlight = inFlightChecks.remove(path);

    if (inFlight != null) {
      cancelInterrupting(inFlight.future());
      log.info("Cancelled in-flight check for deleted file: {}", path);
    }

    log.info("Watcher event type: DELETE -- filepath: {}", path);
  }

  private record LibraryWithPath(Library library, Path path) {}

  private Optional<UUID> resolveLibrary(Path path) {
    stateLock.readLock().lock();

    try {
      var absolutePath = path.toAbsolutePath();
      var fs = absolutePath.getFileSystem();

      return cachedLibraries.stream()
          .map(lib -> new LibraryWithPath(lib, FilepathCodec.decode(fs, lib.getFilepathUri())))
          .filter(lp -> absolutePath.startsWith(lp.path()))
          .max(Comparator.comparingInt(lp -> lp.path().toString().length()))
          .map(lp -> lp.library().getId());
    } finally {
      stateLock.readLock().unlock();
    }
  }

  private static void cancelInterrupting(Future<?> future) {
    future.cancel(true);
  }
}
