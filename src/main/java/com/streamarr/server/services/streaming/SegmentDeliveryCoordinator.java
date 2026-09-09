package com.streamarr.server.services.streaming;

import com.streamarr.server.exceptions.TranscodeException;
import java.time.Duration;
import java.util.UUID;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/** Waits for advertised segment bytes while the lifecycle owner coordinates producer recovery. */
@Slf4j
@Builder
public class SegmentDeliveryCoordinator {

  private final SegmentStore segmentStore;
  private final ProducerLifecycleService producerLifecycle;

  @Builder.Default private final Duration pollInterval = Duration.ofMillis(100);

  public SegmentDelivery deliver(UUID sessionId, String variantLabel, String segmentName) {
    while (true) {
      var outcome = deliverOnce(sessionId, variantLabel, segmentName);
      if (outcome != null) {
        return outcome;
      }
      // Every non-terminal pass — waiting on a live producer, a superseded recovery attempt, or a
      // FAILED variant awaiting revival — re-observes at poll cadence, never in a hot loop. The
      // loop owns the one wait and the one interrupt check.
      if (!sleepOnePoll()) {
        return new SegmentDelivery.Cancelled();
      }
    }
  }

  /**
   * One delivery pass. Returns a terminal {@link SegmentDelivery} outcome, or {@code null} to mean
   * "re-observe after one poll interval".
   */
  private SegmentDelivery deliverOnce(UUID sessionId, String variantLabel, String segmentName) {
    if (matchesNoNamingScheme(segmentName)) {
      // Waiting on a name no run can produce would misread the frontier and stall-kill a healthy
      // producer; an unknown name is a 404, never a recovery trigger.
      log.debug(
          "Rejected segment request matching no naming scheme: session {} name {}",
          sessionId,
          segmentName);
      return new SegmentDelivery.SessionEnded();
    }

    var ready = tryRead(sessionId, segmentName);
    if (ready != null) {
      return ready;
    }

    var recovery = producerLifecycle.recover(sessionId, variantLabel, segmentName);
    if (recovery == ProducerLifecycleService.RecoveryResult.EXHAUSTED) {
      return exhaustedDelivery(sessionId, segmentName);
    }

    if (recovery == ProducerLifecycleService.RecoveryResult.SESSION_GONE) {
      return new SegmentDelivery.SessionEnded();
    }

    return null;
  }

  private SegmentDelivery exhaustedDelivery(UUID sessionId, String segmentName) {
    var lastPublication = tryRead(sessionId, segmentName);
    if (lastPublication != null) {
      return lastPublication;
    }

    return new SegmentDelivery.Unrecoverable();
  }

  private static boolean matchesNoNamingScheme(String segmentName) {
    return SegmentNames.indexOf(segmentName).isEmpty() && !SegmentNames.isInitSegment(segmentName);
  }

  /** Drops recovery bookkeeping after session destruction. */
  public void forgetSession(UUID sessionId) {
    producerLifecycle.forgetRecovery(sessionId);
  }

  private SegmentDelivery tryRead(UUID sessionId, String segmentName) {
    if (!segmentStore.segmentExists(sessionId, segmentName)) {
      return null;
    }

    try {
      return new SegmentDelivery.Ready(segmentStore.readSegment(sessionId, segmentName));
    } catch (TranscodeException e) {
      // A concurrent destroy can remove the segment between the existence check and the read. The
      // store reports that disappearance as TranscodeException, so re-observe the session state.
      log.debug(
          "Segment read raced a concurrent destroy: session {} name {}", sessionId, segmentName, e);
      return null;
    }
  }

  private boolean sleepOnePoll() {
    try {
      Thread.sleep(pollInterval.toMillis());
      return true;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
