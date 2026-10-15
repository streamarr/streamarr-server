package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.LibraryScanFailedException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.ArtworkRequestService;
import com.streamarr.server.services.ArtworkRunWaiter;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.util.concurrent.ExecutionException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Opens the runs that collect the primary artwork and probes of one scan or file discovery, and
 * waits until each has a saved result.
 */
@Service
@Builder
@RequiredArgsConstructor
public class FileDiscoveryRuns {

  private final ArtworkRequestService artworkRequestService;
  private final ArtworkRunWaiter artworkRunWaiter;
  private final ProbeRuns probeRuns;
  private final MediaFileRepository mediaFiles;

  public FileDiscovery open(String operation, Library library) {
    var artworkRun =
        artworkRequestService.openRun(
            operation + " library '" + library.getName() + "'", ImageRefreshMode.PRESERVE);
    return new FileDiscovery(library, artworkRun, probeRuns.open());
  }

  /**
   * Waits for the recorded primary artwork results and probe results of a closed discovery.
   * Background artwork and the retries of failed artwork steps and probes continue in the
   * background.
   *
   * @throws LibraryScanFailedException if the stored results cannot be read, the wait fails
   *     unexpectedly, or the wait is interrupted
   */
  public ScanResults awaitResults(FileDiscovery discovery) {
    var libraryName = discovery.library().getName();
    try {
      return awaitRequiredResults(discovery);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new LibraryScanFailedException(libraryName, exception);
    } catch (ExecutionException exception) {
      throw new LibraryScanFailedException(libraryName, exception.getCause());
    } catch (RuntimeException exception) {
      throw new LibraryScanFailedException(libraryName, exception);
    }
  }

  // Probes are checked while the artwork finishes, so each phase's timer stops with its own work.
  private ScanResults awaitRequiredResults(FileDiscovery discovery)
      throws InterruptedException, ExecutionException {
    try (var executor = SystemWork.newExecutorRunningAsSystemActor("library-scan-probes-")) {
      var probes = executor.submit(() -> probeRuns.awaitResults(discovery.probeRun()));
      try {
        var artwork = artworkRunWaiter.awaitResults(discovery.artworkRun());
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
