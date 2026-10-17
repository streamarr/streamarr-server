package com.streamarr.server.services.streaming;

@FunctionalInterface
public interface ProbeWorkers {

  /**
   * Whether a connected worker can read the media source namespace and probe at the given version,
   * so a probe request can start.
   */
  boolean hasCompatibleWorker(int probeVersion);
}
