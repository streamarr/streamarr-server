package com.streamarr.server.services.library;

import com.streamarr.server.repositories.LibraryRepository;
import io.methvin.watcher.DirectoryWatcher;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@DependsOn("libraryRepository")
public class DirectoryWatchingService implements InitializingBean {

    private final LibraryRepository libraryRepository;
    private final Logger log;

    private final Set<Path> directoriesToWatch = new HashSet<>();
    private DirectoryWatcher watcher;

    // Server being setup, no libraries specified yet, early exit
    // Server setup, libraries specified
    // Server partially setup, library added, stop and recreate watcher

    // No directory yet
    // Given directory of library
    public void setup() throws IOException {
        if (directoriesToWatch.isEmpty()) {
            // TODO; log
            return;
        }

        // TODO: provider pattern to DI?
        this.watcher = DirectoryWatcher.builder()
            .paths(directoriesToWatch.stream().toList())
            .listener(event -> {
                switch (event.eventType()) {
                    case CREATE ->
                        // TODO: Ignore certain files, ignore "New Folder" / "untitled folder"
                        // TODO: Watch file. Is the file expanding? If yes, delay for ~60 seconds.
                        log.info("Watcher event type: {} -- filepath: {}", event.eventType(), event.path());
                    case MODIFY -> /* file modified */
                        log.info("Watcher event type: {} -- filepath: {}", event.eventType(), event.path());
                    case DELETE -> /* file deleted */
                        log.info("Watcher event type: {} -- filepath: {}", event.eventType(), event.path());
                }
            })
            // .fileHashing(false) // defaults to true
            // .logger(logger) // defaults to LoggerFactory.getLogger(DirectoryWatcher.class)
            // .watchService(watchService) // defaults based on OS to either JVM WatchService or the JNA macOS WatchService
            .build();

        watch();
    }

    public void addDirectory(Path path) throws IOException {
        if (watcher == null || watcher.isClosed()) {
            directoriesToWatch.add(path);

            setup();

            return;
        }

        // TODO: will this work?
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
