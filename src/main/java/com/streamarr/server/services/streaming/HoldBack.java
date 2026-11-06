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
import lombok.Builder;
import lombok.NonNull;

/**
 * Holds back the job attempts that run far ahead of their viewers (ADR 0044, hold-back). A job
 * attempt whose own media stored ahead of its viewer outlasts the high-water mark is held until
 * that media falls to the low-water mark. Each hold tells the worker to resume the job attempt by
 * the time a viewer playing at real time reaches the low-water mark, so a hold ends even when no
 * later command arrives. A later command moves a held job attempt's release only earlier, so a late
 * command never extends a hold; a job attempt that the worker released is held again at its next
 * stored segment, by a command that follows one without it. Seek priority hands each stream
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

  /**
   * The own media that a running job attempt has stored ahead of its viewer, and the duration of
   * the media segment that it produces next.
   */
  @Builder
  record AttemptLead(
      @NonNull UUID attemptId,
      @NonNull ExecutionTargetId target,
      @NonNull Duration lead,
      @NonNull Duration nextSegment) {}

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
    // A worker that received the command late still holds a job attempt past the release time the
    // server counts, so a change from the last command sent is sent, even one that only releases.
    var lastSent = holds.getOrDefault(target, Map.of());
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
    var changed = !next.equals(lastSent);
    keep(target, next);
    if (!changed) {
      return;
    }

    var withoutFreshHolds = withoutFreshHolds(next, sent, lastSent);
    if (withoutFreshHolds.size() < next.size()) {
      send(target, withoutFreshHolds, now);
    }

    send(target, next, now);
  }

  // A worker may still keep a hold that the server counts as past, and ignores a listing of a job
  // attempt whose hold ended at its release time until a command leaves it out. So a new hold of a
  // job attempt that the last command listed follows a command without it.
  private static Map<UUID, Hold> withoutFreshHolds(
      Map<UUID, Hold> next, Map<UUID, Hold> sent, Map<UUID, Hold> lastSent) {
    var without = new HashMap<>(next);
    without
        .keySet()
        .removeIf(attemptId -> !sent.containsKey(attemptId) && lastSent.containsKey(attemptId));
    return without;
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
    var lowWater = lowWater(lead);
    var mark = lowWater;
    if (sent.isEmpty()) {
      mark = lowWater.plus(properties.holdBackHighWater().minus(properties.holdBackLowWater()));
    }

    if (lead.lead().compareTo(mark) <= 0) {
      return Optional.empty();
    }

    var releaseAt = now.plus(lead.lead().minus(lowWater));
    var keptRelease =
        sent.map(Hold::releaseAt).filter(kept -> releaseAt.plus(RELEASE_TOLERANCE).isAfter(kept));
    if (keptRelease.isPresent()) {
      return keptRelease;
    }

    return Optional.of(releaseAt);
  }

  // A next segment longer than the target takes longer to produce at real time, so both marks rise
  // by the difference. That keeps the slack beyond what a resume needs, which absorbs a command's
  // delivery delay while a worker still holds the job attempt, the same for every segment length.
  private Duration lowWater(AttemptLead lead) {
    var longerThanTarget = lead.nextSegment().minus(properties.targetSegmentDuration());
    if (longerThanTarget.isPositive()) {
      return properties.holdBackLowWater().plus(longerThanTarget);
    }

    return properties.holdBackLowWater();
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
