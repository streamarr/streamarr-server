package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ProbeVersionRescanRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Queues a scan of every library once per probe version, so files probed under an earlier version
 * get the new version's facts. Each file keeps its earlier outcome for playback until its new
 * outcome is stored.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProbeVersionRescan {

  private final ProbeVersionRescanRepository rescans;
  private final LibraryRepository libraries;
  private final LibraryScanTrigger scanTrigger;
  private final Clock clock;

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    if (!rescans.tryMarkQueued(ProbeVersion.CURRENT, clock.instant())) {
      return;
    }

    var all = libraries.findAll();
    log.info(
        "Scanning {} libraries to collect the facts of probe version {}",
        all.size(),
        ProbeVersion.CURRENT);
    all.forEach(library -> scanTrigger.triggerAsyncScan(library.getId()));
  }
}
