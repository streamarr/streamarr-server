package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.task.RequestedProbeResult;
import com.streamarr.server.services.ArtworkRunSummary;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.NonNull;

/**
 * The required results of one scan, and the secondary work that continues after it. Secondary image
 * counts cover the whole server.
 */
@Builder
public record ScanResults(
    @NonNull Map<MediaFileStatus, Long> files,
    @NonNull ArtworkRunSummary artwork,
    @NonNull ProbeRunSummary probes,
    int secondaryImagesPending) {

  public ScanResults {
    files = Map.copyOf(files);
  }

  public String describe() {
    return String.join(
        "; ",
        describeFiles(),
        "required artwork in %s seconds (%s)"
            .formatted(seconds(artwork.elapsed()), artwork.counts().describe()),
        "probes in %s seconds (%s)".formatted(seconds(probes.elapsed()), describeProbes()),
        "in the background: %d secondary images pending, %d failed probes retrying, %d changed files to probe"
            .formatted(
                secondaryImagesPending,
                probes.count(RequestedProbeResult.FAILED),
                probes.count(RequestedProbeResult.SUPERSEDED)));
  }

  private String describeFiles() {
    var total = files.values().stream().mapToLong(Long::longValue).sum();
    var statuses =
        files.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getValue() + " " + words(entry.getKey().name()))
            .collect(Collectors.joining(", "));
    return "%d files (%s)".formatted(total, statuses);
  }

  private String describeProbes() {
    return "%d ready, %d media errors, %d failed, %d superseded, %d removed"
        .formatted(
            probes.count(RequestedProbeResult.READY),
            probes.count(RequestedProbeResult.MEDIA_ERROR),
            probes.count(RequestedProbeResult.FAILED),
            probes.count(RequestedProbeResult.SUPERSEDED),
            probes.count(RequestedProbeResult.REMOVED));
  }

  private static String words(String constant) {
    return constant.toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }
}
