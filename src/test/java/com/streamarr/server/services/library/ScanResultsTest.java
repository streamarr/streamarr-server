package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.task.RequestedProbeResult;
import com.streamarr.server.services.ArtworkCounts;
import com.streamarr.server.services.ArtworkRunSummary;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Scan Results Tests")
class ScanResultsTest {

  @Test
  @DisplayName("Should name every count when each result has a distinct count")
  void shouldNameEveryCountWhenEachResultHasADistinctCount() {
    var results =
        ScanResults.builder()
            .files(Map.of(MediaFileStatus.UNMATCHED, 2L, MediaFileStatus.MATCHED, 3L))
            .artwork(
                artwork(
                    ArtworkCounts.builder().saved(4).skipped(5).unavailable(6).failed(7).build(),
                    Duration.ofMillis(2500)))
            .probes(
                probes(
                    Map.of(
                        RequestedProbeResult.READY, 8,
                        RequestedProbeResult.MEDIA_ERROR, 9,
                        RequestedProbeResult.FAILED, 10,
                        RequestedProbeResult.SUPERSEDED, 11,
                        RequestedProbeResult.PROBED_BY_NEWER_VERSION, 12,
                        RequestedProbeResult.REMOVED, 13),
                    Duration.ofMillis(1200)))
            .secondaryImagesPending(14)
            .build();

    assertThat(results.describe())
        .isEqualTo(
            "5 files (2 unmatched, 3 matched); required artwork in 2.5 seconds (4 saved, 5 skipped,"
                + " 6 unavailable, 7 failed); probes in 1.2 seconds (8 ready, 9 media errors, 10"
                + " failed, 11 superseded, 12 probed by a newer version, 13 removed); in the"
                + " background: 14 secondary images pending across all libraries, 10 failed probes"
                + " retrying, 11 changed files to probe");
  }

  @Test
  @DisplayName("Should use the singular when a count is one")
  void shouldUseTheSingularWhenACountIsOne() {
    var probeCounts = new EnumMap<RequestedProbeResult, Integer>(RequestedProbeResult.class);
    for (var result : RequestedProbeResult.values()) {
      probeCounts.put(result, 1);
    }

    var results =
        ScanResults.builder()
            .files(Map.of(MediaFileStatus.MATCHED, 1L))
            .artwork(
                artwork(
                    ArtworkCounts.builder().saved(1).skipped(1).unavailable(1).failed(1).build(),
                    Duration.ofSeconds(1)))
            .probes(probes(probeCounts, Duration.ofSeconds(1)))
            .secondaryImagesPending(1)
            .build();

    assertThat(results.describe())
        .isEqualTo(
            "1 file (1 matched); required artwork in 1.0 seconds (1 saved, 1 skipped, 1"
                + " unavailable, 1 failed); probes in 1.0 seconds (1 ready, 1 media error, 1"
                + " failed, 1 superseded, 1 probed by a newer version, 1 removed); in the"
                + " background: 1 secondary image pending across all libraries, 1 failed probe"
                + " retrying, 1 changed file to probe");
  }

  @Test
  @DisplayName("Should name no file statuses when the library has no files")
  void shouldNameNoFileStatusesWhenTheLibraryHasNoFiles() {
    var results =
        ScanResults.builder()
            .files(Map.of())
            .artwork(artwork(ArtworkCounts.builder().build(), Duration.ZERO))
            .probes(probes(Map.of(), Duration.ZERO))
            .secondaryImagesPending(0)
            .build();

    assertThat(results.describe())
        .isEqualTo(
            "0 files; required artwork in 0.0 seconds (0 saved, 0 skipped, 0 unavailable, 0"
                + " failed); probes in 0.0 seconds (0 ready, 0 media errors, 0 failed, 0"
                + " superseded, 0 probed by a newer version, 0 removed); in the background: 0"
                + " secondary images pending across all libraries, 0 failed probes retrying, 0"
                + " changed files to probe");
  }

  @ParameterizedTest
  @EnumSource(value = RequestedProbeResult.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDING")
  @DisplayName("Should name the probe count when only one finished result occurred")
  void shouldNameTheProbeCountWhenOnlyOneFinishedResultOccurred(RequestedProbeResult result) {
    var summary = probes(Map.of(result, 7), Duration.ZERO);

    assertThat(summary.describe()).containsPattern("\\b7\\b");
  }

  private static ArtworkRunSummary artwork(ArtworkCounts counts, Duration elapsed) {
    return ArtworkRunSummary.builder()
        .description("scan of Movies")
        .counts(counts)
        .elapsed(elapsed)
        .build();
  }

  private static ProbeRunSummary probes(
      Map<RequestedProbeResult, Integer> counts, Duration elapsed) {
    return ProbeRunSummary.builder().counts(counts).elapsed(elapsed).build();
  }
}
