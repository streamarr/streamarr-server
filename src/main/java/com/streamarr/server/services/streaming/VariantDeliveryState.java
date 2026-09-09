package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;

/** Recovery progress and attempted targets, accessed only under the lifecycle's session mutex. */
final class VariantDeliveryState {
  private UUID trackedAttemptId;
  private int runStart;
  private int frontier;
  private Instant lastProgressAt = Instant.EPOCH;
  private final Set<ExecutionTargetId> attemptedSinceProgress = new LinkedHashSet<>();

  void syncProgress(TranscodeHandle handle, IntPredicate frontierSegmentExists, Instant now) {
    if (!handle.attemptId().equals(trackedAttemptId)) {
      attemptedSinceProgress.clear();
      trackAttempt(handle, now);
    }

    var advanced = false;
    // Scan the run's output even for init or first-segment requests, which can observe progress
    // beyond their own requested index.
    while (frontierSegmentExists.test(frontier)) {
      frontier++;
      advanced = true;
    }

    if (advanced) {
      lastProgressAt = now;
      attemptedSinceProgress.clear();
    }
  }

  boolean hasStalled(Duration stallThreshold, Duration startupThreshold, Instant now) {
    var budget = frontier > runStart ? stallThreshold : startupThreshold;
    return Duration.between(lastProgressAt, now).compareTo(budget) >= 0;
  }

  List<ExecutionTargetId> attemptedTargets() {
    return List.copyOf(attemptedSinceProgress);
  }

  Optional<ExecutionTargetId> nextTarget(Set<ExecutionTargetId> liveTargets) {
    return liveTargets.stream()
        .filter(candidate -> !attemptedSinceProgress.contains(candidate))
        .findFirst();
  }

  void recordReplacement(ExecutionTargetId target, TranscodeHandle handle, Instant now) {
    attemptedSinceProgress.add(target);
    trackAttempt(handle, now);
  }

  void recordRefusal(ExecutionTargetId target) {
    attemptedSinceProgress.add(target);
  }

  boolean resetForFreshTargets(
      Set<ExecutionTargetId> eligibleNow, TranscodeHandle handle, Instant now) {
    if (nextTarget(eligibleNow).isEmpty()) {
      return false;
    }

    attemptedSinceProgress.clear();
    trackAttempt(handle, now);
    return true;
  }

  private void trackAttempt(TranscodeHandle handle, Instant now) {
    trackedAttemptId = handle.attemptId();
    runStart = handle.startSequenceNumber();
    frontier = runStart;
    lastProgressAt = now;
  }
}
