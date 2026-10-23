package com.streamarr.server.services.library;

import com.streamarr.server.services.streaming.ProbeCapacity;
import java.time.Duration;
import lombok.Builder;
import lombok.NonNull;

/**
 * How far a probe run has come while a scan waits for it.
 *
 * @param finished the requested probes with a saved outcome or failure
 * @param pending the requested probes still waiting for a result
 * @param elapsed the time since the run requested its first probe
 * @param capacity the probe capacities of the connected workers
 */
@Builder
record ProbeRunProgress(
    int finished, int pending, @NonNull Duration elapsed, @NonNull ProbeCapacity capacity) {

  double filesPerSecond() {
    if (elapsed.isZero()) {
      return 0;
    }

    return finished * 1000.0 / elapsed.toMillis();
  }
}
