package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.ArtworkRun;
import lombok.NonNull;

/**
 * A library whose media files are being matched, with the runs that collect the required artwork
 * and probes of those files.
 */
public record FileDiscovery(
    @NonNull Library library, @NonNull ArtworkRun artworkRun, @NonNull ProbeRun probeRun)
    implements AutoCloseable {

  /** Declares that the discovery requests no more required artwork. */
  @Override
  public void close() {
    artworkRun.close();
  }
}
