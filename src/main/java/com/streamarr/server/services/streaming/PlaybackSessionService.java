package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.EncodeFormat;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.PlaybackSessionClosedException;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.events.watchprogress.StreamSessionTimelineReportedEvent;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.events.CopiedVariantRanShortEvent;
import com.streamarr.server.services.streaming.events.DolbyVisionConversionFailedEvent;
import com.streamarr.server.services.streaming.events.MediaSegmentStoredEvent;
import com.streamarr.server.services.streaming.events.PlaybackSessionChangedEvent;
import com.streamarr.server.services.streaming.events.ProducedRangeMayHaveMovedEvent;
import com.streamarr.server.services.streaming.events.RequestedVariantChangedEvent;
import com.streamarr.server.services.streaming.events.RequestedVariantHardwareEncoderFailedEvent;
import com.streamarr.server.services.streaming.events.TerminalTranscodeFailureEvent;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

@Slf4j
public class PlaybackSessionService implements AutoCloseable {
  // Original that reaches an encode plays it in Auto (ADR 0052).
  private static final PlayingChoice AUTO_AFTER_AN_AUDIO_TRACK_SWITCH =
      new PlayingChoice(VideoQuality.AUTO, Optional.of(PlaybackFormatChange.AUDIO_TRACK_SWITCHED));
  private final StreamingService streamingService;
  private final PlaybackAuthorityGate authorityGate;
  private final Executor executor;
  private final Optional<ExecutorService> ownedExecutor;
  private final FormatRecoveryPolicy recoveryPolicy;
  private final Clock clock;
  private final ApplicationEventPublisher eventPublisher;
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
      Clock clock,
      @NonNull ApplicationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
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
    // The audio track that the viewer switched to during direct play, which every later format
    // attempt plays.
    @Builder.Default private OptionalInt audioTrackPosition = OptionalInt.empty();
    // The HLS stream session that an audio switch starts while direct play stays current.
    @Builder.Default private Optional<PendingAudioTrackSwitch> audioTrackSwitch = Optional.empty();
    @Builder.Default private List<VideoQuality> offeredChoices = List.of();
    // The format attempts that count against the budget. An audio switch's format attempt does
    // not (ADR 0043), nor does a software continuation after a hardware encoder failure (ADR 0044),
    // so it differs from startedFormatAttempts, which numbers each decision.
    @Builder.Default private int formatAttemptCount = 1;
    @Builder.Default private int startedFormatAttempts = 1;
    private final Set<String> excludedEncodeCodecFamilies = new HashSet<>();
    private final Set<EncodeFormat> excludedEncodeFormats = new HashSet<>();
    private final Set<UUID> streamIds = new HashSet<>();
    // A retired format attempt can still take a timeline report until its stream session ends.
    private final List<StreamSession> readyAttempts = new ArrayList<>();
    private final List<PlaybackDecision> decisions = new ArrayList<>();
    // The reason that the format attempt before the pending one gives it: its failure, or an audio
    // switch that its player could not make in direct play.
    @Builder.Default private Optional<DecisionReason> pendingAttemptCause = Optional.empty();
  }

  @Builder
  private record FormatAttemptReservation(
      PlaybackSession playback, UUID retiredStreamId, Optional<ReservedFormatAttempt> next) {}

  /**
   * The format attempt that a reservation starts: its stream session and the video operations it
   * may use. It plays {@code choice}, or {@code choiceWhenEncoding} once it turns out to encode the
   * video. {@code softwareContinuation} names the encode that it continues in software after a
   * hardware encoder failure.
   */
  @Builder
  private record ReservedFormatAttempt(
      @NonNull UUID streamId,
      @NonNull VideoFormatConstraint videoFormatConstraint,
      @NonNull PlayingChoice choice,
      @NonNull PlayingChoice choiceWhenEncoding,
      @NonNull Optional<SoftwareContinuation> softwareContinuation) {

    // Lombok builder defaults — its generated setters assign them
    @SuppressWarnings("java:S9438")
    private static class ReservedFormatAttemptBuilder {
      private Optional<SoftwareContinuation> softwareContinuation = Optional.empty();
    }
  }

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

  /**
   * Moves a playback session that direct-plays the source to HLS for the audio track that the
   * viewer chose, as a player that cannot select an audio track in a directly played file needs
   * (ADR 0045). The HLS format attempt is the one that follows a failed direct play: a copy keeps
   * Original without a format change, and an encode plays in Auto with {@code
   * AUDIO_TRACK_SWITCHED}. It spends no format attempt (ADR 0043), and every later format attempt
   * plays the chosen track. A switch is the viewer's choice, not a failure, so direct play stops
   * only once the HLS stream session has started. When it does not start or fails first, direct
   * play goes on and the outcome says why. The playback session stays as it is when the stream
   * session is not its current direct-play format attempt, or when that attempt already plays the
   * chosen track.
   */
  public Outcome<PlaybackSessionSnapshot, AudioTrackSwitchRejection> switchAudioTrack(
      @NonNull SwitchPlaybackAudioTrackCommand command) {
    var accessible = accessibleSession(command.playbackSessionId(), command.identity());
    if (accessible.isEmpty()) {
      return Outcome.rejected(new AudioTrackSwitchRejection.PlaybackSessionClosed());
    }

    var playback = accessible.get();
    return switch (locked(playback, () -> reserveAudioTrackSwitch(playback, command))) {
      case AudioTrackSwitch.Unchanged() -> currentSnapshot(playback);
      case AudioTrackSwitch.Refused(var rejection) -> Outcome.rejected(rejection);
      case AudioTrackSwitch.Reserved(var pending) -> startAudioTrackSwitch(playback, pending);
    };
  }

  /**
   * The HLS stream session {@code streamId} that an audio switch starts for the track at {@code
   * position}, to replace the direct-play stream session {@code directPlayId}. {@code startFailed}
   * records a worker failure of that stream session, and {@code directPlayFailure} a format failure
   * of direct play, before the switch completes.
   */
  @Builder(toBuilder = true)
  private record PendingAudioTrackSwitch(
      UUID streamId,
      UUID directPlayId,
      int position,
      boolean startFailed,
      Optional<PlaybackSessionFailure> directPlayFailure) {

    PendingAudioTrackSwitch withStartFailed() {
      return toBuilder().startFailed(true).build();
    }

    PendingAudioTrackSwitch withDirectPlayFailure(PlaybackSessionFailure failure) {
      return toBuilder().directPlayFailure(Optional.of(failure)).build();
    }
  }

  /** What a switch request does to the playback session, decided under its lock. */
  private sealed interface AudioTrackSwitch {
    record Unchanged() implements AudioTrackSwitch {}

    record Refused(AudioTrackSwitchRejection rejection) implements AudioTrackSwitch {}

    record Reserved(PendingAudioTrackSwitch pending) implements AudioTrackSwitch {}
  }

  // Direct play stays the current format attempt until the switch completes, so the reaper and a
  // closing client act on it as on any direct play.
  private AudioTrackSwitch reserveAudioTrackSwitch(
      PlaybackSession playback, SwitchPlaybackAudioTrackCommand command) {
    var directPlay = currentDirectPlay(playback, command.streamSessionId());
    if (directPlay.isEmpty() || playback.audioTrackSwitch.isPresent()) {
      return new AudioTrackSwitch.Unchanged();
    }

    var session = directPlay.get();
    var position = command.audioTrackPosition();
    if (position < 0 || position >= session.getMediaProbe().audioStreams().size()) {
      return new AudioTrackSwitch.Refused(new AudioTrackSwitchRejection.AudioTrackNotInFile());
    }

    if (session.getDirectPlayAudioTrackPosition().equals(OptionalInt.of(position))) {
      return new AudioTrackSwitch.Unchanged();
    }

    var pending =
        PendingAudioTrackSwitch.builder()
            .streamId(UUID.randomUUID())
            .directPlayId(session.getSessionId())
            .position(position)
            .directPlayFailure(Optional.empty())
            .build();
    playback.audioTrackSwitch = Optional.of(pending);
    playback.streamIds.add(pending.streamId());
    streamOwners.put(pending.streamId(), playback);
    return new AudioTrackSwitch.Reserved(pending);
  }

  private Outcome<PlaybackSessionSnapshot, AudioTrackSwitchRejection> startAudioTrackSwitch(
      PlaybackSession playback, PendingAudioTrackSwitch pending) {
    var outcome =
        abandoningTheSwitchIfItThrows(
            playback,
            pending,
            () ->
                streamingService.createSession(
                    childCommand(playback, pending.streamId())
                        .videoFormatConstraint(VideoFormatConstraint.COPY_OR_ENCODE)
                        .audioTrackPosition(OptionalInt.of(pending.position()))
                        .build()));
    return switch (outcome) {
      case Outcome.Accepted(var session) -> completeAudioTrackSwitch(playback, session);
      case Outcome.Rejected(var rejections) ->
          afterFailedAudioTrackSwitch(
              playback,
              abandonAudioTrackSwitch(playback, pending.streamId()),
              new AudioTrackSwitchRejection.StreamSessionRefused(rejections.getFirst()));
    };
  }

  private <T> T abandoningTheSwitchIfItThrows(
      PlaybackSession playback, PendingAudioTrackSwitch pending, Supplier<T> start) {
    try {
      return start.get();
    } catch (RuntimeException failure) {
      try {
        abandonAudioTrackSwitch(playback, pending.streamId())
            .ifPresent(ended -> recoverDeferredDirectPlayFailure(playback, ended));
      } catch (RuntimeException cleanupFailure) {
        suppress(failure, cleanupFailure);
      }

      throw failure;
    }
  }

  private Outcome<PlaybackSessionSnapshot, AudioTrackSwitchRejection> completeAudioTrackSwitch(
      PlaybackSession playback, StreamSession session) {
    var ended = locked(playback, () -> endAudioTrackSwitch(playback, session));
    if (ended.isEmpty()) {
      streamingService.destroySession(session.getSessionId());
      return currentSnapshot(playback);
    }

    if (ended.get().startFailed()) {
      streamingService.destroySession(session.getSessionId());
      return afterFailedAudioTrackSwitch(
          playback, ended, new AudioTrackSwitchRejection.StreamSessionFailed());
    }

    streamingService.destroySession(ended.get().directPlayId());
    changed(playback);
    return currentSnapshot(playback);
  }

  // Ending the switch and adopting its stream session share one critical section, so no worker
  // failure or second switch can fall between them.
  private Optional<PendingAudioTrackSwitch> endAudioTrackSwitch(
      PlaybackSession playback, StreamSession session) {
    var ended = takeAudioTrackSwitch(playback, session.getSessionId());
    if (ended.isEmpty()) {
      return ended;
    }

    if (ended.get().startFailed()) {
      forgetStream(playback, session.getSessionId());
      return ended;
    }

    playback.audioTrackPosition = OptionalInt.of(ended.get().position());
    playback.pendingAttemptCause =
        Optional.of(DecisionReason.DIRECT_PLAY_AUDIO_TRACK_NOT_SELECTABLE);
    playback.startedFormatAttempts++;
    playback.lastAccessedAt = clock.instant();
    streamOwners.remove(ended.get().directPlayId(), playback);
    adopt(playback, session, AUTO_AFTER_AN_AUDIO_TRACK_SWITCH);
    return ended;
  }

  // The switch ends here unless the playback session was discarded meanwhile. When direct play
  // failed during the switch, every recovery from now on plays the chosen track.
  private Optional<PendingAudioTrackSwitch> takeAudioTrackSwitch(
      PlaybackSession playback, UUID streamId) {
    var pending = playback.audioTrackSwitch.filter(current -> current.streamId().equals(streamId));
    if (!owns(playback) || pending.isEmpty()) {
      return Optional.empty();
    }

    playback.audioTrackSwitch = Optional.empty();
    if (pending.get().directPlayFailure().isPresent()) {
      playback.audioTrackPosition = OptionalInt.of(pending.get().position());
    }

    return pending;
  }

  private Optional<PendingAudioTrackSwitch> abandonAudioTrackSwitch(
      PlaybackSession playback, UUID streamId) {
    var ended =
        locked(
            playback,
            () -> {
              forgetStream(playback, streamId);
              return takeAudioTrackSwitch(playback, streamId);
            });
    streamingService.destroySession(streamId);
    return ended;
  }

  // A switch that does not complete leaves direct play current. A direct-play failure that the
  // switch held back now recovers as it would have, with the chosen track.
  private Outcome<PlaybackSessionSnapshot, AudioTrackSwitchRejection> afterFailedAudioTrackSwitch(
      PlaybackSession playback,
      Optional<PendingAudioTrackSwitch> ended,
      AudioTrackSwitchRejection rejection) {
    if (ended.flatMap(PendingAudioTrackSwitch::directPlayFailure).isEmpty()) {
      return Outcome.rejected(rejection);
    }

    recoverDeferredDirectPlayFailure(playback, ended.get());
    return currentSnapshot(playback);
  }

  private void recoverDeferredDirectPlayFailure(
      PlaybackSession playback, PendingAudioTrackSwitch ended) {
    ended
        .directPlayFailure()
        .ifPresent(failure -> recover(playback, ended.directPlayId(), failure));
  }

  private void forgetStream(PlaybackSession playback, UUID streamId) {
    playback.streamIds.remove(streamId);
    streamOwners.remove(streamId, playback);
  }

  // A worker failure of the stream session that an audio switch starts fails only the switch,
  // because direct play still plays.
  private boolean failPendingAudioTrackSwitch(PlaybackSession playback, UUID failedId) {
    var pending = playback.audioTrackSwitch.filter(current -> current.streamId().equals(failedId));
    if (pending.isEmpty()) {
      return false;
    }

    playback.audioTrackSwitch = Optional.of(pending.get().withStartFailed());
    return true;
  }

  // A format failure of direct play during a switch waits for the switch: its stream session
  // already moves to HLS, and a second one could take the place that it needs. The failure
  // recovers only when the switch does not complete.
  private boolean deferToAudioTrackSwitch(
      PlaybackSession playback, UUID failedId, PlaybackSessionFailure failure) {
    var pending = playback.audioTrackSwitch;
    if (pending.isEmpty() || !isCurrentAttempt(playback, failedId)) {
      return false;
    }

    playback.audioTrackSwitch = Optional.of(pending.get().withDirectPlayFailure(failure));
    return true;
  }

  private Outcome<PlaybackSessionSnapshot, AudioTrackSwitchRejection> currentSnapshot(
      PlaybackSession playback) {
    return snapshot(playback)
        .<Outcome<PlaybackSessionSnapshot, AudioTrackSwitchRejection>>map(Outcome::accepted)
        .orElseGet(() -> Outcome.rejected(new AudioTrackSwitchRejection.PlaybackSessionClosed()));
  }

  private Optional<StreamSession> currentDirectPlay(PlaybackSession playback, UUID streamId) {
    if (!isCurrentAttempt(playback, streamId)) {
      return Optional.empty();
    }

    if (playback.current instanceof PlaybackFormatAttempt.Ready(var session)
        && session.getDelivery() == StreamDelivery.DIRECT_PLAY) {
      return Optional.of(session);
    }

    return Optional.empty();
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
    recoverFromFailedCopy(event.streamSessionId(), new PlaybackSessionFailure.OutputMismatch());
  }

  /** A copy that ran short while a source stream still has media fails as a copy does. */
  @EventListener
  public void onCopiedVariantRanShort(@NonNull CopiedVariantRanShortEvent event) {
    recoverFromFailedCopy(event.streamSessionId(), new PlaybackSessionFailure.OutputMismatch());
  }

  /**
   * A Dolby Vision copy whose conversion the worker cannot finish falls back to the copy with Dolby
   * Vision stripped, as one that the client rejects does (streamarr/streamarr-server#651, ADR
   * 0052).
   */
  @EventListener
  public void onDolbyVisionConversionFailed(@NonNull DolbyVisionConversionFailedEvent event) {
    recoverFromFailedCopy(
        event.streamSessionId(), new PlaybackSessionFailure.DolbyVisionConversionFailed());
  }

  // Every playback choice falls back from a failed copy to another format attempt, so the failure
  // that this names ends playback only when none follows.
  private void recoverFromFailedCopy(UUID failedId, PlaybackSessionFailure reason) {
    handleJobFailure(failedId, playback -> reserveFormatAttempt(playback, failedId, reason));
  }

  /**
   * Fails the playback session without another format attempt, which would fail the same way. A
   * segment over the cap on the segment list starts a new format attempt instead, as a failed copy
   * does, because that attempt's stream session encodes on the plain grid (ADR 0037).
   */
  @EventListener
  public void onTerminalTranscodeFailure(@NonNull TerminalTranscodeFailureEvent event) {
    var failedId = event.streamSessionId();
    switch (event.failure()) {
      case OUTPUT_MISMATCH ->
          failWithoutFormatAttempt(failedId, new PlaybackSessionFailure.OutputMismatch());
      case INVALID_SPECIFICATION ->
          failWithoutFormatAttempt(failedId, new PlaybackSessionFailure.InvalidSpecification());
      case DOLBY_VISION_CONVERSION_FAILED ->
          failWithoutFormatAttempt(
              failedId, new PlaybackSessionFailure.DolbyVisionConversionFailed());
      case SEGMENT_CAP_EXCEEDED ->
          recoverFromFailedCopy(failedId, new PlaybackSessionFailure.OutputMismatch());
    }
  }

  private void failWithoutFormatAttempt(UUID failedId, PlaybackSessionFailure reason) {
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
        locked(playback, () -> reserveAfterJobFailure(playback, failedId, reserve))
            .ifPresent(this::scheduleAfterChange);
      }
    } catch (RuntimeException failure) {
      discardAfterFailure(playback, failure);
      log.warn("Playback recovery failed for session {}", playback.id, failure);
    }
  }

  private Optional<FormatAttemptReservation> reserveAfterJobFailure(
      PlaybackSession playback,
      UUID failedId,
      Function<PlaybackSession, Optional<FormatAttemptReservation>> reserve) {
    if (failPendingAudioTrackSwitch(playback, failedId)) {
      return Optional.empty();
    }

    return reserve.apply(playback);
  }

  private void recover(
      PlaybackSession playback, UUID failedId, PlaybackSessionFailure fixedFailure) {
    var reservation = locked(playback, () -> reserveRecovery(playback, failedId, fixedFailure));
    reservation.ifPresent(this::scheduleAfterChange);
  }

  private Optional<FormatAttemptReservation> reserveRecovery(
      PlaybackSession playback, UUID failedId, PlaybackSessionFailure fixedFailure) {
    if (deferToAudioTrackSwitch(playback, failedId, fixedFailure)) {
      return Optional.empty();
    }

    return reserveFormatAttempt(playback, failedId, fixedFailure);
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

    return Optional.of(
        reserveAfterFailedAttempt(playback, failedId, failedAttempt(playback, fixedFailure)));
  }

  private FormatAttemptReservation reserveAfterFailedAttempt(
      PlaybackSession playback, UUID failedId, FailedFormatAttempt failed) {
    if (failed.output() instanceof FailedFormatAttempt.Encode(var codecFamily, var format)) {
      excludeFailedEncode(playback, codecFamily, format);
    }

    return switch (recoveryPolicy.after(playback.choice, failed, playback.formatAttemptCount)) {
      case NextFormatAttempt.Fail(var reason) -> failPlayback(playback, failedId, reason);
      case NextFormatAttempt.Encode(var choice) -> {
        playback.pendingAttemptCause = Optional.of(causeOf(failed));
        yield reserveNextFormatAttempt(
            playback,
            failedId,
            ReservedFormatAttempt.builder()
                .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                .choice(choice)
                .choiceWhenEncoding(choice));
      }
      case NextFormatAttempt.CopyOrEncode(var whenCopying, var whenEncoding) -> {
        playback.pendingAttemptCause = Optional.of(causeOf(failed));
        yield reserveNextFormatAttempt(
            playback,
            failedId,
            ReservedFormatAttempt.builder()
                .videoFormatConstraint(VideoFormatConstraint.COPY_OR_ENCODE)
                .choice(whenCopying)
                .choiceWhenEncoding(whenEncoding));
      }
      case NextFormatAttempt.CopyWithDolbyVisionStrippedOrEncode(
              var whenCopying,
              var whenEncoding) -> {
        playback.pendingAttemptCause = Optional.of(DecisionReason.DOLBY_VISION_COPY_FAILED);
        yield reserveNextFormatAttempt(
            playback,
            failedId,
            ReservedFormatAttempt.builder()
                .videoFormatConstraint(
                    VideoFormatConstraint.COPY_WITH_DOLBY_VISION_STRIPPED_OR_ENCODE)
                .choice(whenCopying)
                .choiceWhenEncoding(whenEncoding));
      }
    };
  }

  /**
   * Continues the requested variant's encode on the software encoder of the same codec family in a
   * new format attempt, which the client reopens at the same position (ADR 0044). It spends no
   * format attempt, and no worker with room for it ends the playback session. The encode keeps its
   * range and bit depth. When no software encoder on a worker with room writes them, the encode has
   * failed as after a format error: Auto gives the range or the 10 bits up within its budget, and a
   * fixed quality ends playback (ADR 0013).
   */
  @EventListener
  public void onRequestedVariantHardwareEncoderFailed(
      @NonNull RequestedVariantHardwareEncoderFailedEvent event) {
    var failedId = event.streamSessionId();
    handleJobFailure(failedId, playback -> reserveSoftwareContinuation(playback, failedId));
  }

  private Optional<FormatAttemptReservation> reserveSoftwareContinuation(
      PlaybackSession playback, UUID failedId) {
    if (!isCurrentAttempt(playback, failedId)
        || endedNormally(playback)
        || !(playback.current instanceof PlaybackFormatAttempt.Ready(var session))
        || !(outputOf(session)
            instanceof FailedFormatAttempt.Encode(var codecFamily, var format))) {
      return Optional.empty();
    }

    playback.pendingAttemptCause = Optional.of(DecisionReason.HARDWARE_ENCODER_FAILED);
    return Optional.of(
        reserveNextFormatAttempt(
            playback,
            failedId,
            ReservedFormatAttempt.builder()
                .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                .choice(playback.choice)
                .choiceWhenEncoding(playback.choice)
                .softwareContinuation(Optional.of(new SoftwareContinuation(codecFamily, format)))));
  }

  // A failed HDR or 10-bit encode keeps its format out of every later format attempt, in every
  // codec family, so Auto gives HDR up for SDR (#397) and can still try the codec family in 8-bit
  // SDR. A failed 8-bit SDR encode keeps its codec family out.
  private static void excludeFailedEncode(
      PlaybackSession playback, String codecFamily, EncodeFormat format) {
    if (format.equals(EncodeFormat.SDR_8_BIT)) {
      playback.excludedEncodeCodecFamilies.add(codecFamily);
      return;
    }

    playback.excludedEncodeFormats.add(format);
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

  // A format attempt fails before it is ready only through a worker's report about a copy. The
  // worker reports a Dolby Vision conversion failure only from a copy that converts profile 7 on a
  // PQ base (streamarr/streamarr-server#651), so that report names a Dolby Vision copy on an HDR
  // base; its output mismatch names a copy.
  private static FailedFormatAttempt failedAttempt(
      PlaybackSession playback, PlaybackSessionFailure reason) {
    if (playback.current instanceof PlaybackFormatAttempt.Ready(var session)) {
      return new FailedFormatAttempt(reason, outputOf(session));
    }

    return new FailedFormatAttempt(reason, outputOfAPendingCopy(reason));
  }

  private static FailedFormatAttempt.Output outputOfAPendingCopy(PlaybackSessionFailure reason) {
    if (reason instanceof PlaybackSessionFailure.DolbyVisionConversionFailed) {
      return new FailedFormatAttempt.DolbyVisionCopyOnAnHdrBase();
    }

    return new FailedFormatAttempt.Copy();
  }

  private static FailedFormatAttempt.Output outputOf(StreamSession session) {
    if (session.getDelivery() == StreamDelivery.DIRECT_PLAY) {
      return new FailedFormatAttempt.DirectPlay();
    }

    var decision = session.getTranscodeDecision();
    if (!decision.transcodeMode().copiesVideo()) {
      return new FailedFormatAttempt.Encode(
          decision.videoCodecFamily(), decision.videoEncodeFormat());
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
    if (next.softwareContinuation().isEmpty()) {
      playback.formatAttemptCount++;
    }

    playback.startedFormatAttempts++;
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

  // The reservation changed the current format attempt, so listeners hear of it before the next
  // format attempt starts.
  private void scheduleAfterChange(FormatAttemptReservation work) {
    changed(work.playback());
    schedule(work);
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
                  .softwareContinuation(next.softwareContinuation())
                  .build());
      switch (outcome) {
        case Outcome.Accepted(var session) ->
            settle(work.playback(), session, next.choiceWhenEncoding());
        case Outcome.Rejected(var rejections) ->
            failOrRecoverRejectedAttempt(
                work.playback(),
                next,
                new PlaybackSessionFailure.CreationRejected(rejections.getFirst()));
      }
    } catch (RuntimeException failure) {
      failFormatAttempt(work, failure, false);
    }
  }

  private void failOrRecoverRejectedAttempt(
      PlaybackSession playback,
      ReservedFormatAttempt attempt,
      PlaybackSessionFailure.CreationRejected rejection) {
    var afterAFailedEncode =
        locked(playback, () -> reserveAfterContinuationRejected(playback, attempt, rejection));
    if (afterAFailedEncode.isPresent()) {
      scheduleAfterChange(afterAFailedEncode.get());
      return;
    }

    failCurrent(playback, attempt.streamId(), rejection);
    streamingService.destroySession(attempt.streamId());
  }

  // A software continuation of a PQ, HLG or 10-bit SDR encode that could not start never continues
  // in another range or bit depth. Its encode has failed, so what follows is what follows a format
  // error during that encode. The continuation made no decision, so a format attempt that follows
  // takes its number. An 8-bit SDR encode has no range to give up, so its rejected continuation
  // ends playback (ADR 0044).
  private Optional<FormatAttemptReservation> reserveAfterContinuationRejected(
      PlaybackSession playback,
      ReservedFormatAttempt attempt,
      PlaybackSessionFailure.CreationRejected rejection) {
    var continuation =
        attempt
            .softwareContinuation()
            .filter(encode -> !encode.format().equals(EncodeFormat.SDR_8_BIT));
    if (continuation.isEmpty() || !pending(playback, attempt.streamId())) {
      return Optional.empty();
    }

    var failedEncode =
        new FailedFormatAttempt.Encode(
            continuation.get().codecFamily(), continuation.get().format());
    playback.startedFormatAttempts--;
    return Optional.of(
        reserveAfterFailedAttempt(
            playback, attempt.streamId(), new FailedFormatAttempt(rejection, failedEncode)));
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
    boolean failed =
        locked(
            playback,
            () -> {
              if (!pending(playback, childId)) {
                return false;
              }

              playback.current = new PlaybackFormatAttempt.Failed(childId, reason);
              streamOwners.remove(childId, playback);
              return true;
            });
    if (failed) {
      changed(playback);
    }
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
              .excludedEncodeFormats(Set.copyOf(playback.excludedEncodeFormats))
              .startPosition(playback.startPosition)
              .audioTrackPosition(playback.audioTrackPosition);
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
    var removed = removeLockedIf(playback, condition);
    if (!removed.isEmpty()) {
      changed(playback);
    }

    return removed;
  }

  private Set<UUID> removeLockedIf(PlaybackSession playback, Predicate<PlaybackSession> condition) {
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
      return;
    }

    changed(playback);
  }

  private void changed(PlaybackSession playback) {
    eventPublisher.publishEvent(new PlaybackSessionChangedEvent(playback.id));
  }

  /** The activity of every open playback session. Reading it keeps no playback session open. */
  public List<PlaybackSessionActivity> activity() {
    return sessions.values().stream().map(this::activityOf).flatMap(Optional::stream).toList();
  }

  /** The activity of the playback session, or empty once it ended. */
  public Optional<PlaybackSessionActivity> activity(@NonNull UUID playbackSessionId) {
    return Optional.ofNullable(sessions.get(playbackSessionId)).flatMap(this::activityOf);
  }

  private Optional<PlaybackSessionActivity> activityOf(PlaybackSession playback) {
    return locked(
        playback,
        () -> {
          if (!owns(playback)) {
            return Optional.empty();
          }

          return Optional.of(
              PlaybackSessionActivity.builder()
                  .playbackSessionId(playback.id)
                  .authority(playback.command.identity().playbackAuthority())
                  .mediaFileId(playback.command.mediaFileId())
                  .currentFormatAttempt(playback.current)
                  .decision(lastDecisionOf(playback))
                  .build());
        });
  }

  // A decision belongs to the current format attempt only while that attempt is ready.
  private static Optional<PlaybackDecision> lastDecisionOf(PlaybackSession playback) {
    if (!(playback.current instanceof PlaybackFormatAttempt.Ready)
        || playback.decisions.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(playback.decisions.getLast());
  }

  /** A client's timeline report changed what its playback session plays. */
  @EventListener
  public void onStreamSessionTimelineReported(@NonNull StreamSessionTimelineReportedEvent event) {
    var playback = streamOwners.get(event.streamSessionId());
    if (playback != null) {
      changed(playback);
    }
  }

  /**
   * A job attempt stored a media segment of a playback session's stream session: the stream session
   * of its current format attempt or of a pending audio track switch.
   */
  @EventListener
  public void onMediaSegmentStored(@NonNull MediaSegmentStoredEvent event) {
    announceProducedRangeMayHaveMoved(event.streamSessionId());
  }

  /** The requested variant of a playback session's stream session changed. */
  @EventListener
  public void onRequestedVariantChanged(@NonNull RequestedVariantChangedEvent event) {
    announceProducedRangeMayHaveMoved(event.streamSessionId());
  }

  private void announceProducedRangeMayHaveMoved(UUID streamSessionId) {
    var playback = streamOwners.get(streamSessionId);
    if (playback != null) {
      eventPublisher.publishEvent(new ProducedRangeMayHaveMovedEvent(playback.id));
    }
  }

  private static void adopt(
      PlaybackSession playback, StreamSession session, PlayingChoice choiceWhenEncoding) {
    playback.current = new PlaybackFormatAttempt.Ready(session);
    playback.readyAttempts.add(session);
    playback.decisions.add(
        PlaybackDecision.of(playback.startedFormatAttempts, session, playback.pendingAttemptCause));
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
