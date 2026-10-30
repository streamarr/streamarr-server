package com.streamarr.server.services.streaming;

import com.streamarr.server.exceptions.TranscodeException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/** Waits for advertised segment bytes while the lifecycle owner coordinates producer recovery. */
@Slf4j
@Builder
public class SegmentDeliveryCoordinator {

  private final SegmentStore segmentStore;
  private final ProducerLifecycleService producerLifecycle;
  private final HeldSegmentRequests heldRequests;

  /**
   * Answers one segment request. Before the wait, the request applies the on-demand rules of ADR
   * 0019 once: a media-segment request stops the other video variants, and a request for a variant
   * without a job attempt starts that variant. Later passes never start the variant again, so a
   * held request whose variant another request stopped ends instead of restarting it. A held
   * request also ends instead of moving its variant once a newer request has started the variant's
   * job attempt.
   */
  public SegmentDelivery deliver(UUID sessionId, String variantLabel, String segmentName) {
    var refusal = refusal(sessionId, segmentName);
    if (refusal.isPresent()) {
      return refusal.get();
    }

    var request = producerLifecycle.prepareRequestedVariant(sessionId, variantLabel, segmentName);
    while (true) {
      try (var wake = heldRequests.nextWake(sessionId, variantLabel)) {
        var outcome = deliverOnce(request);
        if (outcome != null) {
          return outcome;
        }

        // A stored segment, an ended job attempt or a destroyed session fires the wake. Only a
        // stall has no event, so the wait ends at the stall deadline that recovery measures
        // against.
        if (!tryAwait(wake, producerLifecycle.timeUntilStall(sessionId, variantLabel))) {
          return new SegmentDelivery.Cancelled();
        }
      }
    }
  }

  private Optional<SegmentDelivery> refusal(UUID sessionId, String segmentName) {
    if (!SegmentNames.matchesNamingScheme(segmentName)) {
      // Waiting on a name no run can produce would misread the frontier and stall-kill a healthy
      // producer; an unknown name is a 404, never a recovery trigger.
      log.debug(
          "Rejected segment request matching no naming scheme: session {} name {}",
          sessionId,
          segmentName);
      return Optional.of(new SegmentDelivery.SessionEnded());
    }

    // Media that outlasts its probed duration can leave segments past the advertised count in the
    // store; the playlist never lists them, so they are not found rather than served.
    if (producerLifecycle.isUnadvertisedMediaSegment(sessionId, segmentName)) {
      return Optional.of(new SegmentDelivery.SessionEnded());
    }

    return Optional.empty();
  }

  /**
   * One delivery pass. Returns a terminal {@link SegmentDelivery} outcome, or {@code null} to mean
   * "re-observe after the next wake".
   */
  private SegmentDelivery deliverOnce(SegmentRequest request) {
    var ready = tryRead(request.sessionId(), request.segmentName());
    if (ready != null) {
      return ready;
    }

    return switch (producerLifecycle.recover(request)) {
      case EXHAUSTED, VARIANT_NOT_RUNNING, SUPERSEDED ->
          exhaustedDelivery(request.sessionId(), request.segmentName());
      case SESSION_GONE, SEGMENT_NOT_ADVERTISED -> new SegmentDelivery.SessionEnded();
      case WAITING -> null;
    };
  }

  private SegmentDelivery exhaustedDelivery(UUID sessionId, String segmentName) {
    var lastPublication = tryRead(sessionId, segmentName);
    if (lastPublication != null) {
      return lastPublication;
    }

    return new SegmentDelivery.Unrecoverable();
  }

  /** Drops recovery state after session destruction and wakes the session's held requests. */
  public void forgetSession(UUID sessionId) {
    producerLifecycle.forgetRecovery(sessionId);
    heldRequests.wakeSession(sessionId);
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

  private static boolean tryAwait(HeldSegmentRequests.Wake wake, Duration timeout) {
    try {
      wake.await(timeout);
      return true;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
