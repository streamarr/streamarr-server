package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@DependsOn("libraryRepository")
public class DirectoryWatchingService implements InitializingBean {

  private final LibraryRepository libraryRepository;
  private final FileStabilityChecker fileStabilityChecker;
  private final LibraryManagementService libraryManagementService;
  private final VideoExtensionValidator videoExtensionValidator;

  private final Set<Path> directoriesToWatch = new HashSet<>();
  private final ConcurrentHashMap<Path, CompletableFuture<Void>> inFlightChecks =
      new ConcurrentHashMap<>();

  private DirectoryWatcher watcher;
  private ExecutorService executor;

  public DirectoryWatchingService(
      LibraryRepository libraryRepository,
      FileStabilityChecker fileStabilityChecker,
      LibraryManagementService libraryManagementService,
      VideoExtensionValidator videoExtensionValidator) {
    this.libraryRepository = libraryRepository;
    this.fileStabilityChecker = fileStabilityChecker;
    this.libraryManagementService = libraryManagementService;
    this.videoExtensionValidator = videoExtensionValidator;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  public void setup() throws IOException {
    if (directoriesToWatch.isEmpty()) {
      log.debug("No directories configured for watching, skipping setup.");
      return;
    }

    this.watcher =
        DirectoryWatcher.builder()
            .paths(directoriesToWatch.stream().toList())
            .listener(event -> handleFileEvent(event.eventType(), event.path()))
            .build();

    watch();
  }

  void handleFileEvent(DirectoryChangeEvent.EventType eventType, Path path) {
    switch (eventType) {
      case CREATE, MODIFY -> handleCreateOrModify(path);
      case DELETE -> handleDelete(path);
      case OVERFLOW -> log.warn("Watcher event buffer overflow, some events may have been lost");
    }
  }

  private void handleCreateOrModify(Path path) {
    var extension = FilenameUtils.getExtension(path.getFileName().toString());
    if (!videoExtensionValidator.validate(extension)) {
      log.debug("Ignoring non-video file: {}", path);
      return;
    }

    var submitted = new AtomicBoolean(false);
    inFlightChecks.computeIfAbsent(
        path,
        key -> {
          submitted.set(true);
          return CompletableFuture.runAsync(
              () -> {
                try {
                  processStableFile(key);
                } finally {
                  inFlightChecks.remove(key);
                }
              },
              executor);
        });

    if (!submitted.get()) {
      log.debug("Stability check already in progress for: {}", path);
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

  Optional<UUID> resolveLibrary(Path path) {
    var absolutePath = path.toAbsolutePath();
    var fs = absolutePath.getFileSystem();

    return libraryRepository.findAll().stream()
        .filter(library -> absolutePath.startsWith(fs.getPath(library.getFilepath())))
        .max(Comparator.comparingInt(library -> library.getFilepath().length()))
        .map(Library::getId);
  }

  public void addDirectory(Path path) throws IOException {
    if (watcher == null || watcher.isClosed()) {
      directoriesToWatch.add(path);

      setup();

      return;
    }

    stopWatching();

    directoriesToWatch.add(path);

    setup();
  }

  public void removeDirectory(Path path) throws IOException {
    if (directoriesToWatch.isEmpty()) {
      return;
    }

    if (!directoriesToWatch.contains(path)) {
      return;
    }

    stopWatching();

    directoriesToWatch.remove(path);

    if (!directoriesToWatch.isEmpty()) {
      setup();
    }
  }

  @PreDestroy
  public void stopWatching() throws IOException {
    if (watcher != null) {
      watcher.close();
    }
    executor.close();
    inFlightChecks.clear();
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  public CompletableFuture<Void> watch() {
    return watcher.watchAsync();
  }

  @Override
  public void afterPropertiesSet() {

    var repositories = libraryRepository.findAll();

    repositories.forEach(rep -> directoriesToWatch.add(Path.of(rep.getFilepath())));

    try {
      setup();
    } catch (IOException ex) {
      log.error("Failed to start library watcher", ex);
    }
  }
}
