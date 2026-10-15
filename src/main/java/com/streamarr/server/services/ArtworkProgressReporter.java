package com.streamarr.server.services;

import com.streamarr.server.domain.media.ArtworkBacklog;
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
 * results, so the report stays correct across restarts. A priority's busy period starts when the
 * oldest of its pending requests was first made and ends at the first report that finds no pending
 * image. Counts cover the results recorded since the busy period started.
 */
@Slf4j
@Component
public class ArtworkProgressReporter {

  private final ArtworkRequestRepository requests;
  private final ItemResultRepository itemResults;
  private final Clock clock;
  private final Map<ArtworkPriority, Instant> busySince = new EnumMap<>(ArtworkPriority.class);

  public ArtworkProgressReporter(
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
    var backlogByPriority = new EnumMap<ArtworkPriority, ArtworkBacklog>(ArtworkPriority.class);
    requests
        .findArtworkBacklogByTargetType()
        .forEach(
            (targetType, backlog) ->
                backlogByPriority.merge(
                    ArtworkPriority.of(targetType), backlog, ArtworkBacklog::plus));
    var reports =
        Arrays.stream(ArtworkPriority.values())
            .map(
                priority ->
                    report(priority, Optional.ofNullable(backlogByPriority.get(priority)), now))
            .flatMap(Optional::stream)
            .toList();
    reports.forEach(ArtworkProgressReporter::log);
    return reports;
  }

  private Optional<ArtworkProgressReport> report(
      ArtworkPriority priority, Optional<ArtworkBacklog> backlog, Instant now) {
    var pendingImages = backlog.map(ArtworkBacklog::pendingImages).orElse(0L);
    var startedAt = busySince.get(priority);
    if (startedAt == null && pendingImages == 0) {
      return Optional.empty();
    }

    if (pendingImages > 0) {
      startedAt = startOfBusyPeriod(priority, backlog.get());
    }

    if (pendingImages == 0) {
      busySince.remove(priority);
    }

    return Optional.of(
        ArtworkProgressReport.builder()
            .priority(priority)
            .counts(countsSince(priority, startedAt))
            .pending(pendingImages)
            .elapsed(Duration.between(startedAt, now))
            .build());
  }

  // The busy period starts at the oldest pending request's stored request time, so a reporter
  // that starts after a restart counts the results recorded since that request too.
  private Instant startOfBusyPeriod(ArtworkPriority priority, ArtworkBacklog backlog) {
    var startedAt = busySince.get(priority);
    if (startedAt != null) {
      return startedAt;
    }

    log.info("Started fetching {} artwork across all libraries.", describe(priority));
    busySince.put(priority, backlog.oldestRequestAt());
    return backlog.oldestRequestAt();
  }

  private FinishedImageCounts countsSince(ArtworkPriority priority, Instant since) {
    var counts = FinishedImageCounts.NONE;
    for (var entry : itemResults.countArtworkOutcomesRecordedSince(since).entrySet()) {
      if (ArtworkPriority.of(entry.getKey()) == priority) {
        counts = counts.plus(entry.getValue());
      }
    }

    return counts;
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
