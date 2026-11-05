package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.PlaybackSessionClosedException;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.events.CopiedVariantRanShortEvent;
import com.streamarr.server.services.streaming.events.TerminalTranscodeFailureEvent;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

@Slf4j
public class PlaybackSessionService implements AutoCloseable {
  private final StreamingService streamingService;
  private final PlaybackAuthorityGate authorityGate;
  private final Executor executor;
  private final Optional<ExecutorService> ownedExecutor;
  private final FormatRecoveryPolicy recoveryPolicy;
  private final Clock clock;
  private final ConcurrentHashMap<UUID, PlaybackSession> sessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, PlaybackSession> streamOwners = new ConcurrentHashMap<>();
  private final MutexFactory<UUID> mutexes = new MutexFactory<>();
  private final Object registration = new Object();
  private boolean closed;

  @Builder
  public PlaybackSessionService(
      @NonNull StreamingService streamingService,
      @NonNull PlaybackAuthorityGate authorityGate,
      Executor executor,
      int maximumFormatAttempts,
      Clock clock) {
    this.recoveryPolicy = new FormatRecoveryPolicy(maximumFormatAttempts);
    this.streamingService = streamingService;
    this.authorityGate = authorityGate;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    if (executor == null) {
      var owned = Executors.newVirtualThreadPerTaskExecutor();
      this.executor = owned;
      this.ownedExecutor = Optional.of(owned);
      return;
    }

    this.executor = executor;
    this.ownedExecutor = Optional.empty();
  }

  @Builder
  private static class PlaybackSession {
    private final UUID id;
    private final StartPlaybackSessionCommand command;
    private final VideoQuality requestedChoice;
    // Every later format attempt follows the playback choice that plays now (ADR 0052).
    private PlayingChoice choice;
    private PlaybackFormatAttempt current;
    // Every format attempt starts at the start position until playback moves from it.
    private Optional<Duration> startPosition;
    private Instant lastAccessedAt;
    @Builder.Default private List<VideoQuality> offeredChoices = List.of();
    @Builder.Default private int formatAttemptCount = 1;
    private final Set<String> excludedEncodeCodecFamilies = new HashSet<>();
    private final Set<UUID> streamIds = new HashSet<>();
    // A retired format attempt can still take a timeline report until its stream session ends.
    private final List<StreamSession> readyAttempts = new ArrayList<>();
    private final List<PlaybackDecision> decisions = new ArrayList<>();
    // The reason that the failure of the format attempt before the pending one gives it.
    @Builder.Default private Optional<DecisionReason> pendingAttemptCause = Optional.empty();
  }

  @Builder
  private record FormatAttemptReservation(
      PlaybackSession playback, UUID retiredStreamId, Optional<ReservedFormatAttempt> next) {}

  /**
   * The format attempt that a reservation starts: its stream session and the video operations it
   * may use. It plays {@code choice}, or {@code choiceWhenEncoding} once it turns out to encode the
   * video.
   */
  @Builder
  private record ReservedFormatAttempt(
      @NonNull UUID streamId,
      @NonNull VideoFormatConstraint videoFormatConstraint,
      @NonNull PlayingChoice choice,
      @NonNull PlayingChoice choiceWhenEncoding) {}

  /**
   * Starts a playback session and its first format attempt. A capability declaration that the API
   * boundary refuses starts nothing and returns every reason together.
   */
  public Outcome<PlaybackSessionSnapshot, StartPlaybackSessionRejection> start(
      @NonNull StartPlaybackSessionCommand command) {
    if (command.identity().profileId() == null) {
      throw new AuthenticationRequiredException();
    }

    requireAuthority(command.identity(), command.identity().playbackAuthority());
    var declarationRejections =
        CapabilityDeclarationRules.rejectionsOf(command.capabilityDeclaration());
    if (!declarationRejections.isEmpty()) {
      return Outcome.rejected(List.<StartPlaybackSessionRejection>copyOf(declarationRejections));
    }

    var childId = UUID.randomUUID();
    var playback = register(command, childId);
    try {
      // Only the first format attempt may direct-play: picking Original again starts a new playback
      // session (ADR 0045, ADR 0052).
      var outcome =
          streamingService.createSession(
              childCommand(playback, childId)
                  .videoFormatConstraint(VideoFormatConstraint.DIRECT_PLAY_OR_COPY_OR_ENCODE)
                  .build());
      return switch (outcome) {
        case Outcome.Accepted(var session) -> {
          offerChoicesOf(playback, session);
          settle(playback, session, firstEncodeChoice(playback.requestedChoice));
          yield Outcome.accepted(
              snapshot(playback).orElseThrow(PlaybackSessionClosedException::new));
        }
        case Outcome.Rejected(var rejections) -> {
          discard(playback);
          yield Outcome.rejected(List.<StartPlaybackSessionRejection>copyOf(rejections));
        }
      };
    } catch (RuntimeException failure) {
      discardAfterFailure(playback, failure);
      throw failure;
    }
  }

  // Every format attempt plays the same source to the same declaration, so the first one fixes the
  // offered choices.
  private void offerChoicesOf(PlaybackSession playback, StreamSession session) {
    var offered =
        QualityTier.offeredChoicesFor(
            session.getMediaProbe(), playback.command.capabilityDeclaration());
    locked(
        playback,
        () -> {
          playback.offeredChoices = offered;
          return true;
        });
  }

  private PlaybackSession register(StartPlaybackSessionCommand command, UUID childId) {
    synchronized (registration) {
      if (closed) {
        throw new PlaybackSessionClosedException();
      }

      // A missing quality is Auto.
      var requested = Optional.ofNullable(command.options().quality()).orElse(VideoQuality.AUTO);
      var playback =
          PlaybackSession.builder()
              .id(UUID.randomUUID())
              .command(command)
              .requestedChoice(requested)
              .choice(PlayingChoice.requested(requested))
              .current(new PlaybackFormatAttempt.Pending(childId))
              .startPosition(command.startPosition())
              .lastAccessedAt(clock.instant())
              .build();
      playback.streamIds.add(childId);
      sessions.put(playback.id, playback);
      streamOwners.put(childId, playback);
      return playback;
    }
  }

  public Optional<PlaybackSessionSnapshot> access(@NonNull PlaybackSessionRequest request) {
    return accessibleSession(request.playbackSessionId(), request.identity())
        .flatMap(this::snapshot);
  }

  public Optional<PlaybackSessionSnapshot> reportFormatFailure(
      @NonNull ReportPlaybackFormatFailureCommand command) {
    return accessibleSession(command.playbackSessionId(), command.identity())
        .flatMap(
            playback -> {
              recover(
                  playback,
                  command.streamSessionId(),
                  new PlaybackSessionFailure.FixedFormatFailed(command.reason()));
              return snapshot(playback);
            });
  }

  public void close(@NonNull PlaybackSessionRequest request) {
    accessibleSession(request.playbackSessionId(), request.identity()).ifPresent(this::discard);
  }

  private Optional<PlaybackSession> accessibleSession(
      UUID playbackSessionId, AuthenticatedIdentity identity) {
    var playback = sessions.get(playbackSessionId);
    if (playback == null) {
      return Optional.empty();
    }

    if (identity.profileId() == null) {
      throw new AuthenticationRequiredException();
    }

    var owner = playback.command.identity().playbackAuthority();
    if (!identity.playbackAuthority().equals(owner)) {
      return Optional.empty();
    }

    requireAuthority(identity, owner);
    return Optional.of(playback);
  }

  /** Rolls back an unpublished start, including any format attempt already reserved. */
  public void abandonStart(@NonNull UUID playbackSessionId) {
    var playback = sessions.get(playbackSessionId);
    if (playback == null) {
      return;
    }

    discard(playback);
  }

  @Override
  public void close() {
    try {
      closeAll();
    } finally {
      ownedExecutor.ifPresent(ExecutorService::close);
    }
  }

  public void closeOwningStream(@NonNull UUID streamSessionId) {
    var playback = streamOwners.get(streamSessionId);
    if (playback == null) {
      return;
    }

    destroyChildren(
        removeIf(playback, current -> current.current.streamSessionId().equals(streamSessionId)));
  }

  public void closeMediaFiles(@NonNull Set<UUID> mediaFileIds) {
    discardMatching(playback -> mediaFileIds.contains(playback.command.mediaFileId()));
  }

  public void reapExpired(@NonNull Instant now, @NonNull Duration retention) {
    discardMatching(playback -> lastActivity(playback).plus(retention).isBefore(now));
  }

  public void closeAll() {
    synchronized (registration) {
      closed = true;
    }

    discardMatching(_ -> true);
  }

  private Instant lastActivity(PlaybackSession playback) {
    if (playback.current instanceof PlaybackFormatAttempt.Ready(var session)
        && session.getLastAccessedAt().isAfter(playback.lastAccessedAt)) {
      return session.getLastAccessedAt();
    }

    return playback.lastAccessedAt;
  }

  @EventListener
  public void onVideoCopyOutputMismatch(@NonNull VideoCopyOutputMismatchEvent event) {
    recoverFromFailedCopy(event.streamSessionId());
  }

  /** A copy that ran short while a source stream still has media fails as a copy does. */
  @EventListener
  public void onCopiedVariantRanShort(@NonNull CopiedVariantRanShortEvent event) {
    recoverFromFailedCopy(event.streamSessionId());
  }

  // Every playback choice falls back from a failed copy to an encode, so the failure that this
  // names ends playback only when no encode follows.
  private void recoverFromFailedCopy(UUID failedId) {
    handleJobFailure(
        failedId,
        playback ->
            reserveFormatAttempt(playback, failedId, new PlaybackSessionFailure.OutputMismatch()));
  }

  /** Fails the playback session without another format attempt, which would fail the same way. */
  @EventListener
  public void onTerminalTranscodeFailure(@NonNull TerminalTranscodeFailureEvent event) {
    var failedId = event.streamSessionId();
    var reason =
        switch (event.failure()) {
          case OUTPUT_MISMATCH -> new PlaybackSessionFailure.OutputMismatch();
          case INVALID_SPECIFICATION -> new PlaybackSessionFailure.InvalidSpecification();
        };
    handleJobFailure(failedId, playback -> reserveFailure(playback, failedId, reason));
  }

  private void handleJobFailure(
      UUID failedId, Function<PlaybackSession, Optional<FormatAttemptReservation>> reserve) {
    var playback = streamOwners.get(failedId);
    if (playback == null) {
      return;
    }

    try {
      if (allowsRecovery(playback)) {
        locked(playback, () -> reserve.apply(playback)).ifPresent(this::schedule);
      }
    } catch (RuntimeException failure) {
      discardAfterFailure(playback, failure);
      log.warn("Playback recovery failed for session {}", playback.id, failure);
    }
  }

  private void recover(
      PlaybackSession playback, UUID failedId, PlaybackSessionFailure fixedFailure) {
    var reservation =
        locked(playback, () -> reserveFormatAttempt(playback, failedId, fixedFailure));
    reservation.ifPresent(this::schedule);
  }

  private Optional<FormatAttemptReservation> reserveFailure(
      PlaybackSession playback, UUID failedId, PlaybackSessionFailure reason) {
    if (!isCurrentAttempt(playback, failedId) || endedNormally(playback)) {
      return Optional.empty();
    }

    return Optional.of(failPlayback(playback, failedId, reason));
  }

  private Optional<FormatAttemptReservation> reserveFormatAttempt(
      PlaybackSession playback, UUID failedId, PlaybackSessionFailure fixedFailure) {
    if (!isCurrentAttempt(playback, failedId)
        || endedNormally(playback)
        || (fixedFailure instanceof PlaybackSessionFailure.FixedFormatFailed
            && playback.current instanceof PlaybackFormatAttempt.Pending)) {
      return Optional.empty();
    }

    var failed = failedAttempt(playback, fixedFailure);
    // A failed encode keeps its codec family out of every later format attempt.
    if (failed.output() instanceof FailedFormatAttempt.Encode(var codecFamily)) {
      playback.excludedEncodeCodecFamilies.add(codecFamily);
    }

    return switch (recoveryPolicy.after(playback.choice, failed, playback.formatAttemptCount)) {
      case NextFormatAttempt.Fail(var reason) ->
          Optional.of(failPlayback(playback, failedId, reason));
      case NextFormatAttempt.Encode(var choice) -> {
        playback.pendingAttemptCause = Optional.of(causeOf(failed));
        yield Optional.of(
            reserveNextFormatAttempt(
                playback,
                failedId,
                ReservedFormatAttempt.builder()
                    .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                    .choice(choice)
                    .choiceWhenEncoding(choice)));
      }
      case NextFormatAttempt.CopyOrEncode(var whenCopying, var whenEncoding) -> {
        playback.pendingAttemptCause = Optional.of(causeOf(failed));
        yield Optional.of(
            reserveNextFormatAttempt(
                playback,
                failedId,
                ReservedFormatAttempt.builder()
                    .videoFormatConstraint(VideoFormatConstraint.COPY_OR_ENCODE)
                    .choice(whenCopying)
                    .choiceWhenEncoding(whenEncoding)));
      }
      case NextFormatAttempt.CopyWithDolbyVisionStrippedOrEncode(
              var whenCopying,
              var whenEncoding) -> {
        playback.pendingAttemptCause = Optional.of(DecisionReason.DOLBY_VISION_COPY_FAILED);
        yield Optional.of(
            reserveNextFormatAttempt(
                playback,
                failedId,
                ReservedFormatAttempt.builder()
                    .videoFormatConstraint(
                        VideoFormatConstraint.COPY_WITH_DOLBY_VISION_STRIPPED_OR_ENCODE)
                    .choice(whenCopying)
                    .choiceWhenEncoding(whenEncoding)));
      }
    };
  }

  private static DecisionReason causeOf(FailedFormatAttempt failed) {
    return switch (failed.output()) {
      case FailedFormatAttempt.DirectPlay _ -> DecisionReason.DIRECT_PLAY_FAILED;
      case FailedFormatAttempt.Copy _, FailedFormatAttempt.DolbyVisionCopyOnAnHdrBase _ ->
          DecisionReason.COPY_FAILED;
      case FailedFormatAttempt.Encode _ -> DecisionReason.ENCODE_FAILED;
    };
  }

  // Once the player reports that playback moved from the start position in any format attempt, the
  // client seeks the replacement itself, so the replacement starts at the beginning of the media.
  private static void forgetTheStartPositionOncePlaybackMoved(PlaybackSession playback) {
    if (playback.readyAttempts.stream()
        .anyMatch(StreamSession::hasPlaybackMovedFromTheStartPosition)) {
      playback.startPosition = Optional.empty();
    }
  }

  // A format attempt fails before it is ready only through the worker's copy output mismatch,
  // which the worker reports only for a copy.
  private static FailedFormatAttempt failedAttempt(
      PlaybackSession playback, PlaybackSessionFailure reason) {
    if (!(playback.current instanceof PlaybackFormatAttempt.Ready(var session))) {
      return new FailedFormatAttempt(reason, new FailedFormatAttempt.Copy());
    }

    return new FailedFormatAttempt(reason, outputOf(session));
  }

  private static FailedFormatAttempt.Output outputOf(StreamSession session) {
    if (session.getDelivery() == StreamDelivery.DIRECT_PLAY) {
      return new FailedFormatAttempt.DirectPlay();
    }

    var decision = session.getTranscodeDecision();
    if (!decision.transcodeMode().copiesVideo()) {
      return new FailedFormatAttempt.Encode(decision.videoCodecFamily());
    }

    if (keepsDolbyVisionOnAnHdrBase(decision)) {
      return new FailedFormatAttempt.DolbyVisionCopyOnAnHdrBase();
    }

    return new FailedFormatAttempt.Copy();
  }

  // Profiles 5 and 10.0 have no compatible base, so their copy has no stripped copy to fall back
  // to.
  private static boolean keepsDolbyVisionOnAnHdrBase(TranscodeDecision decision) {
    return decision
        .videoDolbyVisionOutput()
        .filter(output -> output.profile().compatibleBase() != CompatibleBase.NONE)
        .isPresent();
  }

  private FormatAttemptReservation reserveNextFormatAttempt(
      PlaybackSession playback,
      UUID failedId,
      ReservedFormatAttempt.ReservedFormatAttemptBuilder attempt) {
    var childId = UUID.randomUUID();
    var next = attempt.streamId(childId).build();
    playback.choice = next.choice();
    playback.lastAccessedAt = clock.instant();
    playback.current = new PlaybackFormatAttempt.Pending(childId);
    playback.formatAttemptCount++;
    playback.streamIds.add(childId);
    streamOwners.remove(failedId, playback);
    streamOwners.put(childId, playback);
    return FormatAttemptReservation.builder()
        .playback(playback)
        .retiredStreamId(failedId)
        .next(Optional.of(next))
        .build();
  }

  private boolean isCurrentAttempt(PlaybackSession playback, UUID streamId) {
    return owns(playback)
        && playback.current.streamSessionId().equals(streamId)
        && !(playback.current instanceof PlaybackFormatAttempt.Failed);
  }

  // A copy that ran short after every source stream ended ends playback normally, which wins over
  // every failure in its stream session (ADR 0052).
  private static boolean endedNormally(PlaybackSession playback) {
    return playback.current instanceof PlaybackFormatAttempt.Ready(var session)
        && session.getNormalEnd().isPresent();
  }

  private FormatAttemptReservation failPlayback(
      PlaybackSession playback, UUID failedId, PlaybackSessionFailure reason) {
    playback.lastAccessedAt = clock.instant();
    playback.current = new PlaybackFormatAttempt.Failed(failedId, reason);
    streamOwners.remove(failedId, playback);
    return FormatAttemptReservation.builder()
        .playback(playback)
        .retiredStreamId(failedId)
        .next(Optional.empty())
        .build();
  }

  private void schedule(FormatAttemptReservation work) {
    try {
      executor.execute(() -> startFormatAttempt(work));
    } catch (RejectedExecutionException failure) {
      failFormatAttempt(work, failure, true);
    }
  }

  private void startFormatAttempt(FormatAttemptReservation work) {
    try {
      if (!allowsRecovery(work.playback())) {
        return;
      }

      streamingService.destroySession(work.retiredStreamId());
      var pendingAttempt =
          work.next().filter(attempt -> isPending(work.playback(), attempt.streamId()));
      if (pendingAttempt.isEmpty()) {
        return;
      }

      var next = pendingAttempt.orElseThrow();
      var streamId = next.streamId();
      var outcome =
          streamingService.createSession(
              childCommand(work.playback(), streamId)
                  .videoFormatConstraint(next.videoFormatConstraint())
                  .build());
      switch (outcome) {
        case Outcome.Accepted(var session) ->
            settle(work.playback(), session, next.choiceWhenEncoding());
        case Outcome.Rejected(var rejections) -> {
          failCurrent(
              work.playback(),
              streamId,
              new PlaybackSessionFailure.CreationRejected(rejections.getFirst()));
          streamingService.destroySession(streamId);
        }
      }
    } catch (RuntimeException failure) {
      failFormatAttempt(work, failure, false);
    }
  }

  private void failFormatAttempt(
      FormatAttemptReservation work, RuntimeException failure, boolean retireOld) {
    var nextId = work.next().map(ReservedFormatAttempt::streamId);
    nextId.ifPresent(
        id -> failCurrent(work.playback(), id, new PlaybackSessionFailure.StartupFailed()));
    var children = new HashSet<UUID>();
    nextId.ifPresent(children::add);
    if (retireOld) {
      children.add(work.retiredStreamId());
    }

    try {
      destroyChildren(children);
    } catch (RuntimeException cleanupFailure) {
      suppress(failure, cleanupFailure);
    }

    log.warn("Format attempt failed for playback session {}", work.playback().id, failure);
  }

  private void failCurrent(PlaybackSession playback, UUID childId, PlaybackSessionFailure reason) {
    locked(
        playback,
        () -> {
          if (pending(playback, childId)) {
            playback.current = new PlaybackFormatAttempt.Failed(childId, reason);
            streamOwners.remove(childId, playback);
          }

          return true;
        });
  }

  private CreateStreamSessionCommand.CreateStreamSessionCommandBuilder childCommand(
      PlaybackSession playback, UUID streamId) {
    return locked(
        playback,
        () -> {
          // The retired format attempt can take a report until it is destroyed, just before this.
          forgetTheStartPositionOncePlaybackMoved(playback);
          return CreateStreamSessionCommand.builder()
              .streamSessionId(streamId)
              .mediaFileId(playback.command.mediaFileId())
              .identity(playback.command.identity())
              .options(
                  playback.command.options().toBuilder()
                      .quality(playback.choice.playbackChoice())
                      .build())
              .capabilityDeclaration(playback.command.capabilityDeclaration())
              .excludedEncodeCodecFamilies(Set.copyOf(playback.excludedEncodeCodecFamilies))
              .startPosition(playback.startPosition);
        });
  }

  private void requireAuthority(AuthenticatedIdentity identity, PlaybackAuthority authority) {
    if (identity.profileId() == null
        || !identity.playbackAuthority().equals(authority)
        || !authorityGate.allows(identity, authority)) {
      throw new AuthenticationRequiredException();
    }
  }

  private boolean allowsRecovery(PlaybackSession playback) {
    boolean owned = locked(playback, () -> owns(playback));
    if (!owned) {
      return false;
    }

    try {
      if (authorityGate.allows(
          playback.command.identity(), playback.command.identity().playbackAuthority())) {
        return true;
      }

      discard(playback);
      return false;
    } catch (RuntimeException failure) {
      discardAfterFailure(playback, failure);
      throw failure;
    }
  }

  private void discard(PlaybackSession playback) {
    destroyChildren(removeIf(playback, _ -> true));
  }

  private void discardAfterFailure(PlaybackSession playback, RuntimeException failure) {
    try {
      discard(playback);
    } catch (RuntimeException cleanupFailure) {
      suppress(failure, cleanupFailure);
    }
  }

  private void discardMatching(Predicate<PlaybackSession> condition) {
    var children = new HashSet<UUID>();
    for (var playback : List.copyOf(sessions.values())) {
      children.addAll(removeIf(playback, condition));
    }

    destroyChildren(children);
  }

  private Set<UUID> removeIf(PlaybackSession playback, Predicate<PlaybackSession> condition) {
    return locked(
        playback,
        () -> {
          if (!owns(playback) || !condition.test(playback)) {
            return Set.of();
          }

          sessions.remove(playback.id, playback);
          playback.streamIds.forEach(id -> streamOwners.remove(id, playback));
          return Set.copyOf(playback.streamIds);
        });
  }

  private void destroyChildren(Set<UUID> children) {
    RuntimeException failure = null;
    for (var id : children) {
      try {
        streamingService.destroySession(id);
      } catch (RuntimeException cleanupFailure) {
        if (failure == null) {
          failure = cleanupFailure;
          continue;
        }

        suppress(failure, cleanupFailure);
      }
    }

    if (failure != null) {
      throw failure;
    }
  }

  private static void suppress(RuntimeException failure, RuntimeException cleanupFailure) {
    if (failure != cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private void settle(
      PlaybackSession playback, StreamSession session, PlayingChoice choiceWhenEncoding) {
    boolean accepted =
        locked(
            playback,
            () -> {
              if (!pending(playback, session.getSessionId())) {
                return false;
              }

              adopt(playback, session, choiceWhenEncoding);
              return true;
            });
    if (!accepted) {
      streamingService.destroySession(session.getSessionId());
    }
  }

  private static void adopt(
      PlaybackSession playback, StreamSession session, PlayingChoice choiceWhenEncoding) {
    playback.current = new PlaybackFormatAttempt.Ready(session);
    playback.readyAttempts.add(session);
    playback.decisions.add(
        PlaybackDecision.of(playback.formatAttemptCount, session, playback.pendingAttemptCause));
    // A direct-play stream session sends the source as it is and holds no transcode decision.
    if (session.getDelivery() == StreamDelivery.HLS
        && !session.getTranscodeDecision().transcodeMode().copiesVideo()) {
      playback.choice = choiceWhenEncoding;
    }
  }

  // Original that reaches an encode plays it in Auto (ADR 0052).
  private static PlayingChoice firstEncodeChoice(VideoQuality requested) {
    if (requested != VideoQuality.ORIGINAL) {
      return PlayingChoice.requested(requested);
    }

    return new PlayingChoice(
        VideoQuality.AUTO, Optional.of(PlaybackFormatChange.STARTED_WITH_ENCODE));
  }

  private boolean isPending(PlaybackSession playback, UUID childId) {
    return locked(playback, () -> pending(playback, childId));
  }

  private boolean pending(PlaybackSession playback, UUID childId) {
    return owns(playback)
        && playback.current instanceof PlaybackFormatAttempt.Pending(var currentId)
        && currentId.equals(childId);
  }

  private boolean owns(PlaybackSession playback) {
    return sessions.get(playback.id) == playback;
  }

  private Optional<PlaybackSessionSnapshot> snapshot(PlaybackSession playback) {
    return locked(
        playback,
        () -> {
          if (!owns(playback)) {
            return Optional.empty();
          }

          playback.lastAccessedAt = clock.instant();
          return Optional.of(
              PlaybackSessionSnapshot.builder()
                  .playbackSessionId(playback.id)
                  .formatAttemptCount(playback.formatAttemptCount)
                  .maximumFormatAttempts(recoveryPolicy.maximumFormatAttempts())
                  .requestedChoice(playback.requestedChoice)
                  .playbackChoice(playback.choice.playbackChoice())
                  .formatChange(playback.choice.formatChange())
                  .offeredChoices(playback.offeredChoices)
                  .currentFormatAttempt(playback.current)
                  .decisions(List.copyOf(playback.decisions))
                  .build());
        });
  }

  private <T> T locked(PlaybackSession playback, Supplier<T> action) {
    var lock = mutexes.getMutex(playback.id);
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }
}
