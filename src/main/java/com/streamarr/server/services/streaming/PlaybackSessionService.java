package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.PlaybackSessionClosedException;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.events.TerminalTranscodeFailureEvent;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
  private final int maximumFormatAttempts;
  private final Duration sessionRetention;
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
      @NonNull Duration sessionRetention,
      Clock clock) {
    if (maximumFormatAttempts <= 0) {
      throw new IllegalArgumentException("Maximum format attempts must be positive");
    }

    this.streamingService = streamingService;
    this.authorityGate = authorityGate;
    this.maximumFormatAttempts = maximumFormatAttempts;
    this.sessionRetention = sessionRetention;
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
    private PlaybackFormatAttempt current;
    private Instant lastAccessedAt;
    @Builder.Default private int formatAttemptCount = 1;
    private final Set<String> excludedEncodeCodecFamilies = new HashSet<>();
    private final Set<UUID> streamIds = new HashSet<>();
  }

  @Builder
  private record FormatAttemptReservation(
      PlaybackSession playback, UUID retiredStreamId, Optional<UUID> streamId) {}

  public Outcome<PlaybackSessionSnapshot, CreateStreamSessionRejection> start(
      @NonNull StartPlaybackSessionCommand command) {
    if (command.identity().profileId() == null) {
      throw new AuthenticationRequiredException();
    }

    requireAuthority(command.identity(), command.identity().playbackAuthority());
    var childId = UUID.randomUUID();
    var playback = register(command, childId);
    try {
      var outcome = streamingService.createSession(childCommand(playback, childId).build());
      return switch (outcome) {
        case Outcome.Accepted(var session) -> {
          settle(playback, session);
          yield Outcome.accepted(
              snapshot(playback).orElseThrow(PlaybackSessionClosedException::new));
        }
        case Outcome.Rejected(var rejections) -> {
          discard(playback);
          yield Outcome.rejected(rejections);
        }
      };
    } catch (RuntimeException failure) {
      discardAfterFailure(playback, failure);
      throw failure;
    }
  }

  private PlaybackSession register(StartPlaybackSessionCommand command, UUID childId) {
    synchronized (registration) {
      if (closed) {
        throw new PlaybackSessionClosedException();
      }

      var playback =
          PlaybackSession.builder()
              .id(UUID.randomUUID())
              .command(command)
              .current(new PlaybackFormatAttempt.Pending(childId))
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

  public void reapExpired(@NonNull Instant now) {
    discardMatching(playback -> lastActivity(playback).plus(sessionRetention).isBefore(now));
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
    var failedId = event.streamSessionId();
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
    if (!isCurrentAttempt(playback, failedId)) {
      return Optional.empty();
    }

    return Optional.of(failPlayback(playback, failedId, reason));
  }

  private Optional<FormatAttemptReservation> reserveFormatAttempt(
      PlaybackSession playback, UUID failedId, PlaybackSessionFailure fixedFailure) {
    if (!isCurrentAttempt(playback, failedId)
        || (fixedFailure instanceof PlaybackSessionFailure.FixedFormatFailed
            && playback.current instanceof PlaybackFormatAttempt.Pending)) {
      return Optional.empty();
    }

    var quality = playback.command.options().quality();
    if (quality != null && quality != VideoQuality.AUTO) {
      return Optional.of(failPlayback(playback, failedId, fixedFailure));
    }

    if (playback.formatAttemptCount >= maximumFormatAttempts) {
      return Optional.of(
          failPlayback(playback, failedId, new PlaybackSessionFailure.AttemptsExhausted()));
    }

    if (fixedFailure instanceof PlaybackSessionFailure.FixedFormatFailed
        && playback.current instanceof PlaybackFormatAttempt.Ready(var session)
        && !session.getTranscodeDecision().transcodeMode().copiesVideo()) {
      playback.excludedEncodeCodecFamilies.add(session.getTranscodeDecision().videoCodecFamily());
    }

    playback.lastAccessedAt = clock.instant();
    var childId = UUID.randomUUID();
    playback.current = new PlaybackFormatAttempt.Pending(childId);
    playback.formatAttemptCount++;
    playback.streamIds.add(childId);
    streamOwners.remove(failedId, playback);
    streamOwners.put(childId, playback);
    return Optional.of(
        FormatAttemptReservation.builder()
            .playback(playback)
            .retiredStreamId(failedId)
            .streamId(Optional.of(childId))
            .build());
  }

  private boolean isCurrentAttempt(PlaybackSession playback, UUID streamId) {
    return owns(playback)
        && playback.current.streamSessionId().equals(streamId)
        && !(playback.current instanceof PlaybackFormatAttempt.Failed);
  }

  private FormatAttemptReservation failPlayback(
      PlaybackSession playback, UUID failedId, PlaybackSessionFailure reason) {
    playback.lastAccessedAt = clock.instant();
    playback.current = new PlaybackFormatAttempt.Failed(failedId, reason);
    streamOwners.remove(failedId, playback);
    return FormatAttemptReservation.builder()
        .playback(playback)
        .retiredStreamId(failedId)
        .streamId(Optional.empty())
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
      if (work.streamId().isEmpty() || !isPending(work.playback(), work.streamId().orElseThrow())) {
        return;
      }

      var streamId = work.streamId().orElseThrow();
      var outcome =
          streamingService.createSession(
              childCommand(work.playback(), streamId)
                  .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                  .build());
      switch (outcome) {
        case Outcome.Accepted(var session) -> settle(work.playback(), session);
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
    work.streamId()
        .ifPresent(
            id -> failCurrent(work.playback(), id, new PlaybackSessionFailure.StartupFailed()));
    var children = new HashSet<UUID>();
    work.streamId().ifPresent(children::add);
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
        () ->
            CreateStreamSessionCommand.builder()
                .streamSessionId(streamId)
                .mediaFileId(playback.command.mediaFileId())
                .identity(playback.command.identity())
                .options(playback.command.options())
                .excludedEncodeCodecFamilies(Set.copyOf(playback.excludedEncodeCodecFamilies))
                .videoFormatConstraint(
                    playback.command.options().quality() == VideoQuality.ORIGINAL
                        ? VideoFormatConstraint.COPY_ONLY
                        : VideoFormatConstraint.COPY_OR_ENCODE));
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

  private void settle(PlaybackSession playback, StreamSession session) {
    boolean accepted =
        locked(
            playback,
            () -> {
              if (!pending(playback, session.getSessionId())) {
                return false;
              }

              playback.current = new PlaybackFormatAttempt.Ready(session);
              return true;
            });
    if (!accepted) {
      streamingService.destroySession(session.getSessionId());
    }
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
                  .maximumFormatAttempts(maximumFormatAttempts)
                  .currentFormatAttempt(playback.current)
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
