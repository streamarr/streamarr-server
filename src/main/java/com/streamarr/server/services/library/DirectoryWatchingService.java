package com.streamarr.server.services.library;

import io.methvin.watcher.DirectoryWatcher;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Service
public class DirectoryWatchingService {

    private Set<Path> directoriesToWatch;
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
                    case CREATE: /* file created */
                        ;
                        break;
                    case MODIFY: /* file modified */
                        ;
                        break;
                    case DELETE: /* file deleted */
                        ;
                        break;
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
}
