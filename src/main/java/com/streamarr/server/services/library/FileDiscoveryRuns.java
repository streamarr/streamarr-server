package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.LibraryScanFailedException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.ArtworkRequestService;
import com.streamarr.server.services.ArtworkRunWaiter;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Opens the runs that collect the primary artwork and probes of one scan or file discovery, and
 * waits until a scan's artwork and probes have saved results.
 */
@Service
@Builder
@RequiredArgsConstructor
public class FileDiscoveryRuns {

  private final ArtworkRequestService artworkRequestService;
  private final ArtworkRunWaiter artworkRunWaiter;
  private final ProbeRuns probeRuns;
  private final MediaFileRepository mediaFiles;

  /** Opens the runs of a file discovery, which waits for nothing. */
  public FileDiscovery open(String operation, Library library) {
    var artworkRun =
        artworkRequestService.openRun(
            operation + " library '" + library.getName() + "'", ImageRefreshMode.PRESERVE);
    return new FileDiscovery(library, artworkRun, probeRuns.open());
  }

  /** Opens the runs of a scan, whose artwork requests carry the scan's library operation id. */
  public FileDiscovery openScan(Library library, UUID libraryOperationId) {
    var artworkRun =
        artworkRequestService.openLibraryOperationRun(
            "scan of library '" + library.getName() + "'",
            ImageRefreshMode.PRESERVE,
            libraryOperationId);
    return new FileDiscovery(library, artworkRun, probeRuns.open());
  }

  /**
   * Waits for the probe results of a closed scan's discovery and for the primary artwork results of
   * its library operation, including artwork that an earlier execution of the operation requested.
   * The wait for probes ends early once the admission end is reached, because neither a stop nor a
   * shutdown waits for probes. Background artwork and the retries of failed artwork steps and
   * probes continue in the background.
   *
   * @throws LibraryScanFailedException if the stored results cannot be read or the wait fails
   *     unexpectedly
   * @throws InterruptedException if the wait is interrupted
   */
  public ScanResults awaitResults(FileDiscovery discovery, AdmissionEnd admissionEnd)
      throws InterruptedException {
    var libraryName = discovery.library().getName();
    try {
      return awaitRequiredResults(discovery, admissionEnd);
    } catch (ExecutionException exception) {
      throw new LibraryScanFailedException(libraryName, exception.getCause());
    } catch (RuntimeException exception) {
      throw new LibraryScanFailedException(libraryName, exception);
    }
  }

  // Probes are checked while the artwork finishes, so each phase's timer stops with its own work.
  private ScanResults awaitRequiredResults(FileDiscovery discovery, AdmissionEnd admissionEnd)
      throws InterruptedException, ExecutionException {
    try (var executor = SystemWork.newExecutorRunningAsSystemActor("library-scan-probes-")) {
      var probes =
          executor.submit(() -> probeRuns.awaitResults(discovery.probeRun(), admissionEnd));
      try {
        var artwork = artworkRunWaiter.awaitLibraryOperationResults(discovery.artworkRun());
        return ScanResults.builder()
            .files(mediaFiles.countStatuses(discovery.probeRun().mediaFileIds()))
            .artwork(artwork)
            .probes(probes.get())
            .backgroundImagesPending(artworkRequestService.pendingBackgroundImages())
            .build();
      } finally {
        probes.cancel(true);
      }
    }
  }
}
