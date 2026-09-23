package com.streamarr.server.services.library;

import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.domain.task.RequestedProbe;
import com.streamarr.server.domain.task.RequestedProbeResult;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Opens probe runs and waits until each probe a run requested has a stored outcome or a recorded
 * failure. Probes run in db-scheduler, so the wait reads their stored results rather than waiting
 * for an in-process signal. A probe that waits for a busy worker keeps the run waiting.
 */
@Service
@Builder
@RequiredArgsConstructor
public class ProbeRuns {

  private final MediaFileProbeTaskScheduler scheduler;
  private final MediaFileContainerInfoRepository outcomes;
  private final Sleeper sleeper;
  private final Clock clock;
  private final ProbeSchedulingProperties properties;

  public ProbeRun open() {
    return new ProbeRun(scheduler, clock);
  }

  /**
   * Waits until no probe of the run is pending. A file whose later change replaced the requested
   * inputs, or that was removed, no longer belongs to the run.
   */
  public ProbeRunSummary awaitResults(ProbeRun run) throws InterruptedException {
    var pending = new HashMap<>(run.requested());
    var counts = new EnumMap<RequestedProbeResult, Integer>(RequestedProbeResult.class);
    while (true) {
      finishedProbes(pending)
          .forEach(
              (mediaFileId, result) -> {
                pending.remove(mediaFileId);
                counts.merge(result, 1, Integer::sum);
              });
      if (pending.isEmpty()) {
        break;
      }

      sleeper.sleep(properties.resultCheckInterval());
    }

    var elapsed =
        run.firstRequestedAt()
            .map(requestedAt -> Duration.between(requestedAt, clock.instant()))
            .orElse(Duration.ZERO);
    return ProbeRunSummary.builder().counts(counts).elapsed(elapsed).build();
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
