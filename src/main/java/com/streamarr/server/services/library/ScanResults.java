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
        "probes in %s seconds (%s)".formatted(seconds(probes.elapsed()), probes.describe()),
        "in the background: %s pending across all libraries, %s retrying, %s to probe"
            .formatted(
                SummaryText.counted(secondaryImagesPending, "secondary image", "secondary images"),
                SummaryText.counted(
                    probes.count(RequestedProbeResult.FAILED), "failed probe", "failed probes"),
                SummaryText.counted(
                    probes.count(RequestedProbeResult.SUPERSEDED),
                    "changed file",
                    "changed files")));
  }

  private String describeFiles() {
    var total = files.values().stream().mapToLong(Long::longValue).sum();
    var fileCount = SummaryText.counted(total, "file", "files");
    if (total == 0) {
      return fileCount;
    }

    var statuses =
        files.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getValue() + " " + words(entry.getKey().name()))
            .collect(Collectors.joining(", "));
    return "%s (%s)".formatted(fileCount, statuses);
  }

  private static String words(String constant) {
    return constant.toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }
}
