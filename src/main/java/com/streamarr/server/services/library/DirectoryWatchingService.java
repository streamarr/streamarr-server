package com.streamarr.server.services.library;

import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.library.events.LibraryAddedEvent;
import com.streamarr.server.services.task.FileProcessingTaskCoordinator;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import io.methvin.watcher.DirectoryWatcher;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@DependsOn("libraryRepository")
public class DirectoryWatchingService implements InitializingBean {

  private final LibraryRepository libraryRepository;
  private final FileEventProcessor fileEventProcessor;

  private final Set<Path> directoriesToWatch = new HashSet<>();
  private DirectoryWatcher watcher;

  public DirectoryWatchingService(
      LibraryRepository libraryRepository,
      FileStabilityChecker fileStabilityChecker,
      LibraryManagementService libraryManagementService,
      IgnoredFileValidator ignoredFileValidator,
      FileProcessingTaskCoordinator taskCoordinator) {
    this.libraryRepository = libraryRepository;
    this.fileEventProcessor =
        new FileEventProcessor(
            fileStabilityChecker, libraryManagementService, ignoredFileValidator, taskCoordinator);
  }

  public void setup() throws IOException {
    if (directoriesToWatch.isEmpty()) {
      log.debug("No directories configured for watching, skipping setup.");
      return;
    }

    fileEventProcessor.reset(libraryRepository.findAll());

    this.watcher =
        DirectoryWatcher.builder()
            .paths(directoriesToWatch.stream().toList())
            .listener(event -> fileEventProcessor.handleFileEvent(event.eventType(), event.path()))
            .build();

    watch();
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
    fileEventProcessor.shutdown();
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

  @EventListener
  public void onLibraryAdded(LibraryAddedEvent event) {
    try {
      addDirectory(Path.of(event.filepath()));
    } catch (IOException e) {
      log.error("Failed to start watching directory for library: {}", event.filepath(), e);
    }
  }
}
