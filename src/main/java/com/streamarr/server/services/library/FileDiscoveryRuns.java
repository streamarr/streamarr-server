package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.LibraryScanFailedException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.ArtworkService;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.util.concurrent.ExecutionException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Opens the runs that collect the required artwork and probes of one scan or file discovery, and
 * waits until each has a saved result.
 */
@Service
@Builder
@RequiredArgsConstructor
public class FileDiscoveryRuns {

  private final ArtworkService artworkService;
  private final ProbeRuns probeRuns;
  private final MediaFileRepository mediaFiles;

  public FileDiscovery open(String operation, Library library) {
    var artworkRun =
        artworkService.openRun(
            operation + " library '" + library.getName() + "'", ImageRefreshMode.PRESERVE);
    return new FileDiscovery(library, artworkRun, probeRuns.open());
  }

  /**
   * Waits for the required artwork and probes of a closed discovery. Secondary artwork and the
   * retries of failed probes continue in the background.
   *
   * @throws LibraryScanFailedException if a required result was not recorded, the stored results
   *     cannot be read, or the wait is interrupted
   */
  public ScanResults awaitResults(FileDiscovery discovery) {
    var libraryName = discovery.library().getName();
    try {
      var artwork = discovery.artworkRun().completion().get();
      var probes = probeRuns.awaitResults(discovery.probeRun());
      return ScanResults.builder()
          .files(mediaFiles.countStatuses(discovery.probeRun().requested().keySet()))
          .artwork(artwork)
          .probes(probes)
          .secondaryImagesPending(artworkService.pendingSecondaryImages())
          .build();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new LibraryScanFailedException(libraryName, exception);
    } catch (ExecutionException exception) {
      throw new LibraryScanFailedException(libraryName, exception.getCause());
    } catch (DataAccessException exception) {
      throw new LibraryScanFailedException(libraryName, exception);
    }
  }
}
