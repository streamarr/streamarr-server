package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PlaybackSessionFixture.acceptedPlayback;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackRequest;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.startPlaybackBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.media.MediaFileTitle;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.streaming.events.TerminalTranscodeFailureEvent;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Active Stream Service Tests")
class ActiveStreamServiceTest {

  private static final ExecutionTargetId WORKER = new ExecutionTargetId("worker-session-1");

  private final FakeCreatingStreamingService streams = new FakeCreatingStreamingService();
  private final ConcurrentLinkedQueue<Runnable> formatAttemptStarts = new ConcurrentLinkedQueue<>();
  private final PlaybackSessionService playbackSessions =
      playbackServiceBuilder(streams).executor(formatAttemptStarts::add).build();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(identityFor(defaultPlaybackAuthorityBuilder().build()));
  private final FakeTranscodeExecutor executor = new FakeTranscodeExecutor();
  private final FakeProfileRepository profiles = new FakeProfileRepository();
  private final FakeAuthSessionRepository authSessions = new FakeAuthSessionRepository();
  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final ActiveStreamService service =
      new ActiveStreamService(
          authorization, playbackSessions, executor, profiles, authSessions, mediaFiles);

  @Test
  @DisplayName(
      "Should describe the viewer, device, title, timeline, decision and worker of each stream when"
          + " a ServerAdmin asks")
  void
      shouldDescribeTheViewerDeviceTitleTimelineDecisionAndWorkerOfEachStreamWhenAServerAdminAsks() {
    var profile = profiles.save(ProfileFixture.defaultProfileBuilder().name("Ava").build());
    var authSession =
        authSessions.save(
            AuthSession.builder().accountId(UUID.randomUUID()).deviceName("Living Room").build());
    var authority =
        defaultPlaybackAuthorityBuilder()
            .profileId(profile.getId())
            .authSessionId(authSession.getId())
            .build();
    var command = startPlaybackBuilder().identity(identityFor(authority)).build();
    var episode =
        MediaFileTitle.builder()
            .title(Optional.of("Pilot"))
            .seriesTitle(Optional.of("A Series"))
            .seasonNumber(OptionalInt.of(1))
            .episodeNumber(OptionalInt.of(2))
            .build();
    mediaFiles.storeTitle(command.mediaFileId(), episode);
    var snapshot = acceptedPlayback(playbackSessions.start(command));
    var session = readySession(snapshot);
    session.updatePlaybackState(42, PlaybackState.PAUSED);
    runOn(WORKER, session);

    var streamsNow = service.activeStreams(authorization.currentIdentity());

    assertThat(streamsNow)
        .singleElement()
        .satisfies(
            stream -> {
              assertThat(stream.playbackSessionId()).isEqualTo(snapshot.playbackSessionId());
              assertThat(stream.profileId()).isEqualTo(profile.getId());
              assertThat(stream.profileName()).contains("Ava");
              assertThat(stream.deviceName()).contains("Living Room");
              assertThat(stream.mediaFileId()).isEqualTo(command.mediaFileId());
              assertThat(stream.title()).contains(episode);
              assertThat(stream.timeline())
                  .hasValueSatisfying(
                      timeline -> {
                        assertThat(timeline.positionSeconds()).isEqualTo(42);
                        assertThat(timeline.state()).isEqualTo(PlaybackState.PAUSED);
                      });
              assertThat(stream.decision()).isEqualTo(snapshot.decisions().stream().findFirst());
              assertThat(stream.workers()).containsExactly(WORKER.value());
            });
    assertThat(authorization.recordedIntents()).containsExactly(new Intent.ViewActiveStreams());
  }

  @Test
  @DisplayName("Should refuse the active streams when the caller is not allowed to view them")
  void shouldRefuseTheActiveStreamsWhenTheCallerIsNotAllowedToViewThem() {
    acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    authorization.denyAll();
    var identity = authorization.currentIdentity();

    assertThatThrownBy(() -> service.activeStreams(identity))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.requireViewActiveStreams(identity))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName(
      "Should describe a stream without timeline, decision or worker while its format attempt"
          + " prepares")
  void shouldDescribeAStreamWithoutTimelineDecisionOrWorkerWhileItsFormatAttemptPrepares() {
    var command = startPlaybackBuilder().build();
    var snapshot = acceptedPlayback(playbackSessions.start(command));
    playbackSessions.reportFormatFailure(
        ReportPlaybackFormatFailureCommand.builder()
            .playbackSessionId(snapshot.playbackSessionId())
            .streamSessionId(snapshot.currentFormatAttempt().streamSessionId())
            .identity(command.identity())
            .reason(PlaybackFormatFailure.DECODE_ERROR)
            .build());

    var stream = service.activeStream(snapshot.playbackSessionId());

    assertThat(stream)
        .hasValueSatisfying(
            preparing -> {
              assertThat(preparing.profileName()).isEmpty();
              assertThat(preparing.deviceName()).isEmpty();
              assertThat(preparing.title()).isEmpty();
              assertThat(preparing.timeline()).isEmpty();
              assertThat(preparing.decision()).isEmpty();
              assertThat(preparing.workers()).isEmpty();
            });
  }

  @Test
  @DisplayName("Should leave out a playback session once it ended or failed")
  void shouldLeaveOutAPlaybackSessionOnceItEndedOrFailed() {
    var ended = startPlaybackBuilder().build();
    var endedSnapshot = acceptedPlayback(playbackSessions.start(ended));
    var failing = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    var playing = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));

    playbackSessions.close(playbackRequest(endedSnapshot, ended));
    playbackSessions.onTerminalTranscodeFailure(
        TerminalTranscodeFailureEvent.builder()
            .streamSessionId(failing.currentFormatAttempt().streamSessionId())
            .jobAttemptId(UUID.randomUUID())
            .variantLabel(StreamSession.defaultVariant())
            .failure(TranscodeFailure.INVALID_SPECIFICATION)
            .build());
    formatAttemptStarts.forEach(Runnable::run);

    assertThat(service.activeStreams(authorization.currentIdentity()))
        .extracting(ActiveStream::playbackSessionId)
        .containsExactly(playing.playbackSessionId());
    assertThat(service.activeStream(endedSnapshot.playbackSessionId())).isEmpty();
    assertThat(service.activeStream(failing.playbackSessionId())).isEmpty();
  }

  private static StreamSession readySession(PlaybackSessionSnapshot snapshot) {
    if (snapshot.currentFormatAttempt() instanceof PlaybackFormatAttempt.Ready(var session)) {
      return session;
    }

    throw new AssertionError("The playback session has no ready format attempt: " + snapshot);
  }

  private void runOn(ExecutionTargetId target, StreamSession session) {
    executor.setExecutionTargets(List.of(target));
    var handle =
        executor.start(
            TranscodeRequest.builder()
                .sessionId(session.getSessionId())
                .sourcePath(session.getSourcePath())
                .transcodeDecision(session.getTranscodeDecision())
                .encoderPreset(EncoderPreset.FASTER)
                .build(),
            target);
    session.setHandle(handle);
  }
}
