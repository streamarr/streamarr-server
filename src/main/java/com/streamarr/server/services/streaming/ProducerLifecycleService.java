package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns every producer (transcode process) mutation for a stream session: initial start, resume of a
 * suspended session, relocation to a distant segment, replacement, and suspension. Every mutation
 * serializes on the per-session mutex this service holds, including initial startup because the
 * session is published before its producers finish starting.
 */
@Slf4j
@Builder
public class ProducerLifecycleService {

  /** Beyond this lead, restarting the encoder beats waiting for it to catch up. */
  private static final Duration FORWARD_RELOCATION_GAP = Duration.ofSeconds(24);

  private static final int FIRST_SEGMENT_ONLY = 1;
  private static final int FIRST_SEGMENT_AND_PREROLL = 2;

  private final TranscodeExecutor transcodeExecutor;
  private final SegmentStore segmentStore;
  private final StreamingProperties properties;
  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final MutexFactory<UUID> sessionMutex;
  private final ServerSettingsService serverSettings;
  @Builder.Default private final Clock clock = Clock.systemUTC();
  private final ConcurrentHashMap<VariantKey, VariantDeliveryState> recoveryStates =
      new ConcurrentHashMap<>();
  private final AtomicLong requestArrivals = new AtomicLong();
  // The newest request that positioned each variant: started it on demand, relocated it or resumed
  // it (ADR 0019).
  private final ConcurrentHashMap<VariantKey, SegmentRequest> newestPositioningRequests =
      new ConcurrentHashMap<>();
  // The newest media-segment request of each stream session, for any variant: where the player is.
  private final ConcurrentHashMap<UUID, SegmentRequest> newestMediaSegmentRequests =
      new ConcurrentHashMap<>();
  // The variant that each stream session's player plays: the variant of the last media segment
  // delivered while that variant was the variant of the newest media-segment request (ADR 0019).
  private final ConcurrentHashMap<UUID, String> playingVariants = new ConcurrentHashMap<>();

  public enum RecoveryResult {
    WAITING,
    EXHAUSTED,
    SESSION_GONE,
    SEGMENT_NOT_ADVERTISED,
    // A request for another video variant stopped the variant, or its on-demand start failed.
    // Only a new request for the variant starts it again (ADR 0019).
    VARIANT_NOT_RUNNING,
    // A newer request positioned the variant, and the request's segment needs the variant
    // relocated or resumed. Only a request at least as new may relocate or resume it (ADR 0019).
    SUPERSEDED
  }

  private record VariantKey(UUID sessionId, String variantLabel) {}

  @Builder
  private record PendingSegment(SegmentRequest request, int requestedIndex) {}

  /**
   * Observes progress and decides whether to wait, replace, or exhaust under the same session mutex
   * as every producer mutation. Recovery state is committed before releasing it. Once a newer
   * request has positioned the variant, this request never relocates or resumes it: it ends as
   * {@link RecoveryResult#SUPERSEDED} when its segment needs either. Any request still replaces a
   * dead or stalled job attempt.
   */
  public RecoveryResult recover(SegmentRequest request) {
    return withSessionLock(request.sessionId(), () -> doRecover(request));
  }

  // Also remembered when the relocation or resume fails, so the replacement starts at this
  // request's segment (ADR 0019).
  private void rememberNewestPositioningRequest(SegmentRequest request) {
    newestPositioningRequests.put(variantKeyOf(request), request);
  }

  // Once a newer request has positioned the variant, an older request that relocated or resumed it
  // would move the variant away from the newer request's segment.
  private boolean isSuperseded(SegmentRequest request) {
    return newestPositioningRequest(request)
        .filter(newest -> newest.arrival() > request.arrival())
        .isPresent();
  }

  private Optional<SegmentRequest> newestPositioningRequest(SegmentRequest request) {
    return Optional.ofNullable(newestPositioningRequests.get(variantKeyOf(request)));
  }

  private static VariantKey variantKeyOf(SegmentRequest request) {
    return new VariantKey(request.sessionId(), request.variantLabel());
  }

  /**
   * How long a held segment request can wait for a wake before recovery must look at the variant
   * again: until the variant's tracked job attempt counts as stalled. Before recovery tracks a job
   * attempt of the variant, the startup stall threshold bounds the wait.
   */
  public Duration timeUntilStall(UUID sessionId, String variantLabel) {
    return withSessionLock(
        sessionId, () -> doTimeUntilStall(new VariantKey(sessionId, variantLabel)));
  }

  private Duration doTimeUntilStall(VariantKey key) {
    var state = recoveryStates.get(key);
    var session = runtimeRegistry.findById(key.sessionId());
    if (state == null || session.isEmpty()) {
      return startupStallThreshold(FIRST_SEGMENT_ONLY);
    }

    var deadline =
        state.stallDeadline(
            properties.producerStallThreshold(), startupStallThreshold(session.get(), state));
    var remaining = Duration.between(clock.instant(), deadline);
    if (remaining.isNegative()) {
      return Duration.ZERO;
    }

    return remaining;
  }

  public void forgetRecovery(UUID sessionId) {
    withSessionLock(
        sessionId,
        () -> {
          recoveryStates.keySet().removeIf(key -> key.sessionId().equals(sessionId));
          newestPositioningRequests.keySet().removeIf(key -> key.sessionId().equals(sessionId));
          newestMediaSegmentRequests.remove(sessionId);
          playingVariants.remove(sessionId);
        });
  }

  private RecoveryResult doRecover(SegmentRequest request) {
    var sessionId = request.sessionId();
    var variantLabel = request.variantLabel();
    var segmentName = request.segmentName();
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null) {
      return RecoveryResult.SESSION_GONE;
    }

    if (!session.offersVariant(variantLabel)) {
      return RecoveryResult.SESSION_GONE;
    }

    if (transcodeExecutor.terminalFailure(sessionId).isPresent()) {
      session
          .getVariantHandle(variantLabel)
          .ifPresent(handle -> doExhaust(sessionId, variantLabel, handle.attemptId()));
      return RecoveryResult.EXHAUSTED;
    }

    // No job attempt may start at or past the media segment count it advertises, so a request
    // beyond the playlist must neither relocate nor replace the variant's producer.
    if (isUnadvertisedMediaSegment(session, segmentName)) {
      return RecoveryResult.SEGMENT_NOT_ADVERTISED;
    }

    if (segmentStore.segmentExists(sessionId, segmentName)) {
      return RecoveryResult.WAITING;
    }

    if (session.getVariantHandle(variantLabel).isEmpty()) {
      return RecoveryResult.VARIANT_NOT_RUNNING;
    }

    if (isSuperseded(request) && needsPositioning(session, variantLabel, segmentName)) {
      return superseded(request);
    }

    var positioning = tryEnsurePositioned(session, variantLabel, segmentName);
    if (positioning != Positioning.UNCHANGED) {
      rememberNewestPositioningRequest(request);
    }

    var handle = session.getVariantHandle(variantLabel).orElseThrow();
    var state =
        recoveryStates.computeIfAbsent(
            new VariantKey(sessionId, variantLabel), _ -> new VariantDeliveryState());
    var pending =
        PendingSegment.builder()
            .request(request)
            .requestedIndex(
                SegmentNames.indexOf(segmentName).orElseGet(() -> currentSegment(session)))
            .build();
    if (handle.status() == TranscodeStatus.FAILED
        && !state.resetForFreshTargets(
            eligibleTargets(session, variantLabel), handle, clock.instant())) {
      return RecoveryResult.EXHAUSTED;
    }

    return attemptRecovery(state, pending, positioning != Positioning.FAILED);
  }

  private boolean needsPositioning(StreamSession session, String variantLabel, String segmentName) {
    return session.isSuspended() || requiresRelocation(session, variantLabel, segmentName);
  }

  private static RecoveryResult superseded(SegmentRequest request) {
    log.info(
        "Ended the request for {} of session {}: a newer request positioned variant {}",
        request.segmentName(),
        request.sessionId(),
        request.variantLabel());
    return RecoveryResult.SUPERSEDED;
  }

  private enum Positioning {
    UNCHANGED,
    MOVED,
    // The request's relocation or resume failed to start a job attempt. A relocation has already
    // stopped the earlier job attempt.
    FAILED
  }

  private Positioning tryEnsurePositioned(
      StreamSession session, String variantLabel, String segmentName) {
    if (SegmentNames.indexOf(segmentName).isEmpty() && !session.isSuspended()) {
      return Positioning.UNCHANGED;
    }

    try {
      if (tryRelocateOrResume(session.getSessionId(), variantLabel, segmentName)) {
        return Positioning.MOVED;
      }

      return Positioning.UNCHANGED;
    } catch (TranscodeException e) {
      log.warn(
          "Positioning failed for session {} segment {}: {}",
          session.getSessionId(),
          segmentName,
          e.getMessage());
      return Positioning.FAILED;
    }
  }

  private RecoveryResult attemptRecovery(
      VariantDeliveryState state, PendingSegment pending, boolean positioned) {
    var request = pending.request();
    while (true) {
      var session = runtimeRegistry.findById(request.sessionId()).orElse(null);
      if (session == null) {
        return RecoveryResult.SESSION_GONE;
      }

      var handle = session.getVariantHandle(request.variantLabel()).orElseThrow();
      syncProgress(state, handle, pending);
      var running = transcodeExecutor.isRunning(request.sessionId(), request.variantLabel());
      if (running && !hasStalled(state, session)) {
        return RecoveryResult.WAITING;
      }

      var reason = replacementReason(running, positioned, session);
      if (!isReplaceableStatus(handle, session, reason)) {
        return RecoveryResult.WAITING;
      }

      var eligibleTargets = eligibleTargets(session, request.variantLabel());
      // Segment publication is external to the session mutex and may advance during discovery.
      syncProgress(state, handle, pending);
      if (noLongerNeedsRecovery(pending, running && !hasStalled(state, session))) {
        return RecoveryResult.WAITING;
      }

      var target = state.nextTarget(eligibleTargets);
      if (target.isEmpty()) {
        return exhaust(state, pending, handle.attemptId());
      }

      var command =
          ReplaceProducerCommand.builder()
              .sessionId(request.sessionId())
              .variantLabel(request.variantLabel())
              .segmentName(request.segmentName())
              .segmentIndex(replacementStart(state, pending))
              .expectedAttemptId(handle.attemptId())
              .reason(reason)
              .target(target.get())
              .build();
      var outcome = replaceAndTrack(state, session, command);
      if (outcome.isPresent()) {
        return outcome.get();
      }
    }
  }

  // Once a request for a media segment has positioned the variant, a replacement never starts past
  // that request's segment until the segment is stored, then starts where the replaced job attempt
  // stopped. A start at a later held request's segment would skip the segment of a superseded
  // request, which cannot move the variant back (ADR 0019).
  private int replacementStart(VariantDeliveryState state, PendingSegment pending) {
    var requestedIndex = pending.requestedIndex();
    var newest = newestPositioningRequest(pending.request());
    if (newest.isEmpty()) {
      return requestedIndex;
    }

    var newestIndex = SegmentNames.indexOf(newest.get().segmentName());
    if (newestIndex.isEmpty()) {
      return requestedIndex;
    }

    if (!segmentStore.segmentExists(newest.get().sessionId(), newest.get().segmentName())) {
      return Math.min(requestedIndex, newestIndex.getAsInt());
    }

    return state.frontier();
  }

  // Every later job attempt of a pinned variant runs only where its encoder backend runs.
  private Set<ExecutionTargetId> eligibleTargets(StreamSession session, String variantLabel) {
    return session
        .getPinnedEncoder(variantLabel)
        .map(transcodeExecutor::executionTargets)
        .orElseGet(() -> transcodeExecutor.executionTargets(session.getTranscodeDecision()));
  }

  private boolean noLongerNeedsRecovery(PendingSegment pending, boolean runningWithoutStall) {
    return runningWithoutStall
        || segmentStore.segmentExists(
            pending.request().sessionId(), pending.request().segmentName());
  }

  private RecoveryResult exhaust(
      VariantDeliveryState state, PendingSegment pending, UUID expectedAttemptId) {
    if (!doExhaust(
        pending.request().sessionId(), pending.request().variantLabel(), expectedAttemptId)) {
      return RecoveryResult.WAITING;
    }

    log.warn(
        "Recovery exhausted for session {} variant {}: every eligible execution target in {} was tried",
        pending.request().sessionId(),
        pending.request().variantLabel(),
        state.attemptedTargets());
    return RecoveryResult.EXHAUSTED;
  }

  private Optional<RecoveryResult> replaceAndTrack(
      VariantDeliveryState state, StreamSession session, ReplaceProducerCommand command) {
    return switch (doReplace(command)) {
      case ReplaceResult.Replaced(UUID newAttemptId) -> {
        state.trackReplacement(
            command.target(),
            session.getVariantHandle(command.variantLabel()).orElseThrow(),
            clock.instant());
        log.info(
            "Replaced producer for session {} variant {} on target {} at segment {} (attempt {})",
            command.sessionId(),
            command.variantLabel(),
            command.target().value(),
            command.segmentIndex(),
            newAttemptId);
        yield Optional.of(RecoveryResult.WAITING);
      }
      case ReplaceResult.Refused(String refusal) -> {
        state.markRefused(command.target());
        log.warn(
            "Execution target {} refused replacement for session {} variant {}: {}",
            command.target().value(),
            command.sessionId(),
            command.variantLabel(),
            refusal);
        yield Optional.empty();
      }
      case ReplaceResult.Superseded() -> Optional.of(RecoveryResult.WAITING);
      case ReplaceResult.SessionGone() -> Optional.of(RecoveryResult.SESSION_GONE);
    };
  }

  private void syncProgress(
      VariantDeliveryState state, TranscodeHandle handle, PendingSegment pending) {
    state.syncProgress(
        handle,
        index ->
            segmentStore.segmentExists(
                pending.request().sessionId(),
                SegmentNames.siblingName(pending.request().segmentName(), index)),
        clock.instant());
  }

  private boolean hasStalled(VariantDeliveryState state, StreamSession session) {
    return state.hasStalled(
        properties.producerStallThreshold(),
        startupStallThreshold(session, state),
        clock.instant());
  }

  // Before its first media segment is stored, a job attempt gets the stall threshold plus one
  // target segment duration for each segment it encodes: its first segment and, when it encodes
  // video from after segment 0, the preroll segment before it, where the worker starts encoding.
  private Duration startupStallThreshold(StreamSession session, VariantDeliveryState state) {
    if (session.getTranscodeDecision().transcodeMode().copiesVideo() || state.runStart() == 0) {
      return startupStallThreshold(FIRST_SEGMENT_ONLY);
    }

    return startupStallThreshold(FIRST_SEGMENT_AND_PREROLL);
  }

  private Duration startupStallThreshold(int segmentsEncodedBeforeFirstStored) {
    return properties
        .producerStallThreshold()
        .plus(properties.targetSegmentDuration().multipliedBy(segmentsEncodedBeforeFirstStored));
  }

  private static ReplacementReason replacementReason(
      boolean running, boolean positioned, StreamSession session) {
    if (running) {
      return ReplacementReason.STALLED;
    }

    if (!positioned && session.isSuspended()) {
      return ReplacementReason.RESUME_FAILED;
    }

    return ReplacementReason.DEAD;
  }

  private enum ReplacementReason {
    DEAD,
    STALLED,
    RESUME_FAILED
  }

  @Builder
  private record ReplaceProducerCommand(
      UUID sessionId,
      String variantLabel,
      String segmentName,
      int segmentIndex,
      UUID expectedAttemptId,
      ReplacementReason reason,
      ExecutionTargetId target) {}

  private sealed interface ReplaceResult {
    record Replaced(UUID newAttemptId) implements ReplaceResult {}

    record Refused(String reason) implements ReplaceResult {}

    record Superseded() implements ReplaceResult {}

    record SessionGone() implements ReplaceResult {}
  }

  /**
   * Starts the variant that the multivariant playlist lists first, at the stream session's current
   * segment. No other variant starts until the player requests one of its segments (ADR 0019).
   */
  public StreamSession startFirstListedVariant(StreamSession session) {
    var sessionId = session.getSessionId();
    return withSessionLock(
        sessionId,
        () -> {
          var current =
              runtimeRegistry
                  .findById(sessionId)
                  .orElseThrow(() -> new SessionNotFoundException(sessionId));
          startVariant(current, firstListedVariant(current), current.getCurrentSegment());
          if (runtimeRegistry.findById(sessionId).isEmpty()) {
            throw new SessionNotFoundException(sessionId);
          }

          runtimeRegistry.save(current);
          return current;
        });
  }

  private static String firstListedVariant(StreamSession session) {
    if (session.getVariants().isEmpty()) {
      return StreamSession.defaultVariant();
    }

    return session.getVariants().getFirst().label();
  }

  /**
   * Applies the on-demand rules of ADR 0019 to one segment request under one session lock. A
   * media-segment request stops the job attempts of the other video variants, except the variant
   * that the player plays while that variant's running job attempt can produce the requested
   * segment without a relocation. A request for a variant without a job attempt then starts that
   * variant: at the requested media segment, or at the current segment for the initialization
   * segment. When the start of a media-segment request's variant fails, the variant that the player
   * plays stops and the start is tried once more. A stored media segment starts nothing, and
   * neither does a full transcode limit. A variant that does not start stays without a job attempt,
   * so recovery answers the request as {@link RecoveryResult#VARIANT_NOT_RUNNING}. Returns the
   * request numbered in order of arrival; the caller passes it to every later {@link #recover}.
   */
  public SegmentRequest prepareRequestedVariant(
      UUID sessionId, String variantLabel, String segmentName) {
    return withSessionLock(
        sessionId,
        () -> {
          var request =
              SegmentRequest.builder()
                  .sessionId(sessionId)
                  .variantLabel(variantLabel)
                  .segmentName(segmentName)
                  .arrival(requestArrivals.incrementAndGet())
                  .build();
          if (SegmentNames.indexOf(segmentName).isPresent()) {
            rememberNewestMediaSegmentRequest(request);
            doStopVariantsTheSwitchLeaves(request);
          }

          if (tryStartVariantOnDemand(request)) {
            rememberNewestPositioningRequest(request);
          }

          return request;
        });
  }

  private void rememberNewestMediaSegmentRequest(SegmentRequest request) {
    var offered =
        runtimeRegistry
            .findById(request.sessionId())
            .filter(session -> session.offersVariant(request.variantLabel()));
    if (offered.isPresent()) {
      newestMediaSegmentRequests.put(request.sessionId(), request);
    }
  }

  /**
   * The current segment (ADR 0019): the media segment that the player needs next. That is the
   * segment of the stream session's newest media-segment request until that segment is stored, and
   * the segment after it once it is stored, unless it is the last advertised segment. Before any
   * media-segment request, it is the segment at which the stream session starts.
   */
  private int currentSegment(StreamSession session) {
    var newest = newestMediaSegmentRequests.get(session.getSessionId());
    if (newest == null) {
      return session.getCurrentSegment();
    }

    var index = SegmentNames.indexOf(newest.segmentName()).orElseThrow();
    var next = index + 1;
    if (!segmentStore.segmentExists(session.getSessionId(), newest.segmentName())
        || !timelineOf(session).advertises(next)) {
      return index;
    }

    return next;
  }

  /**
   * Once the player has received the request's media segment, stops every other video variant when
   * the request's variant is the variant of the stream session's newest media-segment request: the
   * player then plays that variant, so the variant that the player left stops (ADR 0019). A
   * delivered segment of a variant that the player has since left changes nothing.
   */
  public void completeVariantSwitch(SegmentRequest delivered) {
    if (SegmentNames.indexOf(delivered.segmentName()).isEmpty()) {
      return;
    }

    withSessionLock(delivered.sessionId(), () -> doCompleteVariantSwitch(delivered));
  }

  private void doCompleteVariantSwitch(SegmentRequest delivered) {
    var session = runtimeRegistry.findById(delivered.sessionId()).orElse(null);
    var newest = newestMediaSegmentRequests.get(delivered.sessionId());
    if (session == null
        || newest == null
        || !newest.variantLabel().equals(delivered.variantLabel())) {
      return;
    }

    playingVariants.put(delivered.sessionId(), delivered.variantLabel());
    var stopped = doStopVideoVariantsExcept(session, Set.of(delivered.variantLabel()));
    if (stopped.isEmpty()) {
      return;
    }

    log.info(
        "Stopped variants {} of session {} once variant {} delivered {}",
        stopped,
        delivered.sessionId(),
        delivered.variantLabel(),
        delivered.segmentName());
  }

  private void doStopVariantsTheSwitchLeaves(SegmentRequest request) {
    var session = runtimeRegistry.findById(request.sessionId()).orElse(null);
    if (session == null || !session.offersVariant(request.variantLabel())) {
      return;
    }

    var kept = new HashSet<String>();
    kept.add(request.variantLabel());
    variantToKeepForSwitchBack(session, request).ifPresent(kept::add);
    var stopped = doStopVideoVariantsExcept(session, kept);
    if (stopped.isEmpty()) {
      return;
    }

    log.info(
        "Stopped variants {} of session {} for a request of variant {}",
        stopped,
        request.sessionId(),
        request.variantLabel());
  }

  // The variant that the player plays keeps running beside the requested variant while its job
  // attempt can produce the requested segment without a relocation, so the player can switch back
  // to it (ADR 0019).
  private Optional<String> variantToKeepForSwitchBack(
      StreamSession session, SegmentRequest request) {
    var requestedIndex = SegmentNames.indexOf(request.segmentName()).orElseThrow();
    return Optional.ofNullable(playingVariants.get(request.sessionId()))
        .filter(playing -> !playing.equals(request.variantLabel()))
        .filter(playing -> canProduceWithoutRelocation(session, playing, requestedIndex));
  }

  // A job attempt that no longer runs still holds its configured transcode slot until its handle
  // goes, so only a running job attempt keeps its variant.
  private boolean canProduceWithoutRelocation(
      StreamSession session, String variantLabel, int segmentIndex) {
    if (session.getVariantHandle(variantLabel).isEmpty()
        || !transcodeExecutor.isRunning(session.getSessionId(), variantLabel)) {
      return false;
    }

    var segmentName = variantLabel + "/" + SegmentNames.mediaSegmentName(segmentIndex);
    return !requiresRelocation(session, variantLabel, segmentName);
  }

  // Answers the labels of the variants it stopped.
  private List<String> doStopVideoVariantsExcept(StreamSession session, Set<String> keptLabels) {
    var stopped =
        session.getVariantHandles().keySet().stream()
            .filter(label -> !keptLabels.contains(label))
            .toList();
    if (stopped.isEmpty()) {
      return stopped;
    }

    for (var variantLabel : stopped) {
      stopVariant(session, variantLabel);
    }

    runtimeRegistry.save(session);
    return stopped;
  }

  private void stopVariant(StreamSession session, String variantLabel) {
    if (transcodeExecutor.isRunning(session.getSessionId(), variantLabel)) {
      transcodeExecutor.stopVariant(session.getSessionId(), variantLabel);
    }

    session.removeVariantHandle(variantLabel);
  }

  private boolean tryStartVariantOnDemand(SegmentRequest request) {
    var sessionId = request.sessionId();
    var variantLabel = request.variantLabel();
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null || !needsOnDemandStart(session, variantLabel, request.segmentName())) {
      return false;
    }

    var startSequenceNumber =
        SegmentNames.indexOf(request.segmentName()).orElseGet(() -> currentSegment(session));
    var refusal = startOnDemand(session, request, startSequenceNumber);
    if (refusal.isPresent()) {
      log.warn(
          "Variant {} of session {} did not start on demand at segment {}: {}",
          variantLabel,
          sessionId,
          startSequenceNumber,
          refusal.get());
      return false;
    }

    runtimeRegistry.save(session);
    log.info(
        "Started variant {} of session {} on demand at segment {}",
        variantLabel,
        sessionId,
        startSequenceNumber);
    return true;
  }

  // The variant that the player plays must not keep the requested variant from starting: when no
  // slot was free and the start fails beside it, that variant stops and the start is tried once
  // more (ADR 0019). A start that fails for another reason leaves it running. Answers why the
  // variant did not start.
  private Optional<String> startOnDemand(
      StreamSession session, SegmentRequest request, int startSequenceNumber) {
    var slotShortage = hasSlotShortage(session);
    var refusal = attemptOnDemandStart(session, request.variantLabel(), startSequenceNumber);
    if (refusal.isEmpty()
        || !slotShortage
        || SegmentNames.indexOf(request.segmentName()).isEmpty()) {
      return refusal;
    }

    var stopped = doStopVideoVariantsExcept(session, Set.of(request.variantLabel()));
    if (stopped.isEmpty()) {
      return refusal;
    }

    log.info(
        "Stopped variants {} of session {} to make room for variant {}: {}",
        stopped,
        request.sessionId(),
        request.variantLabel(),
        refusal.get());
    return attemptOnDemandStart(session, request.variantLabel(), startSequenceNumber);
  }

  private Optional<String> attemptOnDemandStart(
      StreamSession session, String variantLabel, int startSequenceNumber) {
    if (!hasConfiguredTranscodeSlotFor(session)) {
      return Optional.of("the transcode limit is reached");
    }

    try {
      startVariant(session, variantLabel, startSequenceNumber);
      return Optional.empty();
    } catch (TranscodeException e) {
      return Optional.of(e.getMessage());
    }
  }

  private boolean needsOnDemandStart(
      StreamSession session, String variantLabel, String segmentName) {
    if (!session.offersVariant(variantLabel)
        || session.getVariantHandle(variantLabel).isPresent()
        || isUnadvertisedMediaSegment(session, segmentName)) {
      return false;
    }

    return SegmentNames.indexOf(segmentName).isEmpty()
        || !segmentStore.segmentExists(session.getSessionId(), segmentName);
  }

  // No configured transcode slot or no worker job slot is free for the stream session's decision.
  private boolean hasSlotShortage(StreamSession session) {
    return !hasConfiguredTranscodeSlotFor(session)
        || transcodeExecutor.availableSlots(session.getTranscodeDecision()) <= 0;
  }

  private boolean hasConfiguredTranscodeSlotFor(StreamSession session) {
    return !requiresTranscode(session) || freeConfiguredTranscodeSlots() > 0;
  }

  /**
   * The configured transcode limit minus the running job attempts of every transcoding stream
   * session. A suspended or stopped variant has no running job attempt.
   */
  public long freeConfiguredTranscodeSlots() {
    var runningJobAttempts =
        runtimeRegistry.findAll().stream()
            .filter(ProducerLifecycleService::requiresTranscode)
            .mapToLong(ProducerLifecycleService::countRunningJobAttempts)
            .sum();
    return properties.maxConcurrentTranscodes() - runningJobAttempts;
  }

  private static boolean requiresTranscode(StreamSession session) {
    return session.getTranscodeDecision().transcodeMode() != TranscodeMode.REMUX;
  }

  private static long countRunningJobAttempts(StreamSession session) {
    return session.getVariantHandles().values().stream()
        .filter(handle -> handle.status() == TranscodeStatus.ACTIVE)
        .count();
  }

  public void ensurePositioned(UUID sessionId, String variantLabel, String segmentName) {
    tryRelocateOrResume(sessionId, variantLabel, segmentName);
  }

  // Throws TranscodeException when the new job attempt fails to start.
  private boolean tryRelocateOrResume(UUID sessionId, String variantLabel, String segmentName) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null || !session.offersVariant(variantLabel)) {
      return false;
    }

    if (segmentStore.segmentExists(sessionId, segmentName)) {
      return false;
    }

    if (session.isSuspended()) {
      return withSessionLock(sessionId, () -> tryResume(sessionId, variantLabel, segmentName));
    }

    if (!requiresRelocation(session, variantLabel, segmentName)) {
      return false;
    }

    return withSessionLock(sessionId, () -> tryRelocate(sessionId, variantLabel, segmentName));
  }

  public void suspend(UUID sessionId) {
    withSessionLock(sessionId, () -> doSuspend(sessionId));
  }

  private void doSuspend(UUID sessionId) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null) {
      return;
    }

    transcodeExecutor.stop(session.getSessionId());
    for (var entry : session.getVariantHandles().entrySet()) {
      var handle = entry.getValue();
      if (handle.status() != TranscodeStatus.ACTIVE) {
        continue;
      }

      session.setVariantHandle(entry.getKey(), handle.withStatus(TranscodeStatus.SUSPENDED));
    }

    runtimeRegistry.save(session);
  }

  /**
   * Removes the session from the registry under the session mutex, so an in-flight replace either
   * completes (and its saved session is removed here) or observes the removal — a destroyed session
   * can never be resurrected by a racing save.
   */
  public boolean removeSession(UUID sessionId) {
    return withSessionLock(sessionId, () -> runtimeRegistry.removeById(sessionId).isPresent());
  }

  /**
   * Stops every producer of a session already removed from the registry. Taking the mutex closes
   * the window where a concurrent replace could start a producer for a destroyed session.
   */
  public void stopForDestroy(UUID sessionId) {
    withSessionLock(sessionId, () -> transcodeExecutor.destroySession(sessionId));
  }

  private ReplaceResult doReplace(ReplaceProducerCommand command) {
    var session = runtimeRegistry.findById(command.sessionId()).orElse(null);
    if (session == null) {
      return new ReplaceResult.SessionGone();
    }

    if (segmentStore.segmentExists(command.sessionId(), command.segmentName())) {
      return new ReplaceResult.Superseded();
    }

    var handle = session.getVariantHandle(command.variantLabel()).orElse(null);
    if (handle == null
        || !handle.attemptId().equals(command.expectedAttemptId())
        || !isReplaceableStatus(handle, session, command.reason())) {
      return new ReplaceResult.Superseded();
    }

    var producerRunning = transcodeExecutor.isRunning(command.sessionId(), command.variantLabel());
    if (producerRunning && command.reason() != ReplacementReason.STALLED) {
      // Only a stall observation licenses stopping a live producer; a caller claiming death
      // against a producer that is running holds a stale view (e.g. another waiter's healthy
      // replacement) and must re-observe instead.
      return new ReplaceResult.Superseded();
    }

    if (producerRunning) {
      transcodeExecutor.stopVariant(command.sessionId(), command.variantLabel());
    }

    TranscodeHandle replacement;
    try {
      replacement =
          transcodeExecutor.start(
              jobAttemptRequest(session, command.variantLabel(), command.segmentIndex()),
              command.target());
    } catch (TranscodeException refusal) {
      return new ReplaceResult.Refused(refusal.getMessage());
    }

    installHandle(session, command.variantLabel(), replacement);
    runtimeRegistry.save(session);
    return new ReplaceResult.Replaced(replacement.attemptId());
  }

  private boolean doExhaust(UUID sessionId, String variantLabel, UUID expectedAttemptId) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null) {
      return false;
    }

    // SUSPENDED is exhaustible here: with a healthy suspension every replacement supersedes
    // instead of consuming a target, so exhaustion is only ever reached when nothing — not even a
    // resume — can produce the segment (e.g. no eligible targets after a failed resume).
    var handle = session.getVariantHandle(variantLabel).orElse(null);
    if (handle == null || !handle.attemptId().equals(expectedAttemptId)) {
      return false;
    }

    // The last attempt can be alive but stalled; FAILED promises "no producer", so honor it.
    if (transcodeExecutor.isRunning(sessionId, variantLabel)) {
      transcodeExecutor.stopVariant(sessionId, variantLabel);
    }

    session.setVariantHandle(variantLabel, handle.withStatus(TranscodeStatus.FAILED));
    runtimeRegistry.save(session);
    return true;
  }

  /**
   * A suspended handle is the planned-suspension fence: replaceable only when the caller's resume
   * attempt just failed (recovery is then the only path back to a producer) or when the session is
   * merely part-suspended (a partial resume failure). FAILED stays replaceable so the new-target
   * reset can revive the exhausted attempt.
   */
  private static boolean isReplaceableStatus(
      TranscodeHandle handle, StreamSession session, ReplacementReason reason) {
    return switch (handle.status()) {
      case ACTIVE, FAILED -> true;
      case SUSPENDED -> reason == ReplacementReason.RESUME_FAILED || !session.isSuspended();
      case STARTING, SEEKING, STOPPED -> false;
    };
  }

  private TranscodeRequest jobAttemptRequest(
      StreamSession session, String variantLabel, int startSequenceNumber) {
    var request =
        baseRequest(session, variantLabel, startSequenceNumber)
            .encoderPreset(pinEncoderPreset(session, variantLabel));

    var variant =
        session.getVariants().stream()
            .filter(candidate -> candidate.label().equals(variantLabel))
            .findFirst();
    if (variant.isPresent()) {
      request.width(variant.get().width());
      request.height(variant.get().height());
      request.bitrate(variant.get().videoBitrate());
      return request.build();
    }

    var probe = session.getMediaProbe();
    var source = probe.displayedSize();
    var size =
        session.getTranscodeDecision().transcodeMode().copiesVideo()
            ? source
            : EncodedPictureSizes.atHeight(source, source.height());
    request.width(size.width());
    request.height(size.height());
    request.bitrate(probe.bitrate());
    return request.build();
  }

  /**
   * A running encoder produces segments sequentially from its start segment. A requested media
   * segment needs the variant's encoder relocated when it lies behind that start (it will never be
   * produced) or so far ahead of produced output that waiting would stall the player longer than
   * restarting. An initialization segment and a variant without a job attempt never relocate.
   */
  private boolean requiresRelocation(
      StreamSession session, String variantLabel, String segmentName) {
    var requestedIndex = SegmentNames.indexOf(segmentName);
    var handle = session.getVariantHandle(variantLabel);
    if (requestedIndex.isEmpty() || handle.isEmpty()) {
      return false;
    }

    var startSequenceNumber = handle.get().startSequenceNumber();
    if (requestedIndex.getAsInt() < startSequenceNumber) {
      return true;
    }

    var probeIndex = requestedIndex.getAsInt() - forwardGapSegments();
    if (probeIndex < startSequenceNumber) {
      return false;
    }

    return !segmentStore.segmentExists(
        session.getSessionId(), SegmentNames.siblingName(segmentName, probeIndex));
  }

  // A relocation restarts only the requested variant (ADR 0019).
  private boolean tryRelocate(UUID sessionId, String variantLabel, String segmentName) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null || !requiresRelocation(session, variantLabel, segmentName)) {
      return false;
    }

    if (segmentStore.segmentExists(sessionId, segmentName)) {
      return false;
    }

    var segmentIndex = SegmentNames.indexOf(segmentName).orElseThrow();
    if (transcodeExecutor.isRunning(sessionId, variantLabel)) {
      transcodeExecutor.stopVariant(sessionId, variantLabel);
    }

    startVariant(session, variantLabel, segmentIndex);
    session.setLastAccessedAt(Instant.now());
    runtimeRegistry.save(session);

    log.info(
        "Relocated variant {} of session {} to segment {}", variantLabel, sessionId, segmentIndex);
    return true;
  }

  private int forwardGapSegments() {
    var gapSegments =
        FORWARD_RELOCATION_GAP.toSeconds() / properties.targetSegmentDuration().toSeconds();
    return Math.max(1, (int) gapSegments);
  }

  /** A session that no longer exists advertises nothing to refuse, so this answers false. */
  public boolean isUnadvertisedMediaSegment(UUID sessionId, String segmentName) {
    return runtimeRegistry
        .findById(sessionId)
        .map(session -> isUnadvertisedMediaSegment(session, segmentName))
        .orElse(false);
  }

  private boolean isUnadvertisedMediaSegment(StreamSession session, String segmentName) {
    return SegmentNames.indexOf(segmentName).stream()
        .anyMatch(index -> !timelineOf(session).advertises(index));
  }

  private MediaSegmentTimeline timelineOf(StreamSession session) {
    return MediaSegmentTimelines.of(session, properties);
  }

  private boolean tryResume(UUID sessionId, String variantLabel, String segmentName) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null || !session.isSuspended()) {
      return false;
    }

    var segmentIndex = SegmentNames.indexOf(segmentName).orElseGet(() -> currentSegment(session));

    startVariant(session, variantLabel, segmentIndex);
    session.setLastAccessedAt(Instant.now());
    runtimeRegistry.save(session);

    log.info(
        "Resumed variant {} of suspended session {} at segment {}",
        variantLabel,
        sessionId,
        segmentIndex);
    return true;
  }

  private void startVariant(StreamSession session, String variantLabel, int startSequenceNumber) {
    var request = jobAttemptRequest(session, variantLabel, startSequenceNumber);
    installHandle(session, variantLabel, transcodeExecutor.start(request));
  }

  // The first job attempt that reports an encoder pins the variant to that encoder backend. Every
  // started job attempt moves the session's start segment, which the current segment falls back to
  // before the player requests a media segment, to its first media segment.
  private static void installHandle(
      StreamSession session, String variantLabel, TranscodeHandle handle) {
    session.setVariantHandle(variantLabel, handle);
    session.setCurrentSegment(handle.startSequenceNumber());
    handle.encoder().ifPresent(encoder -> session.pinEncoder(variantLabel, encoder));
  }

  private TranscodeRequest.TranscodeRequestBuilder baseRequest(
      StreamSession session, String variantLabel, int startSequenceNumber) {
    var frameRate =
        session
            .getTranscodeDecision()
            .videoSource()
            .map(VideoPlaybackFacts::frameRateRule)
            .flatMap(FrameRateRule::outputFrameRate);
    var timeline = timelineOf(session);
    return TranscodeRequest.builder()
        .sessionId(session.getSessionId())
        .variantLabel(variantLabel)
        .pinnedEncoder(session.getPinnedEncoder(variantLabel))
        .sourcePath(session.getSourcePath())
        .seekPosition(timeline.mediaSegmentStartSeconds(startSequenceNumber))
        .keyframeSeekPosition(timeline.keyframeSeekPosition(startSequenceNumber))
        .targetSegmentDuration(timeline.targetSegmentDurationSeconds())
        .framerate(
            frameRate
                .map(rate -> OptionalDouble.of(rate.toDouble()))
                .orElseGet(OptionalDouble::empty))
        .transcodeDecision(session.getTranscodeDecision())
        .startSequenceNumber(startSequenceNumber)
        .mediaSegmentCount(timeline.mediaSegmentCount());
  }

  private EncoderPreset pinEncoderPreset(StreamSession session, String variantLabel) {
    return session.pinEncoderPreset(
        variantLabel,
        () ->
            serverSettings.resolveEncoderPreset(session.getTranscodeDecision().videoCodecFamily()));
  }

  private void withSessionLock(UUID sessionId, Runnable action) {
    withSessionLock(
        sessionId,
        () -> {
          action.run();
          return null;
        });
  }

  private <T> T withSessionLock(UUID sessionId, Supplier<T> action) {
    var lock = sessionMutex.getMutex(sessionId);
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }
}
