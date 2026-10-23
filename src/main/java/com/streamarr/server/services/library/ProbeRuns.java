package com.streamarr.server.services.library;

import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.domain.task.RequestedProbe;
import com.streamarr.server.domain.task.RequestedProbeResult;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.concurrency.Sleeper;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Opens probe runs and waits until each probe a run requested has a stored outcome or a recorded
 * failure. Probes run in db-scheduler, so the wait reads their stored results rather than waiting
 * for an in-process signal. A probe that waits for a busy worker keeps the run waiting.
 */
@Service
@Builder
@RequiredArgsConstructor
@Slf4j
public class ProbeRuns {

  private static final Duration PROGRESS_REPORT_INTERVAL = Duration.ofSeconds(30);

  private final MediaFileProbeTaskScheduler scheduler;
  private final MediaFileContainerInfoRepository outcomes;
  private final Sleeper sleeper;
  private final Clock clock;
  private final ProbeSchedulingProperties properties;
  private final ProbeCapacitySource capacitySource;
  private final ScanProbesWaiting scanProbesWaiting;

  public ProbeRun open() {
    return new ProbeRun(scheduler, clock);
  }

  /**
   * Waits until no probe of the run is pending, or until the admission end is reached. A file whose
   * later change replaced the requested inputs, or that was removed, no longer belongs to the run.
   * The summary counts the probes that finished before the wait ended. While it waits, the progress
   * of the run, named by {@code description}, is logged at a fixed interval.
   */
  public ProbeRunSummary awaitResults(ProbeRun run, String description, AdmissionEnd admissionEnd)
      throws InterruptedException {
    try {
      return awaitPendingResults(run, description, admissionEnd);
    } finally {
      scanProbesWaiting.ended(run);
    }
  }

  private ProbeRunSummary awaitPendingResults(
      ProbeRun run, String description, AdmissionEnd admissionEnd) throws InterruptedException {
    var requested = run.requested();
    var pending = new HashMap<>(requested);
    var counts = new EnumMap<RequestedProbeResult, Integer>(RequestedProbeResult.class);
    var progressReports = new ProgressReportInterval(PROGRESS_REPORT_INTERVAL, clock.instant());
    while (true) {
      finishedProbes(pending)
          .forEach(
              (mediaFileId, result) -> {
                pending.remove(mediaFileId);
                counts.merge(result, 1, Integer::sum);
              });
      scanProbesWaiting.pending(run, pending.size());
      if (pending.isEmpty() || admissionEnd.isReached()) {
        break;
      }

      if (progressReports.tryTakeReport(clock.instant())) {
        logProgress(
            description,
            ProbeRunProgress.builder()
                .finished(requested.size() - pending.size())
                .pending(pending.size())
                .elapsed(elapsed(run))
                .capacity(capacitySource.probeCapacity())
                .build());
      }

      sleeper.sleep(properties.resultCheckInterval());
    }

    return ProbeRunSummary.builder().counts(counts).elapsed(elapsed(run)).build();
  }

  private Duration elapsed(ProbeRun run) {
    return run.firstRequestedAt()
        .map(requestedAt -> Duration.between(requestedAt, clock.instant()))
        .orElse(Duration.ZERO);
  }

  private static void logProgress(String description, ProbeRunProgress progress) {
    var capacity = progress.capacity();
    // A scan's probes are background probes, so a scan waits while no worker grants a unit.
    if (capacity.backgroundProbeCapacity() == 0) {
      log.warn(
          "Probing for {}: {} of {} saved, {} waiting for a connected worker that can run probes",
          description,
          progress.finished(),
          progress.finished() + progress.pending(),
          progress.pending());
      return;
    }

    log.info(
        "Probing for {}: {} of {} saved, {} waiting, {} files per second, probe capacities of the"
            + " connected workers: {} playback, {} background, {} keyframe packet scans",
        description,
        progress.finished(),
        progress.finished() + progress.pending(),
        progress.pending(),
        String.format(Locale.ROOT, "%.1f", progress.filesPerSecond()),
        capacity.playbackProbeCapacity(),
        capacity.backgroundProbeCapacity(),
        capacity.keyframePacketScanCapacity());
  }

  private Map<UUID, RequestedProbeResult> finishedProbes(Map<UUID, RequestedProbe> pending) {
    var states =
        outcomes.findProbeStates(pending.keySet()).stream()
            .collect(Collectors.toMap(ProbeState::mediaFileId, Function.identity()));
    var finished = new HashMap<UUID, RequestedProbeResult>();
    pending.forEach(
        (mediaFileId, probe) -> {
          var result = resultOf(states.get(mediaFileId), probe);
          if (result != RequestedProbeResult.PENDING) {
            finished.put(mediaFileId, result);
          }
        });
    return finished;
  }

  // A media file without a state row was deleted.
  private static RequestedProbeResult resultOf(ProbeState state, RequestedProbe probe) {
    if (state == null) {
      return RequestedProbeResult.REMOVED;
    }

    return state.resultFor(probe);
  }
}
