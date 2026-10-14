package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.repositories.media.ProbeVersionRescanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Scans each library that still holds a file probed under an earlier probe version, so the file
 * gets the current version's facts. A scan requests the current version for every file it lists. A
 * library whose scan a restart interrupted, or whose scan could not start, still holds such a file,
 * so the next startup scans it again. Each file keeps its earlier outcome for playback until its
 * new outcome is stored.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProbeVersionRescan {

  private final ProbeVersionRescanRepository rescans;
  private final LibraryScanTrigger scanTrigger;

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
    libraryIds.forEach(scanTrigger::triggerAsyncScan);
  }
}
