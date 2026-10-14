package com.streamarr.server.repositories.media;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface ProbeVersionRescanRepository {

  /**
   * Returns each library that holds a media file whose latest probe inputs, requested or stored,
   * come from a version before {@code probeVersion}. A file with neither does not count.
   */
  List<UUID> findLibraryIdsProbedBefore(int probeVersion);
}
