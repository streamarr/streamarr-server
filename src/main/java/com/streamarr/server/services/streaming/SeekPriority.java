package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.PlaybackSnapshot;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;

/**
 * Names the job attempts that give up CPU to a seek (ADR 0044, seek priority). A seek's first
 * segment is pending from the relocation that starts the seek's job attempt until the server
 * delivers that segment. Meanwhile the worker that runs the seek's job attempt pauses every other
 * job attempt on it, of any stream session, that runs more than {@code
 * streaming.seek-priority-lead-segments} media segments ahead of its viewer. A job attempt's lead
 * is the last media segment that it has delivered without a gap, minus the media segment at its
 * viewer's position. The position comes from the viewer's latest timeline report while that report
 * is fresh and follows the stream session's latest seek, and otherwise from the highest media
 * segment that the player has requested since that seek. No job attempt that a player waits on
 * yields.
 */
@Builder
public class SeekPriority {

  // Clients report their timeline every 10 s of playback; a report older than three intervals is
  // too sparse to place the viewer.
  private static final Duration FRESH_REPORT = Duration.ofSeconds(30);

  @NonNull private final TranscodeExecutor transcodeExecutor;
  @NonNull private final SegmentStore segmentStore;
  @NonNull private final RuntimeStreamSessionRegistry runtimeRegistry;
  @NonNull private final StreamingProperties properties;
  @Builder.Default private final Clock clock = Clock.systemUTC();

  // Guarded by this monitor.
  private final Map<VariantKey, PendingSeek> pendingSeeks = new HashMap<>();
  private final Map<UUID, Integer> highestRequestedSegments = new HashMap<>();
  private final Map<UUID, Instant> latestSeeks = new HashMap<>();
  private final Map<ExecutionTargetId, Set<UUID>> yielding = new HashMap<>();

  private record VariantKey(UUID sessionId, String variantLabel) {}

  private record PendingSeek(UUID attemptId, int segmentIndex, ExecutionTargetId target) {}

  /** A relocation started the variant's job attempt at the seek's first segment. */
  public synchronized void seekStarted(
      @NonNull StreamSession session, @NonNull String variantLabel, int segmentIndex) {
    var sessionId = session.getSessionId();
    highestRequestedSegments.remove(sessionId);
    latestSeeks.put(sessionId, clock.instant());
    var handle = session.getVariantHandle(variantLabel);
    var target = transcodeExecutor.runningExecutionTarget(sessionId, variantLabel);
    if (handle.isEmpty() || target.isEmpty()) {
      return;
    }

    pendingSeeks.put(
        new VariantKey(sessionId, variantLabel),
        new PendingSeek(handle.get().attemptId(), segmentIndex, target.get()));
    var named = yieldingAttempts(target.get());
    if (!named.isEmpty() || yielding.containsKey(target.get())) {
      send(target.get(), named);
    }
  }

  /** The player requested the media segment, which counts toward its position. */
  public synchronized void mediaSegmentRequested(@NonNull UUID sessionId, int segmentIndex) {
    highestRequestedSegments.merge(sessionId, segmentIndex, Math::max);
  }

  /** The server delivered the segment to a player, which ends a seek that waited for it. */
  public synchronized void segmentDelivered(
      @NonNull UUID sessionId, @NonNull String variantLabel, @NonNull String segmentName) {
    var key = new VariantKey(sessionId, variantLabel);
    var pending = pendingSeeks.get(key);
    var index = SegmentNames.indexOf(segmentName);
    if (pending == null || index.isEmpty() || index.getAsInt() < pending.segmentIndex()) {
      return;
    }

    pendingSeeks.remove(key);
    sendYields(pending.target());
  }

  /** The stream session ended, so its seeks wait for nothing. */
  public synchronized void forgetSession(@NonNull UUID sessionId) {
    highestRequestedSegments.remove(sessionId);
    latestSeeks.remove(sessionId);
    var ended =
        pendingSeeks.entrySet().stream()
            .filter(entry -> entry.getKey().sessionId().equals(sessionId))
            .toList();
    ended.forEach(entry -> pendingSeeks.remove(entry.getKey()));
    ended.stream().map(entry -> entry.getValue().target()).distinct().forEach(this::sendYields);
  }

  // Guarded by this monitor. Sends only a change, because the hold limit still runs on the job
  // attempts that the last yield named.
  private void sendYields(ExecutionTargetId target) {
    var named = yieldingAttempts(target);
    if (!named.equals(yielding.getOrDefault(target, Set.of()))) {
      send(target, named);
    }
  }

  // Guarded by this monitor. Each new seek restarts the hold limit of the job attempts it names.
  private void send(ExecutionTargetId target, Set<UUID> named) {
    if (named.isEmpty()) {
      yielding.remove(target);
    } else {
      yielding.put(target, named);
    }

    transcodeExecutor.yieldToSeek(target, named, properties.seekPriorityHoldLimit());
  }

  // Guarded by this monitor.
  private Set<UUID> yieldingAttempts(ExecutionTargetId target) {
    var waitedOn =
        pendingSeeks.entrySet().stream()
            .filter(entry -> entry.getValue().target().equals(target))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    if (waitedOn.isEmpty()) {
      return Set.of();
    }

    var named = new HashSet<UUID>();
    for (var session : runtimeRegistry.findAll()) {
      attemptsAhead(session, target, waitedOn).forEach(named::add);
    }

    return Set.copyOf(named);
  }

  private Stream<UUID> attemptsAhead(
      StreamSession session, ExecutionTargetId target, Set<VariantKey> waitedOn) {
    var viewerSegment = viewerSegment(session);
    if (viewerSegment.isEmpty()) {
      return Stream.empty();
    }

    return session.getVariantHandles().entrySet().stream()
        .filter(entry -> !waitedOn.contains(new VariantKey(session.getSessionId(), entry.getKey())))
        .filter(entry -> runsOn(session, entry.getKey(), target))
        .filter(
            entry ->
                lead(session, entry.getKey(), entry.getValue(), viewerSegment.getAsInt())
                    > properties.seekPriorityLeadSegments())
        .map(entry -> entry.getValue().attemptId());
  }

  private boolean runsOn(StreamSession session, String variantLabel, ExecutionTargetId target) {
    return transcodeExecutor
        .runningExecutionTarget(session.getSessionId(), variantLabel)
        .filter(target::equals)
        .isPresent();
  }

  // The last media segment that the job attempt delivered without a gap from its first one,
  // counted from the viewer's segment; negative before its first segment.
  private int lead(
      StreamSession session, String variantLabel, TranscodeHandle handle, int viewerSegment) {
    var timeline = MediaSegmentTimelines.of(session, properties);
    var next = handle.startSequenceNumber();
    while (timeline.advertises(next)
        && segmentStore.segmentExists(
            session.getSessionId(),
            SegmentNames.storedSegmentName(variantLabel, SegmentNames.mediaSegmentName(next)))) {
      next++;
    }

    return next - 1 - viewerSegment;
  }

  private OptionalInt viewerSegment(StreamSession session) {
    var reported = reportedPosition(session);
    if (reported.isPresent()) {
      return OptionalInt.of(
          MediaSegmentTimelines.of(session, properties).mediaSegmentIndexAt(reported.get()));
    }

    var requested = highestRequestedSegments.get(session.getSessionId());
    return requested == null ? OptionalInt.empty() : OptionalInt.of(requested);
  }

  // A playing viewer's position advances with the clock from its report.
  private Optional<Duration> reportedPosition(StreamSession session) {
    var snapshot = session.getPlaybackSnapshot();
    if (!isFreshReportAfterTheLatestSeek(session, snapshot)) {
      return Optional.empty();
    }

    var position = Duration.ofSeconds(snapshot.positionSeconds());
    if (snapshot.state() == PlaybackState.PLAYING) {
      return Optional.of(position.plus(Duration.between(snapshot.accessedAt(), clock.instant())));
    }

    return Optional.of(position);
  }

  private boolean isFreshReportAfterTheLatestSeek(
      StreamSession session, PlaybackSnapshot snapshot) {
    if (snapshot.state() == PlaybackState.STOPPED) {
      return false;
    }

    var latestSeek = latestSeeks.get(session.getSessionId());
    if (latestSeek != null && !snapshot.accessedAt().isAfter(latestSeek)) {
      return false;
    }

    return !snapshot.accessedAt().plus(FRESH_REPORT).isBefore(clock.instant());
  }
}
