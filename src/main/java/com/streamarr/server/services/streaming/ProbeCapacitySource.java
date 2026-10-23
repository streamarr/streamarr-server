package com.streamarr.server.services.streaming;

/** Reads the probe capacity of the connected workers. */
@FunctionalInterface
public interface ProbeCapacitySource {

  /** An advertised capacity of 0 means that no connected worker can run the server's probes. */
  ProbeCapacity probeCapacity();
}
