package com.streamarr.server.services.library;

import com.streamarr.server.domain.task.RequestedProbeResult;
import java.time.Duration;
import java.util.Map;
import lombok.Builder;
import lombok.NonNull;

/**
 * How the probes of one run ended. {@code elapsed} runs from the first request until the check that
 * found the last result.
 */
@Builder
public record ProbeRunSummary(
    @NonNull Map<RequestedProbeResult, Integer> counts, @NonNull Duration elapsed) {

  public ProbeRunSummary {
    counts = Map.copyOf(counts);
  }

  public int count(RequestedProbeResult result) {
    return counts.getOrDefault(result, 0);
  }

  public int total() {
    return counts.values().stream().mapToInt(Integer::intValue).sum();
  }
}
