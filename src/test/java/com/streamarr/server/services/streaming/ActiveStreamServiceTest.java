package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PlaybackSessionFixture.acceptedPlayback;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackRequest;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.startPlaybackBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultVariantBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaTimeRange;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.streaming.events.TerminalTranscodeFailureEvent;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Active Stream Service Tests")
class ActiveStreamServiceTest {

  private static final ExecutionTargetId WORKER = new ExecutionTargetId("worker-session-1");

  private final FakeRuntimeStreamSessionRegistry registry = new FakeRuntimeStreamSessionRegistry();
  private final FakeCreatingStreamingService streams = new FakeCreatingStreamingService(registry);
  private final ConcurrentLinkedQueue<Runnable> formatAttemptStarts = new ConcurrentLinkedQueue<>();
  private final PlaybackSessionService playbackSessions =
      playbackServiceBuilder(streams).executor(formatAttemptStarts::add).build();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(identityFor(defaultPlaybackAuthorityBuilder().build()));
  private final FakeTranscodeExecutor executor = new FakeTranscodeExecutor();
  private final FakeSegmentStore segments = new FakeSegmentStore();
  private final ProducerLifecycleService producerLifecycle =
      StreamingRigFixture.streamingRigBuilder()
          .transcodeExecutor(executor)
          .segmentStore(segments)
          .properties(StreamingProperties.builder().build())
          .runtimeRegistry(registry)
          .build()
          .lifecycle();
  private final FakeProfileRepository profiles = new FakeProfileRepository();
  private final FakeAuthSessionRepository authSessions = new FakeAuthSessionRepository();
  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeMovieRepository movies = new FakeMovieRepository();
  private final FakeEpisodeRepository episodes = new FakeEpisodeRepository();
  private final ActiveStreamService service =
      ActiveStreamService.builder()
          .authorizationService(authorization)
          .playbackSessions(playbackSessions)
          .transcodeExecutor(executor)
          .producerLifecycle(producerLifecycle)
          .profileRepository(profiles)
          .authSessionRepository(authSessions)
          .mediaFileRepository(mediaFiles)
          .movieRepository(movies)
          .episodeRepository(episodes)
          .build();

  @Test
  @DisplayName(
      "Should describe the viewer, device, media, timeline, decision and worker of each stream when"
          + " a ServerAdmin asks")
  void
      shouldDescribeTheViewerDeviceMediaTimelineDecisionAndWorkerOfEachStreamWhenAServerAdminAsks() {
    var profile = profiles.save(ProfileFixture.defaultProfileBuilder().name("Ava").build());
    var authSession =
        authSessions.save(
            AuthSession.builder().accountId(UUID.randomUUID()).deviceName("Living Room").build());
    var authority =
        defaultPlaybackAuthorityBuilder()
            .profileId(profile.getId())
            .authSessionId(authSession.getId())
            .build();
    var episode = episodes.save(Episode.builder().build());
    var command =
        startPlaybackBuilder()
            .identity(identityFor(authority))
            .mediaFileId(matchedFile(episode.getId()))
            .build();
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
              assertThat(stream.media()).containsSame(episode);
              assertThat(stream.timeline())
                  .hasValueSatisfying(
                      timeline -> {
                        assertThat(timeline.positionSeconds()).isEqualTo(42);
                        assertThat(timeline.state()).isEqualTo(PlaybackState.PAUSED);
                      });
              assertThat(stream.decision()).isEqualTo(snapshot.decisions().stream().findFirst());
              assertThat(stream.workers()).containsExactly(WORKER.value());
            });
  }

  @Test
  @DisplayName(
      "Should link each stream to its movie or episode and to no media when its file has no match")
  void shouldLinkEachStreamToItsMovieOrEpisodeAndToNoMediaWhenItsFileHasNoMatch() {
    var movie = movies.save(Movie.builder().build());
    var episode = episodes.save(Episode.builder().build());
    var moviePlayback = startedPlaybackOf(matchedFile(movie.getId()));
    var episodePlayback = startedPlaybackOf(matchedFile(episode.getId()));
    var unmatchedPlayback = startedPlaybackOf(unmatchedFile());

    var media =
        service.activeStreams(authorization.currentIdentity()).stream()
            .collect(Collectors.toMap(ActiveStream::playbackSessionId, ActiveStream::media));

    assertThat(media.get(moviePlayback)).as("movie stream").containsSame(movie);
    assertThat(media.get(episodePlayback)).as("episode stream").containsSame(episode);
    assertThat(media.get(unmatchedPlayback)).as("unmatched stream").isEmpty();
  }

  @Test
  @DisplayName("Should link a stream to no media when its file names no movie or episode")
  void shouldLinkAStreamToNoMediaWhenItsFileNamesNoMovieOrEpisode() {
    var playback = startedPlaybackOf(matchedFile(UUID.randomUUID()));

    var streamsNow = service.activeStreams(authorization.currentIdentity());

    assertThat(streamsNow)
        .singleElement()
        .satisfies(
            stream -> {
              assertThat(stream.playbackSessionId()).isEqualTo(playback);
              assertThat(stream.media()).isEmpty();
            });
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
      "Should describe a stream with its media but without timeline, decision, produced range or"
          + " worker while its format attempt prepares")
  void shouldDescribeAStreamWithItsMediaButWithoutTimelineDecisionRangeOrWorkerWhileItPrepares() {
    var movie = movies.save(Movie.builder().build());
    var command = startPlaybackBuilder().mediaFileId(matchedFile(movie.getId())).build();
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
              assertThat(preparing.media()).containsSame(movie);
              assertThat(preparing.timeline()).isEmpty();
              assertThat(preparing.decision()).isEmpty();
              assertThat(preparing.producedRange()).isEmpty();
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

  // The default probe's keyframes open every 6 s slot, so media segment N covers [6N, 6N + 6) s.
  @Test
  @DisplayName(
      "Should report the run of produced media segments that holds the position as the produced"
          + " range when the stream plays HLS")
  void shouldReportTheRunOfProducedMediaSegmentsThatHoldsThePositionWhenTheStreamPlaysHls() {
    var snapshot = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    var session = readySession(snapshot);
    session.updatePlaybackState(40, PlaybackState.PLAYING);
    store(session, StreamSession.defaultVariant(), 0, 1, 4, 5, 6, 7, 9);

    var stream = service.activeStream(snapshot.playbackSessionId());

    assertThat(stream)
        .hasValueSatisfying(
            playing -> assertThat(playing.producedRange()).contains(rangeOf(24, 48)));
  }

  @Test
  @DisplayName(
      "Should report no produced range when no produced media segment holds the stream's position")
  void shouldReportNoProducedRangeWhenNoProducedMediaSegmentHoldsTheStreamsPosition() {
    var snapshot = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    var session = readySession(snapshot);
    session.updatePlaybackState(40, PlaybackState.PLAYING);
    store(session, StreamSession.defaultVariant(), 0, 1, 2, 3, 5, 7);

    var stream = service.activeStream(snapshot.playbackSessionId());

    assertThat(stream).hasValueSatisfying(playing -> assertThat(playing.producedRange()).isEmpty());
  }

  @Test
  @DisplayName("Should report no produced range when the stream plays directly")
  void shouldReportNoProducedRangeWhenTheStreamPlaysDirectly() {
    streams.shapeSessions(
        session ->
            session.delivery(StreamDelivery.DIRECT_PLAY).sourceContainer(SourceContainer.MP4));
    var snapshot = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    var session = readySession(snapshot);
    session.updatePlaybackState(40, PlaybackState.PLAYING);
    store(session, StreamSession.defaultVariant(), 6);

    var stream = service.activeStream(snapshot.playbackSessionId());

    assertThat(stream).hasValueSatisfying(playing -> assertThat(playing.producedRange()).isEmpty());
  }

  @Test
  @DisplayName(
      "Should report the produced range of the first listed variant when the player has requested no"
          + " media segment")
  void
      shouldReportTheProducedRangeOfTheFirstListedVariantWhenThePlayerHasRequestedNoMediaSegment() {
    var session = playingLadderSessionWithStoredSegments();

    var stream = service.activeStream(session.playbackSessionId()).orElseThrow();

    assertThat(stream.producedRange()).contains(rangeOf(36, 42));
  }

  @Test
  @DisplayName(
      "Should report the produced range of the variant whose media segment the player requested"
          + " last when it requested another variant's media segment")
  void shouldReportTheProducedRangeOfTheVariantWhoseMediaSegmentThePlayerRequestedLast() {
    var session = playingLadderSessionWithStoredSegments();

    producerLifecycle.prepareRequestedVariant(
        session.streamSessionId(), "720p", SegmentNames.mediaSegmentName(6));
    var stream = service.activeStream(session.playbackSessionId()).orElseThrow();

    assertThat(stream.producedRange()).contains(rangeOf(24, 54));
  }

  private record PlayingSession(UUID playbackSessionId, UUID streamSessionId) {}

  // An encode lists its ladder's first variant first: 1080p holds only media segment 6; 720p holds
  // media segments 4 to 8.
  private PlayingSession playingLadderSessionWithStoredSegments() {
    streams.shapeSessions(
        session ->
            session
                .transcodeDecision(fullTranscodeDecision("h264"))
                .variants(List.of(variant("1080p"), variant("720p"))));
    var snapshot = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    var session = readySession(snapshot);
    session.updatePlaybackState(40, PlaybackState.PLAYING);
    store(session, "1080p", 6);
    store(session, "720p", 4, 5, 6, 7, 8);
    return new PlayingSession(snapshot.playbackSessionId(), session.getSessionId());
  }

  // A title that runs past its probed duration leaves media segments past the advertised end in
  // the store. A 60 s title advertises media segments 0 to 9.
  @Test
  @DisplayName(
      "Should end the produced range at the media end when an encode stored media segments past the"
          + " advertised end")
  void shouldEndTheProducedRangeAtTheMediaEndWhenAnEncodeStoredMediaSegmentsPastTheAdvertisedEnd() {
    streams.shapeSessions(
        session ->
            session
                .mediaProbe(sixtySecondProbe())
                .transcodeDecision(fullTranscodeDecision("h264")));
    var snapshot = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    var session = readySession(snapshot);
    session.updatePlaybackState(57, PlaybackState.PLAYING);
    store(session, StreamSession.defaultVariant(), 8, 9, 10, 11);

    var stream = service.activeStream(snapshot.playbackSessionId());

    assertThat(stream)
        .hasValueSatisfying(
            playing -> assertThat(playing.producedRange()).contains(rangeOf(48, 60)));
  }

  @Test
  @DisplayName(
      "Should end the produced range at the media end when a stream copy stored media segments past"
          + " the advertised end")
  void
      shouldEndTheProducedRangeAtTheMediaEndWhenAStreamCopyStoredMediaSegmentsPastTheAdvertisedEnd() {
    streams.shapeSessions(session -> session.mediaProbe(sixtySecondProbe()));
    var snapshot = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    var session = readySession(snapshot);
    session.updatePlaybackState(57, PlaybackState.PLAYING);
    store(session, StreamSession.defaultVariant(), 8, 9, 10, 11);

    var stream = service.activeStream(snapshot.playbackSessionId());

    assertThat(stream)
        .hasValueSatisfying(
            playing -> assertThat(playing.producedRange()).contains(rangeOf(48, 60)));
  }

  private static MediaProbe sixtySecondProbe() {
    return defaultProbeBuilder().duration(Duration.ofSeconds(60)).build();
  }

  private UUID matchedFile(UUID mediaId) {
    return mediaFiles
        .save(MediaFile.builder().mediaId(mediaId).status(MediaFileStatus.MATCHED).build())
        .getId();
  }

  private UUID unmatchedFile() {
    return mediaFiles.save(MediaFile.builder().status(MediaFileStatus.UNMATCHED).build()).getId();
  }

  private UUID startedPlaybackOf(UUID mediaFileId) {
    var command = startPlaybackBuilder().mediaFileId(mediaFileId).build();
    return acceptedPlayback(playbackSessions.start(command)).playbackSessionId();
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

  private void store(StreamSession session, String variantLabel, int... mediaSegmentIndexes) {
    for (var index : mediaSegmentIndexes) {
      segments.storeSegment(
          session.getSessionId(),
          SegmentNames.storedSegmentName(variantLabel, SegmentNames.mediaSegmentName(index)),
          new byte[] {1});
    }
  }

  private static MediaTimeRange rangeOf(int startSeconds, int endSeconds) {
    return new MediaTimeRange(Duration.ofSeconds(startSeconds), Duration.ofSeconds(endSeconds));
  }

  private static QualityVariant variant(String label) {
    return defaultVariantBuilder()
        .width(1280)
        .height(720)
        .videoBitrate(3_000_000L)
        .label(label)
        .build();
  }
}
