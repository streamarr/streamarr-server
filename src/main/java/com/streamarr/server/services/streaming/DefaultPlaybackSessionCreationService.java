package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamSessionTerminalReason;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.streaming.StreamSessionAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.auth.TokenScope;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class DefaultPlaybackSessionCreationService implements PlaybackSessionCreationService {

  private final StreamingService streamingService;
  private final PlaybackTokenIssuer playbackTokenIssuer;
  private final StreamingProperties streamingProperties;
  private final StreamSessionLifecycleTransactions lifecycleTransactions;
  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final StreamSessionCleanup cleanup;
  private final StreamSessionTransactionRetry transactionRetry;
  private final Clock clock;

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public CreatedPlaybackSession create(CreatePlaybackSessionCommand command) {
    requireProfileAuthority(command);
    var streamSessionId = UUID.randomUUID();
    var reservation =
        runtimeRegistry
            .reserve(streamSessionId)
            .orElseThrow(() -> new SessionNotFoundException(streamSessionId));
    var authority = authority(streamSessionId, command);
    var runtimeSessionId = streamSessionId;
    try {
      var admittedAt =
          transactionRetry
              .execute(
                  () ->
                      lifecycleTransactions.admit(
                          authority, streamingProperties.provisioningTimeout()))
              .orElseThrow(() -> new SessionNotFoundException(streamSessionId));

      var session =
          streamingService.createSession(
              CreateRuntimeStreamSessionCommand.builder()
                  .streamSessionId(streamSessionId)
                  .mediaFileId(command.mediaFileId())
                  .profileId(command.sourceIdentity().profileId())
                  .options(command.options())
                  .initialLastAccessedAt(admittedAt)
                  .reservation(reservation)
                  .build());
      runtimeSessionId = session.getSessionId();

      if (!runtimeRegistry.markRunning(reservation)) {
        throw new SessionNotFoundException(streamSessionId);
      }

      if (!transactionRetry.execute(
          () ->
              lifecycleTransactions.activate(
                  authority, streamingProperties.provisioningTimeout()))) {
        throw new SessionNotFoundException(streamSessionId);
      }

      var playbackToken =
          playbackTokenIssuer.issue(
              command.sourceIdentity(), session, playbackTokenValidity(session));
      if (!transactionRetry.execute(
          () -> lifecycleTransactions.completeCreation(streamSessionId))) {
        throw new SessionNotFoundException(streamSessionId);
      }

      return CreatedPlaybackSession.builder()
          .sessionId(session.getSessionId())
          .transcodeMode(session.getTranscodeDecision().transcodeMode())
          .playbackToken(playbackToken)
          .build();
    } catch (RuntimeException exception) {
      compensate(streamSessionId, runtimeSessionId, exception);
      throw exception;
    } finally {
      runtimeRegistry.releaseReservation(reservation);
    }
  }

  private void compensate(
      UUID streamSessionId, UUID runtimeSessionId, RuntimeException originalFailure) {
    var termination =
        StreamSessionTermination.builder()
            .streamSessionId(streamSessionId)
            .reason(StreamSessionTerminalReason.STARTUP_FAILURE)
            .terminalAt(clock.instant())
            .build();
    try {
      transactionRetry.execute(() -> lifecycleTransactions.terminalize(termination));
    } catch (RuntimeException terminalFailure) {
      originalFailure.addSuppressed(terminalFailure);
      recordTerminationIntent(termination, originalFailure);
      log.warn("Failed to persist terminal state for stream session {}", streamSessionId);
    }

    try {
      cleanup.cleanup(runtimeSessionId);
      if (!streamSessionId.equals(runtimeSessionId)) {
        cleanup.cleanup(streamSessionId);
      }
    } catch (RuntimeException cleanupFailure) {
      originalFailure.addSuppressed(cleanupFailure);
      log.warn("Stream session {} cleanup will be retried", streamSessionId);
    }
  }

  private void recordTerminationIntent(
      StreamSessionTermination termination, RuntimeException originalFailure) {
    try {
      transactionRetry.execute(() -> lifecycleTransactions.recordTerminationIntent(termination));
    } catch (RuntimeException intentFailure) {
      originalFailure.addSuppressed(intentFailure);
      log.error(
          "Failed to persist termination intent for stream session {}",
          termination.streamSessionId());
    }
  }

  private void requireProfileAuthority(CreatePlaybackSessionCommand command) {
    var identity = command.sourceIdentity();
    if (identity.scope() != TokenScope.PROFILE
        || identity.householdId() == null
        || identity.profileId() == null) {
      throw new ProfileRequiredException();
    }
  }

  private StreamSessionAuthority authority(
      UUID streamSessionId, CreatePlaybackSessionCommand command) {
    var identity = command.sourceIdentity();
    return StreamSessionAuthority.builder()
        .streamSessionId(streamSessionId)
        .authSessionId(identity.sessionId())
        .accountId(identity.accountId())
        .householdId(identity.householdId())
        .profileId(identity.profileId())
        .mediaFileId(command.mediaFileId())
        .build();
  }

  private Duration playbackTokenValidity(StreamSession session) {
    return session.getMediaProbe().duration().plus(streamingProperties.sessionRetention());
  }
}
