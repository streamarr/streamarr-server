package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.validation.VideoExtensionValidator;
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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;

@Slf4j
class FileEventProcessor {

  private final FileStabilityChecker fileStabilityChecker;
  private final LibraryManagementService libraryManagementService;
  private final VideoExtensionValidator videoExtensionValidator;

  private final ConcurrentHashMap<Path, Future<?>> inFlightChecks = new ConcurrentHashMap<>();

  private volatile ExecutorService executor;
  private volatile List<Library> cachedLibraries;

  FileEventProcessor(
      FileStabilityChecker fileStabilityChecker,
      LibraryManagementService libraryManagementService,
      VideoExtensionValidator videoExtensionValidator) {
    this.fileStabilityChecker = fileStabilityChecker;
    this.libraryManagementService = libraryManagementService;
    this.videoExtensionValidator = videoExtensionValidator;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    this.cachedLibraries = List.of();
  }

  void handleFileEvent(DirectoryChangeEvent.EventType eventType, Path path) {
    switch (eventType) {
      case CREATE, MODIFY -> handleCreateOrModify(path);
      case DELETE -> handleDelete(path);
      case OVERFLOW -> log.warn("Watcher event buffer overflow, some events may have been lost");
    }
  }

  void reset(List<Library> libraries) {
    executor.shutdownNow();
    inFlightChecks.clear();
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    this.cachedLibraries = List.copyOf(libraries);
  }

  void shutdown() {
    executor.shutdownNow();
    inFlightChecks.clear();
  }

  private void handleCreateOrModify(Path path) {
    var extension = FilenameUtils.getExtension(path.getFileName().toString());
    if (!videoExtensionValidator.validate(extension)) {
      log.debug("Ignoring non-video file: {}", path);
      return;
    }

    try {
      inFlightChecks.compute(
          path,
          (key, existing) -> {
            if (existing != null && !existing.isDone()) {
              log.debug("Stability check already in progress for: {}", path);
              return existing;
            }
            return executor.submit(
                () -> {
                  try {
                    processStableFile(key);
                  } finally {
                    inFlightChecks.remove(key);
                  }
                });
          });
    } catch (RejectedExecutionException e) {
      log.warn("Executor shut down, ignoring event for: {}", path);
    }
  }

  private void processStableFile(Path path) {
    log.info("Starting stability check for: {}", path);

    if (!fileStabilityChecker.awaitStability(path)) {
      log.warn("File did not stabilize: {}", path);
      return;
    }

    var optionalLibraryId = resolveLibrary(path);
    if (optionalLibraryId.isEmpty()) {
      log.warn("No library matches path: {}", path);
      return;
    }

    try {
      libraryManagementService.processDiscoveredFile(optionalLibraryId.get(), path);
    } catch (Exception e) {
      log.error("Failed to process discovered file: {}", path, e);
    }
  }

  private void handleDelete(Path path) {
    var future = inFlightChecks.remove(path);
    if (future != null) {
      future.cancel(true);
      log.info("Cancelled in-flight check for deleted file: {}", path);
    }
    log.info("Watcher event type: DELETE -- filepath: {}", path);
  }

  private Optional<UUID> resolveLibrary(Path path) {
    var absolutePath = path.toAbsolutePath();
    var fs = absolutePath.getFileSystem();

    return cachedLibraries.stream()
        .filter(library -> absolutePath.startsWith(fs.getPath(library.getFilepath())))
        .max(Comparator.comparingInt(library -> library.getFilepath().length()))
        .map(Library::getId);
  }
}
