package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TimelineReport;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.services.streaming.events.MediaSegmentPublishedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
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
 * The same leads decide which job attempts {@link HoldBack} holds back because they run far ahead
 * of their viewers.
 */
public class SeekPriority {

  // Clients report their timeline every 10 s of playback; a report older than three intervals is
  // too sparse to place the viewer.
  private static final Duration FRESH_REPORT = Duration.ofSeconds(30);

  private final TranscodeExecutor transcodeExecutor;
  private final SegmentStore segmentStore;
  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final HeldSegmentRequests heldRequests;
  private final StreamingProperties properties;
  private final Clock clock;
  // Guarded by this monitor.
  private final HoldBack holdBack;

  // Guarded by this monitor.
  private final Map<VariantKey, PendingSeek> pendingSeeks = new HashMap<>();
  private final Map<UUID, Integer> highestRequestedSegments = new HashMap<>();
  private final Map<UUID, Long> newestArrivals = new HashMap<>();
  private final Map<UUID, Integer> newestRequestedSegments = new HashMap<>();
  private final Map<UUID, Instant> latestSeeks = new HashMap<>();
  private final Map<ExecutionTargetId, Set<UUID>> yielding = new HashMap<>();
  private final Map<VariantKey, OwnOutput> ownOutputs = new HashMap<>();

  @Builder
  private SeekPriority(
      @NonNull TranscodeExecutor transcodeExecutor,
      @NonNull SegmentStore segmentStore,
      @NonNull RuntimeStreamSessionRegistry runtimeRegistry,
      @NonNull HeldSegmentRequests heldRequests,
      @NonNull StreamingProperties properties,
      Clock clock) {
    this.transcodeExecutor = transcodeExecutor;
    this.segmentStore = segmentStore;
    this.runtimeRegistry = runtimeRegistry;
    this.heldRequests = heldRequests;
    this.properties = properties;
    var chosenClock = clock;
    if (chosenClock == null) {
      chosenClock = Clock.systemUTC();
    }

    this.clock = chosenClock;
    holdBack = new HoldBack(transcodeExecutor, properties, chosenClock);
  }

  private record VariantKey(UUID sessionId, String variantLabel) {}

  // A job attempt's own output: the media segments that its uploads stored, in order from its start
  // through storedThrough, short of nextStored. From nextStored on lies media that it found stored
  // past a gap when it started, which it reaches only by reproducing that media too. An upload of
  // another job attempt, even one that a disconnected worker finishes late, credits this one
  // nothing. The execution target is where its uploads came from, known once it stored a segment.
  private record OwnOutput(
      UUID attemptId, int storedThrough, int nextStored, Optional<ExecutionTargetId> uploadedFrom) {

    private boolean includes(int index) {
      return index <= storedThrough && index < nextStored;
    }

    private OwnOutput storedBy(MediaSegmentPublishedEvent published) {
      if (!published.jobAttemptId().equals(attemptId)) {
        return this;
      }

      return new OwnOutput(
          attemptId,
          Math.max(storedThrough, published.segmentIndex()),
          nextStored,
          Optional.of(published.executionTarget()));
    }
  }

  // A request that arrived up to arrivalFloor came before the seek, so only the seek's own first
  // segment ends the seek when such a request delivers it. The target is the worker whose pauses
  // the seek holds: empty before the seek's first job attempt runs, and the earlier seek's worker
  // until the new job attempt runs. Announced once the seek's job attempt runs on the target.
  private record PendingSeek(
      int segmentIndex, long arrivalFloor, Optional<ExecutionTargetId> target, boolean announced) {

    private PendingSeek announcedOn(ExecutionTargetId running) {
      return new PendingSeek(segmentIndex, arrivalFloor, Optional.of(running), true);
    }

    private boolean endsWith(int deliveredIndex, long arrival) {
      return deliveredIndex == segmentIndex
          || (deliveredIndex > segmentIndex && arrival > arrivalFloor);
    }
  }

  /**
   * A relocation is about to start the variant's job attempt at the seek's first segment, which
   * replaces a seek that the variant still waited on. The seek's worker learns what to pause once
   * {@link #variantChanged} finds the seek's job attempt running.
   */
  public synchronized void seekStarted(
      @NonNull StreamSession session, @NonNull String variantLabel, int segmentIndex) {
    var sessionId = session.getSessionId();
    // The seek's own request, noted before the relocation, places the viewer until the next one.
    // When a newer request was noted first, an older request relocates, so every position since
    // stays.
    var position = segmentIndex;
    if (newestRequestedSegments.getOrDefault(sessionId, segmentIndex) != segmentIndex) {
      position = Math.max(position, highestRequestedSegments.getOrDefault(sessionId, position));
    }

    highestRequestedSegments.put(sessionId, position);
    latestSeeks.put(sessionId, clock.instant());
    var key = new VariantKey(sessionId, variantLabel);
    var earlierWorker = Optional.ofNullable(pendingSeeks.get(key)).flatMap(PendingSeek::target);
    pendingSeeks.put(
        key,
        new PendingSeek(
            segmentIndex,
            newestArrivals.getOrDefault(sessionId, Long.MIN_VALUE),
            earlierWorker,
            false));
  }

  /**
   * A segment request of the variant waits for its segment, so the variant's job attempt stops
   * yielding at once.
   */
  public synchronized void segmentRequestWaits(
      @NonNull StreamSession session, @NonNull String variantLabel) {
    reconsiderHoldBacks(session.getSessionId());
    var attempt = session.getVariantHandle(variantLabel).map(TranscodeHandle::attemptId);
    var target = transcodeExecutor.runningExecutionTarget(session.getSessionId(), variantLabel);
    if (attempt.isEmpty()
        || target.isEmpty()
        || !yielding.getOrDefault(target.get(), Set.of()).contains(attempt.get())) {
      return;
    }

    sendYields(target.get());
  }

  /**
   * The player requested the media segment, which counts toward its position. A request within
   * stored media can move the viewer past the lead of a job attempt that a yield named, so the
   * yield is sent again without it.
   */
  public synchronized void mediaSegmentRequested(
      @NonNull UUID sessionId, int segmentIndex, long arrival) {
    highestRequestedSegments.merge(sessionId, segmentIndex, Math::max);
    var newest = newestArrivals.get(sessionId);
    if (newest == null || arrival > newest) {
      newestArrivals.put(sessionId, arrival);
      newestRequestedSegments.put(sessionId, segmentIndex);
    }

    var attempts =
        runtimeRegistry.findById(sessionId).stream()
            .flatMap(session -> session.getVariantHandles().values().stream())
            .map(TranscodeHandle::attemptId)
            .collect(Collectors.toSet());
    yielding.entrySet().stream()
        .filter(entry -> entry.getValue().stream().anyMatch(attempts::contains))
        .map(Map.Entry::getKey)
        .toList()
        .forEach(this::sendYields);
    reconsiderHoldBacks(sessionId);
  }

  /**
   * The server delivered the segment to a player. The seek's first segment ends the seek, and so
   * does a later segment of a request that arrived after the seek.
   */
  public synchronized void segmentDelivered(
      @NonNull UUID sessionId,
      @NonNull String variantLabel,
      @NonNull String segmentName,
      long arrival) {
    var key = new VariantKey(sessionId, variantLabel);
    var pending = pendingSeeks.get(key);
    var index = SegmentNames.indexOf(segmentName);
    if (pending == null || index.isEmpty() || !pending.endsWith(index.getAsInt(), arrival)) {
      return;
    }

    pendingSeeks.remove(key);
    pending.target().ifPresent(this::sendYields);
  }

  /**
   * The variant's job attempt started, was replaced, stopped or exhausted. A seek that waits on the
   * variant follows its running job attempt to that job attempt's worker, which then pauses what
   * the seek needs, and ends when its job attempt stops.
   */
  public synchronized void variantChanged(
      @NonNull StreamSession session, @NonNull String variantLabel) {
    reconsiderHoldBacks(session.getSessionId());
    var key = new VariantKey(session.getSessionId(), variantLabel);
    var pending = pendingSeeks.get(key);
    if (pending == null) {
      return;
    }

    var target = runningTarget(session, variantLabel);
    if (target.isEmpty()) {
      endSeekWithoutJobAttempt(session, key, pending);
      return;
    }

    if (pending.announced() && target.equals(pending.target())) {
      return;
    }

    pendingSeeks.put(key, pending.announcedOn(target.get()));
    pending.target().filter(earlier -> !earlier.equals(target.get())).ifPresent(this::sendYields);
    var named = yieldingAttempts(target.get());
    if (!named.isEmpty() || yielding.containsKey(target.get())) {
      send(target.get(), named);
    }
  }

  // A seek whose job attempt failed to start keeps waiting for a recovery replacement while the
  // variant's handle stays active; a stopped, exhausted or suspended variant ends it.
  private void endSeekWithoutJobAttempt(
      StreamSession session, VariantKey key, PendingSeek pending) {
    var active = isActive(session, key.variantLabel());
    if (active && !pending.announced()) {
      return;
    }

    pendingSeeks.remove(key);
    pending.target().ifPresent(this::sendYields);
  }

  private static boolean isActive(StreamSession session, String variantLabel) {
    return session
        .getVariantHandle(variantLabel)
        .filter(handle -> handle.status() == TranscodeStatus.ACTIVE)
        .isPresent();
  }

  private Optional<ExecutionTargetId> runningTarget(StreamSession session, String variantLabel) {
    if (!isActive(session, variantLabel)) {
      return Optional.empty();
    }

    return transcodeExecutor.runningExecutionTarget(session.getSessionId(), variantLabel);
  }

  /**
   * The job attempt started. A relocation, a recovery replacement or an on-demand start can begin
   * before media that the variant already stored, which the job attempt reproduces first.
   */
  public synchronized void jobAttemptStarted(
      @NonNull StreamSession session,
      @NonNull String variantLabel,
      @NonNull TranscodeHandle handle) {
    ownOutputs.put(
        new VariantKey(session.getSessionId(), variantLabel),
        ownOutputAtStart(session, variantLabel, handle));
  }

  /** A job attempt's upload stored the media segment, which counts as that job attempt's output. */
  public synchronized void mediaSegmentPublished(@NonNull MediaSegmentPublishedEvent published) {
    ownOutputs.computeIfPresent(
        new VariantKey(published.streamSessionId(), published.variantLabel()),
        (_, output) -> output.storedBy(published));
    reconsiderHoldBacks(published.streamSessionId());
  }

  // Guarded by this monitor. A job attempt's lead changes when its viewer moves, when it stores
  // media and when a player starts to wait on it.
  private void reconsiderHoldBacks(UUID sessionId) {
    var session =
        runtimeRegistry
            .findById(sessionId)
            .filter(found -> found.getDelivery() == StreamDelivery.HLS);
    if (session.isEmpty()) {
      holdBack.forget(sessionId);
      return;
    }

    holdBack.reconsider(sessionId, leadsOf(session.get()));
  }

  private List<HoldBack.AttemptLead> leadsOf(StreamSession session) {
    var viewerSegment = viewerSegment(session);
    if (viewerSegment.isEmpty()) {
      return List.of();
    }

    var sessionId = session.getSessionId();
    return session.getVariantHandles().entrySet().stream()
        .filter(entry -> !heldRequests.holdsRequestsOn(sessionId, entry.getKey()))
        .flatMap(
            entry ->
                attemptLead(session, entry.getKey(), entry.getValue(), viewerSegment.getAsInt())
                    .stream())
        .toList();
  }

  // The target comes from the job attempt's own uploads rather than from the executor, so that no
  // segment request waits for a worker connection that is busy publishing a segment.
  private Optional<HoldBack.AttemptLead> attemptLead(
      StreamSession session, String variantLabel, TranscodeHandle handle, int viewerSegment) {
    var target =
        Optional.ofNullable(ownOutputs.get(new VariantKey(session.getSessionId(), variantLabel)))
            .filter(output -> output.attemptId().equals(handle.attemptId()))
            .flatMap(OwnOutput::uploadedFrom);
    if (target.isEmpty() || !isActive(session, variantLabel)) {
      return Optional.empty();
    }

    return ownLead(session, variantLabel, handle, viewerSegment)
        .map(
            lead ->
                HoldBack.AttemptLead.builder()
                    .attemptId(handle.attemptId())
                    .target(target.get())
                    .lead(lead.stored())
                    .nextSegment(lead.nextSegment())
                    .build());
  }

  private OwnOutput ownOutputAtStart(
      StreamSession session, String variantLabel, TranscodeHandle handle) {
    var timeline = MediaSegmentTimelines.of(session, properties);
    var firstMissing = handle.startSequenceNumber() + 1;
    while (timeline.advertises(firstMissing) && isStored(session, variantLabel, firstMissing)) {
      firstMissing++;
    }

    var nextStored = firstMissing;
    while (timeline.advertises(nextStored) && !isStored(session, variantLabel, nextStored)) {
      nextStored++;
    }

    if (!timeline.advertises(nextStored)) {
      nextStored = Integer.MAX_VALUE;
    }

    return new OwnOutput(
        handle.attemptId(), handle.startSequenceNumber() - 1, nextStored, Optional.empty());
  }

  /** The stream session ended, so its seeks wait for nothing. */
  public synchronized void forgetSession(@NonNull UUID sessionId) {
    holdBack.forget(sessionId);
    highestRequestedSegments.remove(sessionId);
    newestRequestedSegments.remove(sessionId);
    ownOutputs.keySet().removeIf(key -> key.sessionId().equals(sessionId));
    newestArrivals.remove(sessionId);
    latestSeeks.remove(sessionId);
    var ended =
        pendingSeeks.entrySet().stream()
            .filter(entry -> entry.getKey().sessionId().equals(sessionId))
            .toList();
    ended.forEach(entry -> pendingSeeks.remove(entry.getKey()));
    ended.stream()
        .flatMap(entry -> entry.getValue().target().stream())
        .distinct()
        .forEach(this::sendYields);
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
            .filter(entry -> entry.getValue().target().filter(target::equals).isPresent())
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

  // The media that the job attempt has stored itself without a gap after the viewer's segment
  // outlasts the hold limit, the time to produce the job attempt's next segment at real time and
  // the position margin, so its viewer plays through the longest pause and the next segment.
  private boolean leadsItsViewer(
      StreamSession session, String variantLabel, TranscodeHandle handle, int viewerSegment) {
    return ownLead(session, variantLabel, handle, viewerSegment)
        .filter(
            lead ->
                lead.stored()
                        .compareTo(
                            properties
                                .seekPriorityHoldLimit()
                                .plus(lead.nextSegment())
                                .plus(properties.seekPriorityPositionMargin()))
                    > 0)
        .isPresent();
  }

  // The media that the job attempt has stored itself without a gap after the viewer's segment, and
  // the duration of the segment that it produces next. Empty when it has produced nothing past the
  // viewer, or when a stored segment that is not its own output follows that media: its next work
  // is then reproducing that media rather than a segment that a lead budgets.
  private Optional<OwnLead> ownLead(
      StreamSession session, String variantLabel, TranscodeHandle handle, int viewerSegment) {
    var timeline = MediaSegmentTimelines.of(session, properties);
    var ownOutput = ownOutputOf(session, variantLabel, handle);
    var stored = Duration.ZERO;
    var next = viewerSegment + 1;
    while (timeline.advertises(next) && ownOutput.test(next)) {
      stored = stored.plus(timeline.mediaSegmentDuration(next));
      next++;
    }

    if (handle.startSequenceNumber() >= next || isStored(session, variantLabel, next)) {
      return Optional.empty();
    }

    var nextSegment =
        timeline.advertises(next) ? timeline.mediaSegmentDuration(next) : Duration.ZERO;
    return Optional.of(new OwnLead(stored, nextSegment));
  }

  private record OwnLead(Duration stored, Duration nextSegment) {}

  // Stored media that the job attempt's own uploads stored. Media that it found stored, or that
  // another job attempt's upload stored, counts once its own upload stores that segment again.
  private IntPredicate ownOutputOf(
      StreamSession session, String variantLabel, TranscodeHandle handle) {
    var output =
        Optional.ofNullable(ownOutputs.get(new VariantKey(session.getSessionId(), variantLabel)))
            .filter(recorded -> recorded.attemptId().equals(handle.attemptId()));
    if (output.isEmpty()) {
      return _ -> false;
    }

    return index -> output.get().includes(index) && isStored(session, variantLabel, index);
  }

  private boolean isStored(StreamSession session, String variantLabel, int index) {
    return segmentStore.segmentExists(
        session.getSessionId(),
        SegmentNames.storedSegmentName(variantLabel, SegmentNames.mediaSegmentName(index)));
  }

  // The farther of the reported position and the highest requested segment: a seek within stored
  // media relocates nothing, so the player's requests can run ahead of its last report.
  private OptionalInt viewerSegment(StreamSession session) {
    var requested = Optional.ofNullable(highestRequestedSegments.get(session.getSessionId()));
    var reported =
        reportedPosition(session)
            .map(MediaSegmentTimelines.of(session, properties)::mediaSegmentIndexAt);
    return Stream.of(reported, requested)
        .flatMap(Optional::stream)
        .mapToInt(Integer::intValue)
        .max();
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
