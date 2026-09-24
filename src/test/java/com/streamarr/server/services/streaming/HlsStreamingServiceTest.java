package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.fixtures.StreamSessionFixture.mintHandle;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.probe.PersistedProbeReader;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@DisplayName("HLS Streaming Service Tests")
class HlsStreamingServiceTest {

  private FakeMediaFileRepository mediaFileRepository;
  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeMediaFileContainerInfoRepository probeResults;
  private CapturingEventPublisher probeEvents;
  private FakePlaybackAuthorityGate authorityGate;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private HlsStreamingService service;

  @Test
  @DisplayName("Should reject session creation when the media file has no persisted probe outcome")
  void shouldRejectSessionCreationWhenTheMediaFileHasNoPersistedProbeOutcome() {
    probeResults.clear();
    var file = seedMediaFile();
    var command = createStreamSessionCommand(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(service.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.ProbeNotReady()));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getRunningCount()).isZero();
  }

  @ParameterizedTest
  @CsvSource({"aac, AUTO, VIDEO_TRANSCODE", "flac, HIGH_720P, FULL_TRANSCODE"})
  @DisplayName(
      "Should reject the stream session when the video must be encoded and the probe has no frame rate")
  void shouldRejectTheStreamSessionWhenTheVideoMustBeEncodedAndTheProbeHasNoFrameRate(
      String audioCodec, VideoQuality quality, TranscodeMode encodingMode) {
    var probe = defaultProbeBuilder().videoCodec("hevc").audioCodec(audioCodec);
    var options =
        StreamingOptions.builder().quality(quality).supportedCodecs(List.of("h264")).build();
    var file = seedMediaFile();
    var profileId = UUID.randomUUID();
    probeResults.setDefaultProbe(probe.framerate(OptionalDouble.of(23.976)).build());
    var encoded = createSession(file.getId(), profileId, options);
    service.destroySession(encoded.getSessionId());
    var startedRequests = List.copyOf(transcodeExecutor.getStartedRequests());
    probeResults.setDefaultProbe(probe.framerate(OptionalDouble.empty()).build());

    var outcome =
        service.createSession(createStreamSessionCommand(file.getId(), profileId, options));

    assertThat(encoded.getTranscodeDecision().transcodeMode()).isEqualTo(encodingMode);
    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.FrameRateUnknown()));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEqualTo(startedRequests);
  }

  @Test
  @DisplayName(
      "Should report the unknown frame rate when the video must be encoded and no transcode slot is free")
  void shouldReportTheUnknownFrameRateWhenTheVideoMustBeEncodedAndNoTranscodeSlotIsFree() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").framerate(OptionalDouble.empty()).build());
    transcodeExecutor.setAvailableSlots(0);
    var file = seedMediaFile();

    assertThat(
            service.createSession(
                createStreamSessionCommand(file.getId(), UUID.randomUUID(), defaultOptions())))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.FrameRateUnknown()));
  }

  @ParameterizedTest
  @CsvSource({"aac, REMUX", "flac, AUDIO_TRANSCODE"})
  @DisplayName(
      "Should copy the video with no encoded variants when the probe has no frame rate and the codec is supported")
  void shouldCopyTheVideoWithNoEncodedVariantsWhenTheProbeHasNoFrameRateAndTheCodecIsSupported(
      String audioCodec, TranscodeMode copyMode) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().audioCodec(audioCodec).framerate(OptionalDouble.empty()).build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(copyMode);
    assertThat(session.getVariants()).isEmpty();
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(TranscodeRequest::framerate)
        .isEqualTo(OptionalDouble.empty());
  }

  @BeforeEach
  void setUp() {
    mediaFileRepository = new FakeMediaFileRepository();
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    probeResults = new FakeMediaFileContainerInfoRepository();
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).build());
    probeEvents = new CapturingEventPublisher();
    authorityGate = new FakePlaybackAuthorityGate();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    service = serviceWith(transcodeExecutor, runtimeRegistry);
  }

  private static StreamingProperties streamingProperties() {
    return StreamingProperties.builder()
        .maxConcurrentTranscodes(3)
        .targetSegmentDuration(Duration.ofSeconds(6))
        .sessionTimeout(Duration.ofSeconds(60))
        .build();
  }

  private HlsStreamingService serviceWith(
      TranscodeExecutor executor, RuntimeStreamSessionRegistry registry) {
    var properties = streamingProperties();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(executor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(registry)
            .build();
    return HlsStreamingService.builder()
        .mediaFileRepository(mediaFileRepository)
        .transcodeExecutor(executor)
        .segmentStore(segmentStore)
        .playbackProbeService(
            new PlaybackProbeService(new PersistedProbeReader(probeResults), probeEvents))
        .transcodeDecisionService(new TranscodeDecisionService())
        .qualityLadderService(new QualityLadderService())
        .properties(properties)
        .authorityGate(authorityGate)
        .runtimeRegistry(registry)
        .producerLifecycle(rig.lifecycle())
        .deliveryCoordinator(rig.coordinator())
        .build();
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.AUTO)
        .supportedCodecs(List.of("h264"))
        .build();
  }

  private MediaFile seedMediaFile() {
    var file =
        MediaFile.builder()
            .filepathUri("file:///media/movies/test.mkv")
            .filename("test.mkv")
            .status(MediaFileStatus.MATCHED)
            .size(1_000_000L)
            .build();
    return mediaFileRepository.save(file);
  }

  private StreamSession createSession(UUID mediaFileId, UUID profileId, StreamingOptions options) {
    return accepted(
        service.createSession(createStreamSessionCommand(mediaFileId, profileId, options)));
  }

  private Optional<StreamSession> accessSession(StreamSession session) {
    return service.accessSession(playbackRequest(session));
  }

  private Optional<StreamSession> accessMissingSession(UUID streamSessionId) {
    var authority = defaultPlaybackAuthorityBuilder().build();
    return service.accessSession(
        PlaybackRequest.builder()
            .streamSessionId(streamSessionId)
            .identity(identityFor(authority))
            .build());
  }

  @Test
  @DisplayName("Should assign session ID and media file when creating session")
  void shouldAssignSessionIdAndMediaFileWhenCreatingSession() {
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getSessionId()).isNotNull();
    assertThat(session.getMediaFileId()).isEqualTo(file.getId());
  }

  @Test
  @DisplayName("Should propagate authority failure when accessing a session")
  void shouldPropagateAuthorityFailureWhenAccessingSession() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    authorityGate.failWith(
        new DataAccessResourceFailureException("authority database unavailable"));

    assertThatThrownBy(() -> accessSession(session)).isInstanceOf(DataAccessException.class);
  }

  @Test
  @DisplayName("Should propagate authority failure and start no transcode when creating a session")
  void shouldPropagateAuthorityFailureWhenCreatingSession() {
    var file = seedMediaFile();
    var fileId = file.getId();
    var profileId = UUID.randomUUID();
    var options = defaultOptions();
    authorityGate.failWith(
        new DataAccessResourceFailureException("authority database unavailable"));

    assertThatThrownBy(() -> createSession(fileId, profileId, options))
        .isInstanceOf(DataAccessException.class);
    assertThat(transcodeExecutor.getStarted()).isEmpty();
  }

  @Test
  @DisplayName("Should populate media probe when creating session")
  void shouldPopulateMediaProbeWhenCreatingSession() {
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getMediaProbe()).isNotNull();
  }

  @Test
  @DisplayName("Should populate transcode decision when creating session")
  void shouldPopulateTranscodeDecisionWhenCreatingSession() {
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision()).isNotNull();
  }

  @Test
  @DisplayName("Should downmix stereo audio to mono when the client supports only one channel")
  void shouldDownmixStereoAudioToMonoWhenTheClientSupportsOnlyOneChannel() {
    probeResults.setDefaultProbe(defaultProbeBuilder().audioChannels(OptionalInt.of(2)).build());
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .supportedCodecs(List.of("av1"))
            .supportedAudioCodecs(List.of("aac"))
            .maxAudioChannels(1)
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.FULL_TRANSCODE);
    var audio = session.getTranscodeDecision().audioDecision();
    assertThat(audio.mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(audio.codec()).isEqualTo("aac");
    assertThat(audio.channels()).isEqualTo(1);
    assertThat(audio.bitrate()).isEqualTo(64_000L);
  }

  // Expected strings: RFC 6381 codecs parameters from the Apple HLS authoring specification.
  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({
    "aac, mp4a.40.2",
    "ac3, ac-3",
    "eac3, ec-3",
    "mp3, mp4a.40.34",
    "flac, fLaC",
    "opus, Opus",
    "alac, alac"
  })
  @DisplayName(
      "Should advertise the copied audio codec in the playlist when the client supports the source audio codec")
  void shouldAdvertiseCopiedAudioCodecInPlaylistWhenClientSupportsSourceAudioCodec(
      String sourceAudioCodec, String codecsParameter) {
    probeResults.setDefaultProbe(defaultProbeBuilder().audioCodec(sourceAudioCodec).build());
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .supportedCodecs(List.of("h264"))
            .supportedAudioCodecs(List.of(sourceAudioCodec))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "token");

    assertThat(session.getTranscodeDecision().audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(playlist).contains("CODECS=\"avc1.640028," + codecsParameter + "\"");
  }

  @Test
  @DisplayName("Should start transcode when creating session")
  void shouldStartTranscodeWhenCreatingSession() {
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(transcodeExecutor.getStarted()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isTrue();
  }

  @ParameterizedTest
  @CsvSource({"PT0.001S, 1", "PT3S, 1", "PT12S, 2", "PT12.0004S, 2", "PT2M5.5S, 21"})
  @DisplayName(
      "Should advertise the media playlist's segment count to the job attempt when creating session")
  void shouldAdvertiseTheMediaPlaylistSegmentCountToTheJobAttemptWhenCreatingSession(
      Duration mediaDuration, int expectedCount) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).duration(mediaDuration).build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMediaPlaylist(session, "token");
    assertThat(playlist.lines().filter(line -> line.startsWith("#EXTINF:"))).hasSize(expectedCount);
    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .extracting(TranscodeRequest::mediaSegmentCount)
        .containsOnly(expectedCount);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("probesWithoutAMediaSegment")
  @DisplayName(
      "Should reject session creation without starting a job attempt when the probe gives no media segment")
  void shouldRejectSessionCreationWithoutStartingJobAttemptWhenTheProbeGivesNoMediaSegment(
      String probedDuration, ProbeOutcome.Success probe) {
    probeResults.setDefaultOutcome(probe);
    var file = seedMediaFile();
    var command = createStreamSessionCommand(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(service.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoMediaSegments()));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  static Stream<Arguments> probesWithoutAMediaSegment() {
    var complete = ProbeFixture.completeProbe(defaultProbeBuilder().build());
    var withoutDuration =
        new ProbeOutcome.Success(
            ProbeContainer.builder()
                .format(complete.container().format())
                .bitrate(complete.container().bitrate())
                .build(),
            complete.streams());
    return Stream.of(
        Arguments.of("missing", withoutDuration),
        Arguments.of("zero", probeLasting(Duration.ZERO)),
        Arguments.of("under one millisecond", probeLasting(Duration.ofNanos(999_999))));
  }

  private static ProbeOutcome.Success probeLasting(Duration duration) {
    return ProbeFixture.completeProbe(defaultProbeBuilder().duration(duration).build());
  }

  @Test
  @DisplayName("Should preserve non-UTF-8 filepath bytes when creating session")
  void shouldPreserveNonUtf8FilepathBytesWhenCreatingSession() {
    var filepathUri = "file:///media/movies/caf%E9.mkv";
    var file =
        mediaFileRepository.save(
            MediaFile.builder()
                .filepathUri(filepathUri)
                .filename("legacy-name.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getSourcePath()).isEqualTo(Path.of(URI.create(filepathUri)));
  }

  @Test
  @DisplayName("Should reject creation when media file is not found")
  void shouldRejectCreationWhenMediaFileIsNotFound() {
    var invalidId = UUID.randomUUID();
    var profileId = UUID.randomUUID();

    var options = defaultOptions();

    assertThat(service.createSession(createStreamSessionCommand(invalidId, profileId, options)))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.MediaFileNotFound(invalidId)));
  }

  @Test
  @DisplayName("Should return session when session exists")
  void shouldReturnSessionWhenSessionExists() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    var retrieved = accessSession(session);

    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getSessionId()).isEqualTo(session.getSessionId());
  }

  @Test
  @DisplayName("Should return empty when session does not exist")
  void shouldReturnEmptyWhenSessionDoesNotExist() {
    authorityGate.failWith(new IllegalStateException("Authority must not be checked"));

    var result = accessMissingSession(UUID.randomUUID());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when playback authority does not own runtime session")
  void shouldReturnEmptyWhenPlaybackAuthorityDoesNotOwnRuntimeSession() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var lastAccessedAt = session.getLastAccessedAt();
    var otherAuthority = defaultPlaybackAuthorityBuilder().build();
    var request =
        PlaybackRequest.builder()
            .streamSessionId(session.getSessionId())
            .identity(identityFor(otherAuthority))
            .build();

    var result = service.accessSession(request);

    assertThat(result).isEmpty();
    assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessedAt);
  }

  @Test
  @DisplayName("Should return empty when live playback authority is denied")
  void shouldReturnEmptyWhenLivePlaybackAuthorityIsDenied() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var lastAccessedAt = session.getLastAccessedAt();
    authorityGate.deny();

    var result = accessSession(session);

    assertThat(result).isEmpty();
    assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessedAt);
  }

  @Test
  @DisplayName("Should refuse session creation when live playback authority is denied")
  void shouldRefuseSessionCreationWhenLivePlaybackAuthorityIsDenied() {
    var file = seedMediaFile();
    var command = createStreamSessionCommand(file.getId(), UUID.randomUUID(), defaultOptions());
    authorityGate.deny();

    assertThatThrownBy(() -> service.createSession(command))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStarted()).isEmpty();
  }

  @Test
  @DisplayName("Should update last accessed timestamp when session is retrieved")
  void shouldUpdateLastAccessedTimestampWhenSessionIsRetrieved() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var initialAccess = session.getLastAccessedAt();

    var retrieved = accessSession(session);

    assertThat(retrieved.get().getLastAccessedAt()).isAfterOrEqualTo(initialAccess);
  }

  @Test
  @DisplayName("Should not resurrect a session when destroyed during a segment request")
  void shouldNotResurrectSessionWhenDestroyedDuringSegmentRequest() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var sessionId = session.getSessionId();
    // The gate runs between accessSession's registry read and its write-back, so destroying here
    // lands inside the check-then-act window a concurrent destroy would occupy.
    authorityGate.onNextCheck(() -> service.destroySession(sessionId));

    accessSession(session);

    assertThat(runtimeRegistry.findById(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should remove session and stop transcode when session is destroyed")
  void shouldRemoveSessionAndStopTranscodeWhenSessionIsDestroyed() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    service.destroySession(session.getSessionId());

    assertThat(accessSession(session)).isEmpty();
    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isFalse();
  }

  @Test
  @DisplayName("Should delete stored segments when destroy fails to stop the transcode")
  void shouldDeleteStoredSegmentsWhenDestroyFailsToStopTranscode() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    segmentStore.addSegment(session.getSessionId(), "segment0.m4s", "data".getBytes());
    transcodeExecutor.failOnStop(session.getSessionId());
    var sessionId = session.getSessionId();

    assertThatThrownBy(() -> service.destroySession(sessionId))
        .isInstanceOf(TranscodeException.class);

    assertThat(accessSession(session)).isEmpty();
    assertThat(segmentStore.segmentExists(session.getSessionId(), "segment0.m4s")).isFalse();
  }

  @Test
  @DisplayName("Should reject full transcode when at concurrency limit")
  void shouldRejectFullTranscodeWhenAtConcurrencyLimit() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());

    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      createSession(file.getId(), UUID.randomUUID(), options);
    }

    var oneMore = seedMediaFile();
    var oneMoreId = oneMore.getId();
    var profileId = UUID.randomUUID();

    assertThat(service.createSession(createStreamSessionCommand(oneMoreId, profileId, options)))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
  }

  @Test
  @DisplayName("Should not count a session against the transcode limit when suspended")
  void shouldNotCountSessionAgainstTranscodeLimitWhenSuspended() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());

    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    var sessions = new ArrayList<StreamSession>();
    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      sessions.add(createSession(file.getId(), UUID.randomUUID(), options));
    }

    var suspended = sessions.getFirst();
    suspended.setHandle(mintHandle(1L, TranscodeStatus.SUSPENDED));

    var oneMore = seedMediaFile();
    var newSession = createSession(oneMore.getId(), UUID.randomUUID(), options);

    assertThat(newSession).isNotNull();
  }

  @Test
  @DisplayName("Should allow remux sessions when at transcode concurrency limit")
  void shouldAllowRemuxSessionsWhenAtTranscodeConcurrencyLimit() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());

    var transcodeOptions =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      createSession(file.getId(), UUID.randomUUID(), transcodeOptions);
    }

    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).build());

    var remuxOptions = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), remuxOptions);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName("Should reject remux session creation when no worker slot is available")
  void shouldRejectRemuxSessionCreationWhenNoWorkerSlotIsAvailable() {
    var file = seedMediaFile();
    transcodeExecutor.setAvailableSlots(0);
    var command =
        CreateStreamSessionCommand.builder()
            .mediaFileId(file.getId())
            .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
            .options(StreamingOptions.builder().supportedCodecs(List.of("h264")).build())
            .build();

    assertThat(service.createSession(command))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getRunningCount()).isZero();
  }

  @Test
  @DisplayName("Should transcode video when video codec is incompatible")
  void shouldTranscodeVideoWhenVideoCodecIsIncompatible() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofMinutes(90))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName("Should start multiple variants when auto quality with full transcode")
  void shouldStartMultipleVariantsWhenAutoQualityWithFullTranscode() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options = defaultOptions();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants()).hasSizeGreaterThan(1);
    assertThat(session.getVariantHandles()).hasSizeGreaterThan(1);
  }

  @Test
  @DisplayName("Should roll back the session when the first transcode startup fails")
  void shouldRollbackSessionWhenFirstTranscodeStartupFails() {
    var failingExecutor = new FailingStartupTranscodeExecutor(0, segmentStore);
    service = serviceWith(failingExecutor, runtimeRegistry);
    var file = seedMediaFile();

    var command = createStreamSessionCommand(file.getId(), UUID.randomUUID(), defaultOptions());
    assertThatThrownBy(() -> service.createSession(command))
        .isInstanceOf(TranscodeException.class)
        .hasMessage("Simulated transcode startup failure");

    assertStartupRolledBack(failingExecutor);
  }

  @Test
  @DisplayName("Should roll back running transcodes when a later variant startup fails")
  void shouldRollbackRunningTranscodesWhenLaterVariantStartupFails() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var failingExecutor = new FailingStartupTranscodeExecutor(1, segmentStore);
    service = serviceWith(failingExecutor, runtimeRegistry);
    var file = seedMediaFile();

    var command = createStreamSessionCommand(file.getId(), UUID.randomUUID(), defaultOptions());
    assertThatThrownBy(() -> service.createSession(command))
        .isInstanceOf(TranscodeException.class)
        .hasMessage("Simulated transcode startup failure");

    assertThat(failingExecutor.getAttemptedRequests()).hasSize(2);
    assertStartupRolledBack(failingExecutor);
  }

  @Test
  @DisplayName("Should preserve the startup failure when rollback cleanup fails")
  void shouldPreserveStartupFailureWhenRollbackCleanupFails() {
    var failingExecutor = new FailingStartupTranscodeExecutor(0, segmentStore);
    failingExecutor.failOnStop();
    service = serviceWith(failingExecutor, runtimeRegistry);
    var file = seedMediaFile();

    var startupFailure =
        catchThrowable(() -> createSession(file.getId(), UUID.randomUUID(), defaultOptions()));

    assertThat(startupFailure)
        .isInstanceOf(TranscodeException.class)
        .hasMessage("Simulated transcode startup failure");
    assertThat(startupFailure.getSuppressed())
        .singleElement()
        .satisfies(
            cleanupFailure ->
                assertThat(cleanupFailure)
                    .isInstanceOf(TranscodeException.class)
                    .hasMessage("Simulated rollback cleanup failure"));
    assertStartupRolledBack(failingExecutor);
  }

  private void assertStartupRolledBack(FailingStartupTranscodeExecutor executor) {
    var sessionId = executor.getAttemptedRequests().getFirst().sessionId();
    assertThat(runtimeRegistry.findById(sessionId)).isEmpty();
    assertThat(executor.getRunningCount()).isZero();
    assertThat(segmentStore.segmentExists(sessionId, "startup.m4s")).isFalse();
  }

  @Test
  @DisplayName("Should use single variant when auto quality with remux")
  void shouldUseSingleVariantWhenAutoQualityWithRemux() {
    var file = seedMediaFile();
    var options = defaultOptions();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants()).isEmpty();
    assertThat(session.getHandle()).isPresent();
  }

  @Test
  @DisplayName("Should use single variant when explicit quality is specified")
  void shouldUseSingleVariantWhenExplicitQualityIsSpecified() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.HIGH_720P)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants()).isEmpty();
    assertThat(session.getHandle()).isPresent();
  }

  @Test
  @DisplayName(
      "Should pass variant label to transcode request for ABR session when managing a session")
  void shouldPassVariantLabelToTranscodeRequestForAbrSessionWhenManagingSession() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options = defaultOptions();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants()).hasSizeGreaterThan(1);
    assertThat(transcodeExecutor.getStartedVariants())
        .containsExactlyInAnyOrderElementsOf(
            session.getVariants().stream().map(v -> v.label()).toList());
  }

  @Test
  @DisplayName("Should return a session immediately when creation completes")
  void shouldReturnSessionImmediatelyWhenCreationCompletes() {
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(accessSession(session)).isPresent();
  }

  @Test
  @DisplayName("Should not throw when destroying nonexistent session")
  void shouldNotThrowWhenDestroyingNonexistentSession() {
    service.destroySession(UUID.randomUUID());

    assertThat(transcodeExecutor.getStopped()).isEmpty();
  }

  @Test
  @DisplayName("Should not destroy session when destroy requested by another profile")
  void shouldNotDestroySessionWhenDestroyRequestedByAnotherProfile() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    service.destroySession(session.getSessionId(), UUID.randomUUID());

    assertThat(accessSession(session)).isPresent();
    assertThat(transcodeExecutor.getStopped()).doesNotContain(session.getSessionId());
  }

  @Test
  @DisplayName("Should remove session and stop transcode when destroy requested by owning profile")
  void shouldRemoveSessionAndStopTranscodeWhenDestroyRequestedByOwningProfile() {
    var file = seedMediaFile();
    var profileId = UUID.randomUUID();
    var session = createSession(file.getId(), profileId, defaultOptions());

    service.destroySession(session.getSessionId(), profileId);

    assertThat(accessSession(session)).isEmpty();
    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
  }

  @Test
  @DisplayName("Should return all sessions when multiple sessions created")
  void shouldReturnAllSessionsWhenMultipleSessionsCreated() {
    var file1 = seedMediaFile();
    var file2 = seedMediaFile();
    createSession(file1.getId(), UUID.randomUUID(), defaultOptions());
    createSession(file2.getId(), UUID.randomUUID(), defaultOptions());

    var all = service.getAllSessions();

    assertThat(all).hasSize(2);
  }

  @Test
  @DisplayName("Should return active session count when sessions exist")
  void shouldReturnActiveSessionCountWhenSessionsExist() {
    var file1 = seedMediaFile();
    var file2 = seedMediaFile();
    var session1 = createSession(file1.getId(), UUID.randomUUID(), defaultOptions());
    createSession(file2.getId(), UUID.randomUUID(), defaultOptions());

    service.destroySession(session1.getSessionId());

    assertThat(service.getActiveSessionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should truncate variants when exceeding available slots")
  void shouldTruncateVariantsWhenExceedingAvailableSlots() {
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(2)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    var limitedExecutor = new FakeTranscodeExecutor();
    var limitedRegistry = new FakeRuntimeStreamSessionRegistry();
    var limitedRig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(limitedExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(limitedRegistry)
            .build();
    var limitedService =
        HlsStreamingService.builder()
            .mediaFileRepository(mediaFileRepository)
            .transcodeExecutor(limitedExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(
                new PlaybackProbeService(new PersistedProbeReader(probeResults), probeEvents))
            .transcodeDecisionService(new TranscodeDecisionService())
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate(authorityGate)
            .runtimeRegistry(limitedRegistry)
            .producerLifecycle(limitedRig.lifecycle())
            .deliveryCoordinator(limitedRig.coordinator())
            .build();

    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options = defaultOptions();

    var session =
        accepted(
            limitedService.createSession(
                createStreamSessionCommand(file.getId(), UUID.randomUUID(), options)));

    assertThat(session.getVariants()).hasSize(2);
  }

  @Test
  @DisplayName("Should truncate variants to executor slots available now when managing a session")
  void shouldTruncateVariantsToExecutorSlotsAvailableNowWhenManagingSession() {
    transcodeExecutor.setAvailableSlots(2);
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var file = seedMediaFile();
    var options = defaultOptions();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants()).hasSize(2);
    assertThat(transcodeExecutor.getStartedVariants()).containsExactlyInAnyOrder("1080p", "720p");
  }

  @Test
  @DisplayName("Should truncate to one variant when only one slot is available")
  void shouldTruncateToOneVariantWhenOnlyOneSlotAvailable() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());

    var singleVariantOptions =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    for (int i = 0; i < 2; i++) {
      var file = seedMediaFile();
      createSession(file.getId(), UUID.randomUUID(), singleVariantOptions);
    }

    var abrOptions = defaultOptions();
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), abrOptions);

    assertThat(session.getVariants()).hasSize(1);
  }

  @Test
  @DisplayName("Should reject ABR session when all transcode slots are full")
  void shouldRejectAbrSessionWhenAllTranscodeSlotsAreFull() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());

    var singleVariantOptions =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      createSession(file.getId(), UUID.randomUUID(), singleVariantOptions);
    }

    var abrOptions = defaultOptions();
    var abrFile = seedMediaFile();
    var abrFileId = abrFile.getId();
    var profileId = UUID.randomUUID();

    assertThat(service.createSession(createStreamSessionCommand(abrFileId, profileId, abrOptions)))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
  }

  private static final class FailingStartupTranscodeExecutor extends FakeTranscodeExecutor {

    private final int successfulStarts;
    private final FakeSegmentStore segmentStore;
    private boolean failOnStop;
    private final List<TranscodeRequest> attemptedRequests = new ArrayList<>();

    private FailingStartupTranscodeExecutor(int successfulStarts, FakeSegmentStore segmentStore) {
      this.successfulStarts = successfulStarts;
      this.segmentStore = segmentStore;
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      attemptedRequests.add(request);
      segmentStore.addSegment(request.sessionId(), "startup.m4s", new byte[] {1});
      if (attemptedRequests.size() > successfulStarts) {
        throw new TranscodeException("Simulated transcode startup failure");
      }
      return super.start(request);
    }

    @Override
    public void stop(UUID sessionId) {
      super.stop(sessionId);
      if (failOnStop) {
        throw new TranscodeException("Simulated rollback cleanup failure");
      }
    }

    private void failOnStop() {
      failOnStop = true;
    }

    private List<TranscodeRequest> getAttemptedRequests() {
      return List.copyOf(attemptedRequests);
    }
  }
}
