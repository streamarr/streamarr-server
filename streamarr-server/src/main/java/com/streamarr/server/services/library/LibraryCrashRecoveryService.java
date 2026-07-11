package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.repositories.LibraryRepository;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryCrashRecoveryService {

  private final LibraryRepository libraryRepository;
  private final ActiveScanChecker activeScanChecker;

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    var orphanedScanning =
        libraryRepository.findAllByStatus(LibraryStatus.SCANNING).stream()
            .filter(lib -> !activeScanChecker.isActivelyScanning(lib.getId()))
            .toList();
    var orphanedRefreshing = libraryRepository.findAllByStatus(LibraryStatus.REFRESHING);

    resetLibraries(orphanedScanning);
    resetLibraries(orphanedRefreshing);
  }

  @PreDestroy
  public void onShutdown() {
    resetLibraries(libraryRepository.findAllByStatus(LibraryStatus.SCANNING));
    resetLibraries(libraryRepository.findAllByStatus(LibraryStatus.REFRESHING));
  }

  private void resetLibraries(List<Library> libraries) {
    if (libraries.isEmpty()) {
      return;
    }

    log.warn(
        "Found {} library(ies) stuck in a busy state. Resetting to UNHEALTHY.", libraries.size());

    libraries.forEach(this::resetToUnhealthy);
  }

  private void resetToUnhealthy(Library library) {
    var previousStatus = library.getStatus();
    library.setStatus(LibraryStatus.UNHEALTHY);
    library.setScanCompletedOn(Instant.now());
    libraryRepository.save(library);

    log.warn(
        "Reset library '{}' (ID: {}) from {} to UNHEALTHY due to incomplete operation.",
        library.getName(),
        library.getId(),
        previousStatus);
  }
}
