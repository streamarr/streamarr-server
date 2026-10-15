package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.exceptions.LibraryOperationNotStartedException;
import com.streamarr.server.exceptions.ResourceBusyException;
import com.streamarr.server.repositories.media.ProbeVersionRescanRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Scans each library that still holds a file probed under an earlier probe version, so the file
 * gets the current version's facts. A scan requests the current version for every file it lists. A
 * library whose scan could not start still holds such a file, so the next startup scans it again. A
 * library that already runs an operation refuses the scan; that operation's scan, or the next
 * startup, requests the current version. Each file keeps its earlier outcome for playback until its
 * new outcome is stored.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProbeVersionRescan {

  private final ProbeVersionRescanRepository rescans;
  private final LibraryOperationService libraryOperationService;

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    var libraryIds = rescans.findLibraryIdsProbedBefore(ProbeVersion.CURRENT);
    if (libraryIds.isEmpty()) {
      return;
    }

    log.info(
        "Scanning {} libraries to collect the facts of probe version {}",
        libraryIds.size(),
        ProbeVersion.CURRENT);
    libraryIds.forEach(this::startScan);
  }

  // One library's failed start must not keep the other libraries from scanning. The failure is
  // counted on the library-operation request metric.
  private void startScan(UUID libraryId) {
    try {
      libraryOperationService.startLibraryScanAsSystemActor(libraryId);
    } catch (LibraryOperationNotStartedException | ResourceBusyException e) {
      log.warn("Could not start the probe-version scan of library {}", libraryId, e);
    }
  }
}
