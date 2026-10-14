package com.streamarr.server.services;

import com.streamarr.server.domain.media.ArtworkOutcomeTally;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
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
 * Logs server-wide artwork progress by priority from stored artwork requests and recorded artwork
 * results, so the report stays correct across restarts. A priority's busy period starts at the
 * first report that finds pending images and ends at the first report that finds none.
 */
@Slf4j
@Component
public class ArtworkProgress {

  private final ArtworkRequestRepository requests;
  private final ItemResultRepository itemResults;
  private final Clock clock;
  private final Map<ArtworkPriority, Instant> busySince = new EnumMap<>(ArtworkPriority.class);

  public ArtworkProgress(
      ArtworkRequestRepository requests, ItemResultRepository itemResults, Clock clock) {
    this.requests = requests;
    this.itemResults = itemResults;
    this.clock = clock;
  }

  /**
   * Logs and returns one report per busy priority, or nothing while idle. The scheduler ignores the
   * returned reports.
   */
  @Scheduled(
      initialDelayString = "${artwork.progress-log-interval:30s}",
      fixedDelayString = "${artwork.progress-log-interval:30s}")
  public synchronized List<ArtworkProgressReport> reportProgress() {
    var now = clock.instant();
    var pendingByPriority = new EnumMap<ArtworkPriority, Long>(ArtworkPriority.class);
    requests
        .countPendingImagesByItemType()
        .forEach(
            (itemType, images) ->
                pendingByPriority.merge(ArtworkPriority.of(itemType), images, Long::sum));
    var reports =
        Arrays.stream(ArtworkPriority.values())
            .map(priority -> report(priority, pendingByPriority.getOrDefault(priority, 0L), now))
            .flatMap(Optional::stream)
            .toList();
    reports.forEach(ArtworkProgress::log);
    return reports;
  }

  private Optional<ArtworkProgressReport> report(
      ArtworkPriority priority, long pending, Instant now) {
    var startedAt = busySince.get(priority);
    if (startedAt == null && pending == 0) {
      return Optional.empty();
    }

    if (startedAt == null) {
      startedAt = now;
      busySince.put(priority, startedAt);
      log.info("Started fetching {} artwork across all libraries.", describe(priority));
    }

    if (pending == 0) {
      busySince.remove(priority);
    }

    return Optional.of(
        ArtworkProgressReport.builder()
            .priority(priority)
            .counts(countsSince(priority, startedAt))
            .pending(pending)
            .elapsed(Duration.between(startedAt, now))
            .build());
  }

  private ArtworkCounts countsSince(ArtworkPriority priority, Instant since) {
    var counts = ArtworkCounts.NONE;
    for (var entry : itemResults.countArtworkOutcomesRecordedSince(since).entrySet()) {
      if (ArtworkPriority.of(entry.getKey()) == priority) {
        counts = plus(counts, entry.getValue());
      }
    }

    return counts;
  }

  private static ArtworkCounts plus(ArtworkCounts counts, ArtworkOutcomeTally tally) {
    return counts.toBuilder()
        .saved(counts.saved() + (int) tally.succeeded())
        .unavailable(counts.unavailable() + (int) tally.unavailable())
        .failed(counts.failed() + (int) tally.failed())
        .build();
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
}
