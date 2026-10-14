package com.streamarr.server.repositories.media;

import java.time.Instant;

public interface ProbeVersionRescanRepository {

  /**
   * Records that the server queued the re-scan for this probe version. Returns {@code false}
   * without writing when a server already queued it.
   */
  boolean tryMarkQueued(int probeVersion, Instant queuedOn);
}
