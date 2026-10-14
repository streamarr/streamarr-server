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

  public String describe() {
    return String.join(
        ", ",
        count(RequestedProbeResult.READY) + " ready",
        SummaryText.counted(count(RequestedProbeResult.MEDIA_ERROR), "media error", "media errors"),
        count(RequestedProbeResult.FAILED) + " failed",
        count(RequestedProbeResult.SUPERSEDED) + " superseded",
        count(RequestedProbeResult.PROBED_BY_NEWER_VERSION) + " probed by a newer version",
        count(RequestedProbeResult.REMOVED) + " removed");
  }
}
