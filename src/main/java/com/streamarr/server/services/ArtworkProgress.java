package com.streamarr.server.services;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Logs server-wide artwork progress by priority. A priority's busy period starts with its first
 * request and ends at the first report after nothing is pending, so work that pauses and resumes
 * between reports stays in one period.
 */
@Slf4j
@Component
public class ArtworkProgress {

  private final Clock clock;
  private final Map<ArtworkPriority, PriorityProgress> progressByPriority =
      new EnumMap<>(ArtworkPriority.class);

  public ArtworkProgress(Clock clock) {
    this.clock = clock;
    Arrays.stream(ArtworkPriority.values())
        .forEach(priority -> progressByPriority.put(priority, new PriorityProgress(priority)));
  }

  /**
   * Logs and returns one report per busy priority, or nothing while idle. The scheduler ignores the
   * returned reports.
   */
  @Scheduled(
      initialDelayString = "${artwork.progress-log-interval:PT30S}",
      fixedDelayString = "${artwork.progress-log-interval:PT30S}")
  public List<ArtworkProgressReport> reportProgress() {
    var now = clock.instant();
    var reports =
        progressByPriority.values().stream()
            .map(progress -> progress.report(now))
            .flatMap(Optional::stream)
            .toList();
    reports.forEach(ArtworkProgress::log);
    return reports;
  }

  void requested(ArtworkPriority priority, int sourceImages) {
    if (progressByPriority.get(priority).request(sourceImages, clock.instant())) {
      log.info("Started fetching {} artwork across all libraries.", describe(priority));
    }
  }

  void finished(ArtworkPriority priority, int sourceImages, List<ArtworkResult> results) {
    progressByPriority.get(priority).finish(sourceImages, results, clock.instant());
  }

  void runCompleted(ArtworkRunSummary summary) {
    if (summary.counts().equals(ArtworkCounts.NONE)) {
      return;
    }

    log.info(
        "Finished required artwork for {} in {} seconds: {}.",
        summary.description(),
        seconds(summary.elapsed()),
        summary.counts().describe());
  }

  private static void log(ArtworkProgressReport report) {
    if (report.isFinished()) {
      log.info(
          "Finished {} artwork across all libraries in {} seconds: {}.",
          describe(report.priority()),
          seconds(report.elapsed()),
          report.counts().describe());
      return;
    }

    log.info(
        "Fetching {} artwork across all libraries for {} seconds: {}, {} pending.",
        describe(report.priority()),
        seconds(report.elapsed()),
        report.counts().describe(),
        report.pending());
  }

  private static String describe(ArtworkPriority priority) {
    return priority.name().toLowerCase();
  }

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }

  private static final class PriorityProgress {

    private final ArtworkPriority priority;
    private int pending;
    private ArtworkCounts counts = ArtworkCounts.NONE;
    private Instant startedAt;
    private Instant drainedAt;

    private PriorityProgress(ArtworkPriority priority) {
      this.priority = priority;
    }

    /** Returns whether this request started a new busy period. */
    private synchronized boolean request(int sourceImages, Instant now) {
      var starting = startedAt == null;
      if (starting) {
        startedAt = now;
        counts = ArtworkCounts.NONE;
      }

      pending += sourceImages;
      drainedAt = null;
      return starting;
    }

    private synchronized void finish(int sourceImages, List<ArtworkResult> results, Instant now) {
      pending -= sourceImages;
      counts = counts.plus(results);
      if (pending == 0) {
        drainedAt = now;
      }
    }

    private synchronized Optional<ArtworkProgressReport> report(Instant now) {
      if (startedAt == null) {
        return Optional.empty();
      }

      if (pending > 0) {
        return Optional.of(reportUntil(now));
      }

      var finished = reportUntil(drainedAt);
      startedAt = null;
      drainedAt = null;
      return Optional.of(finished);
    }

    private ArtworkProgressReport reportUntil(Instant end) {
      return ArtworkProgressReport.builder()
          .priority(priority)
          .counts(counts)
          .pending(pending)
          .elapsed(Duration.between(startedAt, end))
          .build();
    }
  }
}
