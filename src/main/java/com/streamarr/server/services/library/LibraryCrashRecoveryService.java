package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.repositories.LibraryRepository;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
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

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    recoverOrphanedScans();
  }

  @PreDestroy
  public void onShutdown() {
    recoverOrphanedScans();
  }

  void recoverOrphanedScans() {
    var orphanedLibraries = libraryRepository.findAllByStatus(LibraryStatus.SCANNING);

    if (orphanedLibraries.isEmpty()) {
      return;
    }

    log.warn(
        "Found {} library(ies) stuck in SCANNING status. Resetting to UNHEALTHY.",
        orphanedLibraries.size());

    orphanedLibraries.forEach(this::resetToUnhealthy);
  }

  private void resetToUnhealthy(Library library) {
    library.setStatus(LibraryStatus.UNHEALTHY);
    library.setScanCompletedOn(Instant.now());
    libraryRepository.save(library);

    log.warn(
        "Reset library '{}' (ID: {}) from SCANNING to UNHEALTHY due to incomplete scan.",
        library.getName(),
        library.getId());
  }
}
