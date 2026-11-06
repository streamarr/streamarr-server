package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TimelineReport;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
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
 * job attempt on it, of any stream session, when the media that its variant has stored after its
 * viewer's media segment outlasts the hold limit, the job attempt's next segment and {@code
 * streaming.seek-priority-position-margin}, and the job attempt started within that media. The
 * position comes from the viewer's latest timeline report while that report is fresh and follows
 * the stream session's latest seek, and otherwise from the highest media segment that the player
 * has requested since that seek. No job attempt that a player's segment request waits on yields.
 */
@Builder
public class SeekPriority {

  // Clients report their timeline every 10 s of playback; a report older than three intervals is
  // too sparse to place the viewer.
  private static final Duration FRESH_REPORT = Duration.ofSeconds(30);

  @NonNull private final TranscodeExecutor transcodeExecutor;
  @NonNull private final SegmentStore segmentStore;
  @NonNull private final RuntimeStreamSessionRegistry runtimeRegistry;
  @NonNull private final HeldSegmentRequests heldRequests;
  @NonNull private final StreamingProperties properties;
  @Builder.Default private final Clock clock = Clock.systemUTC();

  // Guarded by this monitor.
  private final Map<VariantKey, PendingSeek> pendingSeeks = new HashMap<>();
  private final Map<UUID, Integer> highestRequestedSegments = new HashMap<>();
  private final Map<UUID, Instant> latestSeeks = new HashMap<>();
  private final Map<ExecutionTargetId, Set<UUID>> yielding = new HashMap<>();

  private record VariantKey(UUID sessionId, String variantLabel) {}

  private record PendingSeek(int segmentIndex, ExecutionTargetId target) {}

  /**
   * A relocation started the variant's job attempt at the seek's first segment. A seek that the
   * variant still waited on ends, and its worker resumes what only that seek needed.
   */
  public synchronized void seekStarted(
      @NonNull StreamSession session, @NonNull String variantLabel, int segmentIndex) {
    var sessionId = session.getSessionId();
    highestRequestedSegments.remove(sessionId);
    latestSeeks.put(sessionId, clock.instant());
    var key = new VariantKey(sessionId, variantLabel);
    var previous = Optional.ofNullable(pendingSeeks.remove(key)).map(PendingSeek::target);
    var target = runningTarget(session, variantLabel);
    target.ifPresent(running -> pendingSeeks.put(key, new PendingSeek(segmentIndex, running)));
    previous.filter(earlier -> !target.equals(Optional.of(earlier))).ifPresent(this::sendYields);
    if (target.isEmpty()) {
      return;
    }

    var named = yieldingAttempts(target.get());
    if (!named.isEmpty() || yielding.containsKey(target.get())) {
      send(target.get(), named);
    }
  }

  /**
   * A segment request of the variant waits for its segment, so the variant's job attempt stops
   * yielding at once.
   */
  public synchronized void segmentRequestWaits(
      @NonNull StreamSession session, @NonNull String variantLabel) {
    var attempt = session.getVariantHandle(variantLabel).map(TranscodeHandle::attemptId);
    var target = transcodeExecutor.runningExecutionTarget(session.getSessionId(), variantLabel);
    if (attempt.isEmpty()
        || target.isEmpty()
        || !yielding.getOrDefault(target.get(), Set.of()).contains(attempt.get())) {
      return;
    }

    sendYields(target.get());
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

  /**
   * The variant's job attempt was replaced, stopped or exhausted. A seek that waited on it waits on
   * the replacement, on whichever worker that runs, and ends when no job attempt runs.
   */
  public synchronized void variantChanged(
      @NonNull StreamSession session, @NonNull String variantLabel) {
    var key = new VariantKey(session.getSessionId(), variantLabel);
    var pending = pendingSeeks.remove(key);
    if (pending == null) {
      return;
    }

    var target = runningTarget(session, variantLabel);
    target.ifPresent(
        running -> pendingSeeks.put(key, new PendingSeek(pending.segmentIndex(), running)));
    sendYields(pending.target());
    target.filter(running -> !running.equals(pending.target())).ifPresent(this::sendYields);
  }

  private Optional<ExecutionTargetId> runningTarget(StreamSession session, String variantLabel) {
    var active =
        session
            .getVariantHandle(variantLabel)
            .filter(handle -> handle.status() == TranscodeStatus.ACTIVE);
    if (active.isEmpty()) {
      return Optional.empty();
    }

    return transcodeExecutor.runningExecutionTarget(session.getSessionId(), variantLabel);
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
    if (session.getDelivery() != StreamDelivery.HLS) {
      return Stream.empty();
    }

    var viewerSegment = viewerSegment(session);
    if (viewerSegment.isEmpty()) {
      return Stream.empty();
    }

    var sessionId = session.getSessionId();
    return session.getVariantHandles().entrySet().stream()
        .filter(entry -> !waitedOn.contains(new VariantKey(sessionId, entry.getKey())))
        .filter(entry -> !heldRequests.holdsRequestsOn(sessionId, entry.getKey()))
        .filter(entry -> runsOn(session, entry.getKey(), target))
        .filter(
            entry ->
                leadsItsViewer(session, entry.getKey(), entry.getValue(), viewerSegment.getAsInt()))
        .map(entry -> entry.getValue().attemptId());
  }

  private boolean runsOn(StreamSession session, String variantLabel, ExecutionTargetId target) {
    return transcodeExecutor
        .runningExecutionTarget(session.getSessionId(), variantLabel)
        .filter(target::equals)
        .isPresent();
  }

  // The media that the variant has stored without a gap after the viewer's segment outlasts the
  // hold limit, the time to produce the job attempt's next segment at real time and the position
  // margin, and the job attempt started within that media, so its viewer plays through the longest
  // pause and the next segment.
  private boolean leadsItsViewer(
      StreamSession session, String variantLabel, TranscodeHandle handle, int viewerSegment) {
    var timeline = MediaSegmentTimelines.of(session, properties);
    var stored = Duration.ZERO;
    var next = viewerSegment + 1;
    while (timeline.advertises(next) && isStored(session, variantLabel, next)) {
      stored = stored.plus(timeline.mediaSegmentDuration(next));
      next++;
    }

    if (handle.startSequenceNumber() >= next) {
      return false;
    }

    var nextSegment =
        timeline.advertises(next) ? timeline.mediaSegmentDuration(next) : Duration.ZERO;
    var needed =
        properties
            .seekPriorityHoldLimit()
            .plus(nextSegment)
            .plus(properties.seekPriorityPositionMargin());
    return stored.compareTo(needed) > 0;
  }

  private boolean isStored(StreamSession session, String variantLabel, int index) {
    return segmentStore.segmentExists(
        session.getSessionId(),
        SegmentNames.storedSegmentName(variantLabel, SegmentNames.mediaSegmentName(index)));
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
    var report = session.getLatestTimelineReport();
    if (report.isEmpty() || !isFreshReportAfterTheLatestSeek(session, report.get())) {
      return Optional.empty();
    }

    var position = Duration.ofSeconds(report.get().positionSeconds());
    if (report.get().state() == PlaybackState.PLAYING) {
      return Optional.of(
          position.plus(Duration.between(report.get().reportedAt(), clock.instant())));
    }

    return Optional.of(position);
  }

  private boolean isFreshReportAfterTheLatestSeek(StreamSession session, TimelineReport report) {
    if (report.state() == PlaybackState.STOPPED) {
      return false;
    }

    var latestSeek = latestSeeks.get(session.getSessionId());
    if (latestSeek != null && !report.reportedAt().isAfter(latestSeek)) {
      return false;
    }

    return !report.reportedAt().plus(FRESH_REPORT).isBefore(clock.instant());
  }
}
