package com.streamarr.server.services.library;

import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import io.methvin.watcher.DirectoryWatcher;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@DependsOn("libraryRepository")
public class DirectoryWatchingService implements InitializingBean {

  private final LibraryRepository libraryRepository;
  private final IgnoredFileValidator ignoredFileValidator;

  private final Set<Path> directoriesToWatch = new HashSet<>();
  private DirectoryWatcher watcher;

  public void setup() throws IOException {
    if (directoriesToWatch.isEmpty()) {
      log.debug("No directories configured for watching, skipping setup.");
      return;
    }

    this.watcher =
        DirectoryWatcher.builder()
            .paths(directoriesToWatch.stream().toList())
            .listener(
                event -> {
                  if (ignoredFileValidator.shouldIgnore(event.path())) {
                    return;
                  }

                  switch (event.eventType()) {
                    case CREATE ->
                        log.info(
                            "Watcher event type: {} -- filepath: {}",
                            event.eventType(),
                            event.path());
                    case MODIFY ->
                        log.info(
                            "Watcher event type: {} -- filepath: {}",
                            event.eventType(),
                            event.path());
                    case DELETE ->
                        log.info(
                            "Watcher event type: {} -- filepath: {}",
                            event.eventType(),
                            event.path());
                  }
                })
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
    watcher.close();
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
