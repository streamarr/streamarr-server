package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;

/**
 * Holds back the job attempts that run far ahead of their viewers (ADR 0044, hold-back). A job
 * attempt whose own media stored ahead of its viewer outlasts the high-water mark is held until
 * that media falls to the low-water mark. Each hold tells the worker to resume the job attempt by
 * the time a viewer playing at real time reaches the low-water mark, so a hold ends even when no
 * later command arrives; listing the job attempt again renews it. Seek priority hands each stream
 * session's leads to this class under its own monitor, which guards this class's state.
 */
final class HoldBack {

  // A release that comes this much earlier than the one the worker holds is sent at once; a later
  // one waits until the worker releases the job attempt and its next segment holds it again.
  private static final Duration RELEASE_TOLERANCE = Duration.ofSeconds(1);

  private final TranscodeExecutor transcodeExecutor;
  private final StreamingProperties properties;
  private final Clock clock;
  private final Map<ExecutionTargetId, Map<UUID, Hold>> holds = new HashMap<>();

  HoldBack(
      @NonNull TranscodeExecutor transcodeExecutor,
      @NonNull StreamingProperties properties,
      @NonNull Clock clock) {
    this.transcodeExecutor = transcodeExecutor;
    this.properties = properties;
    this.clock = clock;
  }

  /** The own media that a running job attempt has stored ahead of its viewer. */
  record AttemptLead(UUID attemptId, ExecutionTargetId target, Duration lead) {}

  private record Hold(UUID sessionId, Instant releaseAt) {}

  /**
   * Decides the holds of the stream session's job attempts from their leads. A job attempt that has
   * no lead here, because nothing places its viewer, a player waits on it or it stopped, is
   * released.
   */
  void reconsider(@NonNull UUID sessionId, @NonNull List<AttemptLead> leads) {
    var now = clock.instant();
    var targets = new HashSet<ExecutionTargetId>();
    holds.forEach(
        (target, held) -> {
          if (held.values().stream().anyMatch(hold -> hold.sessionId().equals(sessionId))) {
            targets.add(target);
          }
        });
    leads.forEach(lead -> targets.add(lead.target()));
    for (var target : targets) {
      reconsider(target, sessionId, leads, now);
    }
  }

  /** The stream session ended, so none of its job attempts stays held. */
  void forget(@NonNull UUID sessionId) {
    reconsider(sessionId, List.of());
  }

  private void reconsider(
      ExecutionTargetId target, UUID sessionId, List<AttemptLead> leads, Instant now) {
    var sent = liveHolds(target, now);
    var next = new HashMap<UUID, Hold>();
    sent.forEach(
        (attemptId, hold) -> {
          if (!hold.sessionId().equals(sessionId)) {
            next.put(attemptId, hold);
          }
        });
    leads.stream()
        .filter(lead -> lead.target().equals(target))
        .forEach(
            lead ->
                releaseAt(lead, Optional.ofNullable(sent.get(lead.attemptId())), now)
                    .ifPresent(at -> next.put(lead.attemptId(), new Hold(sessionId, at))));
    var changed = !next.equals(sent);
    keep(target, next);
    if (changed) {
      send(target, next, now);
    }
  }

  // The holds that the worker still keeps: each ends at its release time without a command.
  private Map<UUID, Hold> liveHolds(ExecutionTargetId target, Instant now) {
    return holds.getOrDefault(target, Map.of()).entrySet().stream()
        .filter(entry -> entry.getValue().releaseAt().isAfter(now))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  // A held job attempt stays held above the low-water mark, and another is held above the
  // high-water mark, until a viewer playing at real time reaches the low-water mark.
  private Optional<Instant> releaseAt(AttemptLead lead, Optional<Hold> sent, Instant now) {
    var mark = sent.isPresent() ? properties.holdBackLowWater() : properties.holdBackHighWater();
    if (lead.lead().compareTo(mark) <= 0) {
      return Optional.empty();
    }

    var releaseAt = now.plus(lead.lead().minus(properties.holdBackLowWater()));
    var keptRelease =
        sent.map(Hold::releaseAt).filter(kept -> releaseAt.plus(RELEASE_TOLERANCE).isAfter(kept));
    if (keptRelease.isPresent()) {
      return keptRelease;
    }

    return Optional.of(releaseAt);
  }

  private void keep(ExecutionTargetId target, Map<UUID, Hold> next) {
    if (next.isEmpty()) {
      holds.remove(target);
      return;
    }

    holds.put(target, Map.copyOf(next));
  }

  private void send(ExecutionTargetId target, Map<UUID, Hold> next, Instant now) {
    transcodeExecutor.holdBack(
        target,
        next.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> Duration.between(now, entry.getValue().releaseAt()))));
  }
}
