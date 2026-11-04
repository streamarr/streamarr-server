package com.streamarr.server.services.streaming;

import static com.streamarr.server.domain.streaming.ProbedCodecFamily.AV1;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.H264;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.HEVC;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.directPlaySessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.fixtures.StreamSessionFixture.mintHandle;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.av1Nvenc;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.hevcNvenc;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.libsvtav1;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.libx264;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.libx265;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.OutcomeTestSupport.rejectionOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.LanguageOverrideChange;
import com.streamarr.server.domain.language.OverrideFieldChange;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.AudioStreamProperties;
import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ChannelLayout;
import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContainerCropping;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.Deinterlace;
import com.streamarr.server.domain.streaming.DeinterlaceOutput;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncodeFormat;
import com.streamarr.server.domain.streaming.ExactSegmentStarts;
import com.streamarr.server.domain.streaming.FieldOrder;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoEncodeFeature;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoSampleEntry;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeLanguagePreferenceRepository;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HardwareEncodedFamiliesAndExpectedEncode;
import com.streamarr.server.fixtures.MediaEntityFixture;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamMeasurementFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.language.LanguagePreferenceService;
import com.streamarr.server.services.language.SetLanguageOverrideCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@DisplayName("HLS Streaming Service Tests")
class HlsStreamingServiceTest {

  private static final StreamingOptions OVER_THE_MAXIMUM_BITRATE =
      StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(14_333_000).build();

  private static final String MISSING_ENCODER_SESSIONS_METRIC =
      "streamarr.streaming.missing_encoder_sessions";

  private static final ExecutionTargetId AV1_WORKER = new ExecutionTargetId("av1-worker");
  private static final ExecutionTargetId H264_WORKER = new ExecutionTargetId("h264-worker");

  private FakeMediaFileRepository mediaFileRepository;
  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeMediaFileContainerInfoRepository probeResults;
  private CapturingEventPublisher probeEvents;
  private FakePlaybackAuthorityGate authorityGate;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private HlsStreamingService service;
  private SegmentDeliveryCoordinator coordinator;
  private SimpleMeterRegistry meters;
  private PlaybackLanguagePreferences languagePreferences = (profileId, titleId) -> List.of();
  private final MutexFactory<UUID> sessionMutex = new MutexFactory<>();

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
  @CsvSource({"h264", "hevc"})
  @DisplayName("Should report transient capacity when no worker can access the source")
  void shouldReportTransientCapacityWhenNoWorkerCanAccessTheSource(String videoCodec) {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec(videoCodec).build());
    transcodeExecutor.setHealthy(false);
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of());
    transcodeExecutor.setAvailableSlots(0);
    var command =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThat(service.createSession(command))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
    assertThat(capacityRefusals("no_connected_worker")).isOne();
    assertThat(missingEncoderSessions()).isZero();
  }

  @Test
  @DisplayName("Should reject an encode when a connected worker has no verified video encoder")
  void shouldRejectAnEncodeWhenAConnectedWorkerHasNoVerifiedVideoEncoder() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    transcodeExecutor.setHealthy(true);
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of());
    transcodeExecutor.setAvailableSlots(1);
    var command =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThat(service.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  @Test
  @DisplayName("Should copy video when a connected worker has no verified video encoder")
  void shouldCopyVideoWhenAConnectedWorkerHasNoVerifiedVideoEncoder() {
    transcodeExecutor.setHealthy(true);
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of());
    transcodeExecutor.setAvailableSlots(1);
    var command =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    var session = accepted(service.createSession(command));

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(service.getAllSessions()).containsExactly(session);
  }

  @Test
  @DisplayName("Should count one copy refusal when every video encoder is busy")
  void shouldCountOneCopyRefusalWhenEveryVideoEncoderIsBusy() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().keyframes(new KeyframeKnowledge.NotReported()).build());
    transcodeExecutor.setEncodeSlots(Map.of("h264", 0));
    var command =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThat(service.createSession(command))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(meters.get("streamarr.streaming.stream_copy_refusals").counter().count())
        .isEqualTo(1.0);
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"0", "1"})
  @DisplayName("Should reject a stream session when no video format is eligible")
  void shouldRejectAStreamSessionWhenNoVideoFormatIsEligible(int availableSlots) {
    transcodeExecutor.setAvailableSlots(availableSlots);
    var file = seedMediaFile();
    var command =
        createStreamSessionCommandBuilder(file.getId(), UUID.randomUUID())
            .capabilityDeclaration(directPlayOnlyHevcDeclaration())
            .build();

    var outcome = service.createSession(command);

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("missingEncoderCases")
  @DisplayName(
      "Should count the missing encoder of the codec family the server would encode when no connected worker has one")
  void shouldCountTheMissingEncoderOfTheCodecFamilyTheServerWouldEncodeWhenNoConnectedWorkerHasOne(
      MissingEncoderCase missingEncoder) {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("vp9").build());
    connectWorkerEncoding(missingEncoder.workerCodecFamilies());

    var outcome =
        service.createSession(
            createCommandDeclaring(
                declarationBuilderFor(
                        missingEncoder.acceptedCodecFamilies().toArray(ProbedCodecFamily[]::new))
                    .build()));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(missingEncoderSessions(missingEncoder.expectedCodecFamily())).isOne();
    assertThat(missingEncoderSessions()).isOne();
  }

  // Without a hardware encoder the server prefers H.264 (ADR 0009).
  private static Stream<MissingEncoderCase> missingEncoderCases() {
    return Stream.of(
        MissingEncoderCase.builder()
            .name("H.264 accepted, worker encodes AV1 alone")
            .acceptedCodecFamilies(List.of(H264))
            .workerCodecFamilies(Set.of("av1"))
            .expectedCodecFamily("h264")
            .build(),
        MissingEncoderCase.builder()
            .name("AV1 accepted, worker encodes H.264 alone")
            .acceptedCodecFamilies(List.of(AV1))
            .workerCodecFamilies(Set.of("h264"))
            .expectedCodecFamily("av1")
            .build(),
        MissingEncoderCase.builder()
            .name("AV1 and H.264 accepted, worker encodes nothing")
            .acceptedCodecFamilies(List.of(AV1, H264))
            .workerCodecFamilies(Set.of())
            .expectedCodecFamily("h264")
            .build(),
        MissingEncoderCase.builder()
            .name("AV1 and H.264 accepted, worker encodes HEVC alone")
            .acceptedCodecFamilies(List.of(AV1, H264))
            .workerCodecFamilies(Set.of("hevc"))
            .expectedCodecFamily("h264")
            .build());
  }

  @Builder
  private record MissingEncoderCase(
      String name,
      List<ProbedCodecFamily> acceptedCodecFamilies,
      Set<String> workerCodecFamilies,
      String expectedCodecFamily) {

    @Override
    public String toString() {
      return name;
    }
  }

  // A worker with a busy H.264 encoder connects right after the stream session reads the connected
  // workers. That read refused the stream session for the missing encoder, so the count follows it.
  @Test
  @DisplayName(
      "Should count the missing encoder that refused the stream session when a worker with that"
          + " encoder connects right after the workers are read")
  void
      shouldCountTheMissingEncoderThatRefusedTheStreamSessionWhenAWorkerWithThatEncoderConnectsRightAfterTheWorkersAreRead() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("vp9").build());
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of());
    transcodeExecutor.changeWorkersAroundNextVerifiedFamiliesRead(
        () -> {},
        () ->
            transcodeExecutor.setVerifiedVideoCodecFamiliesByWorker(
                List.of(connectedWorker(Set.of()), connectedWorker(Set.of("h264")))));

    var outcome =
        service.createSession(createCommandDeclaring(declarationBuilderFor(H264).build()));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(missingEncoderSessions("h264")).isOne();
    assertThat(missingEncoderSessions()).isOne();
  }

  @Test
  @DisplayName(
      "Should refuse for a missing worker without counting a missing encoder when the read lists no"
          + " connected worker")
  void
      shouldRefuseForAMissingWorkerWithoutCountingAMissingEncoderWhenTheReadListsNoConnectedWorker() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("vp9").build());
    // The connectivity check still sees the worker, which left before the workers were read.
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of());
    transcodeExecutor.setVerifiedVideoCodecFamiliesByWorker(List.of());

    var outcome =
        service.createSession(
            createStreamSessionCommand(
                seedMediaFile().getId(), UUID.randomUUID(), defaultOptions()));

    assertThat(outcome)
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(capacityRefusals("no_connected_worker")).isOne();
    assertThat(missingEncoderSessions()).isZero();
  }

  @Test
  @DisplayName("Should not count a missing encoder when the worker's accepted encoder is busy")
  void shouldNotCountAMissingEncoderWhenTheWorkersAcceptedEncoderIsBusy() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("vp9").build());
    transcodeExecutor.setEncodeSlots(Map.of("h264", 0));
    transcodeExecutor.setVerifiedVideoCodecFamiliesByWorker(
        List.of(connectedWorker(Set.of("h264"))));

    var outcome =
        service.createSession(
            createStreamSessionCommand(
                seedMediaFile().getId(), UUID.randomUUID(), defaultOptions()));

    assertThat(outcome)
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(missingEncoderSessions()).isZero();
  }

  @Test
  @DisplayName(
      "Should not count a missing encoder when the declaration accepts no codec family that the server encodes")
  void shouldNotCountAMissingEncoderWhenTheDeclarationAcceptsNoCodecFamilyThatTheServerEncodes() {
    connectWorkerEncoding(Set.of());

    var outcome = service.createSession(createCommandDeclaring(directPlayOnlyHevcDeclaration()));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(missingEncoderSessions()).isZero();
  }

  // The read saw the worker's busy H.264 encoder. The worker's connection then flaps while the
  // server refuses the stream session: each connectivity check after the read answers the
  // opposite of the one before it, starting disconnected.
  @Test
  @DisplayName(
      "Should refuse for a busy encoder without counting a missing encoder when the worker's"
          + " connection flaps during the refusal")
  void
      shouldRefuseForABusyEncoderWithoutCountingAMissingEncoderWhenTheWorkersConnectionFlapsDuringTheRefusal() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("vp9").build());
    var executor = new WorkerWithAFlappingConnection();
    executor.setEncodeSlots(Map.of("h264", 0));
    var flappingService = serviceWith(executor, runtimeRegistry);

    var outcome =
        flappingService.createSession(createCommandDeclaring(declarationBuilderFor(H264).build()));

    assertThat(outcome)
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(capacityRefusals("no_encoder_with_free_slot")).isOne();
    assertThat(missingEncoderSessions()).isZero();
  }

  private static final class WorkerWithAFlappingConnection extends FakeTranscodeExecutor {

    private final AtomicBoolean read = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean(true);

    @Override
    public List<WorkerVerifiedCodecFamilies> verifiedVideoCodecFamiliesByWorker() {
      read.set(true);
      return super.verifiedVideoCodecFamiliesByWorker();
    }

    @Override
    public boolean isHealthy() {
      if (!read.get()) {
        return super.isHealthy();
      }

      return !connected.getAndSet(!connected.get());
    }
  }

  @Test
  @DisplayName(
      "Should not count a missing encoder when the encode it would run has no probed frame rate")
  void shouldNotCountAMissingEncoderWhenTheEncodeItWouldRunHasNoProbedFrameRate() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("vp9").framerate(OptionalDouble.empty()).build());
    connectWorkerEncoding(Set.of());

    var outcome =
        service.createSession(
            createStreamSessionCommand(
                seedMediaFile().getId(), UUID.randomUUID(), defaultOptions()));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(missingEncoderSessions()).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("encodesThatNeedAFrameRate")
  @DisplayName(
      "Should reject the stream session when the video must be encoded and the probe has no frame"
          + " rate")
  void shouldRejectTheStreamSessionWhenTheVideoMustBeEncodedAndTheProbeHasNoFrameRate(
      EncodeCase encode) {
    var probe = defaultProbeBuilder().videoCodec("hevc").audioCodec(encode.audioCodec());
    var options = StreamingOptions.builder().quality(encode.quality()).build();
    var file = seedMediaFile();
    var profileId = UUID.randomUUID();
    probeResults.setDefaultProbe(probe.framerate(OptionalDouble.of(23.976)).build());
    var encoded = createSession(file.getId(), profileId, options);
    service.destroySession(encoded.getSessionId());
    var startedRequests = List.copyOf(transcodeExecutor.getStartedRequests());
    probeResults.setDefaultProbe(probe.framerate(OptionalDouble.empty()).build());

    var outcome =
        service.createSession(createStreamSessionCommand(file.getId(), profileId, options));

    assertThat(encoded.getTranscodeDecision().transcodeMode()).isEqualTo(encode.encodingMode());
    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.FrameRateUnknown()));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEqualTo(startedRequests);
  }

  static Stream<EncodeCase> encodesThatNeedAFrameRate() {
    return Stream.of(
        EncodeCase.builder()
            .audioCodec("aac")
            .quality(VideoQuality.AUTO)
            .encodingMode(TranscodeMode.VIDEO_TRANSCODE)
            .build(),
        EncodeCase.builder()
            .audioCodec("flac")
            .quality(VideoQuality.HIGH_720P)
            .encodingMode(TranscodeMode.FULL_TRANSCODE)
            .build());
  }

  @Builder
  record EncodeCase(String audioCodec, VideoQuality quality, TranscodeMode encodingMode) {}

  @Test
  @DisplayName(
      "Should refuse an encode for its unknown frame rate when the probe outcome names no output"
          + " frame rate")
  void shouldRefuseAnEncodeForItsUnknownFrameRateWhenTheProbeOutcomeNamesNoOutputFrameRate() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").videoPlaybackFacts(Optional.empty()).build());
    var command =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThat(service.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.FrameRateUnknown()));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "HIGH_720P"})
  @DisplayName(
      "Should refuse an encode for its unknown frame rate before sizing a variant when the picture"
          + " is also too short to encode")
  void
      shouldRefuseAnEncodeForItsUnknownFrameRateBeforeSizingAVariantWhenThePictureIsAlsoTooShortToEncode(
          VideoQuality quality) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .height(1)
            .framerate(OptionalDouble.empty())
            .build());
    var options = StreamingOptions.builder().quality(quality).build();
    var command = createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(service.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.FrameRateUnknown()));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("picturesWithoutArea")
  @DisplayName("Should reject session creation when the displayed picture has no area")
  void shouldRejectSessionCreationWhenTheDisplayedPictureHasNoArea(
      PictureSize picture, VideoQuality quality) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .width(picture.width())
            .height(picture.height())
            .build());
    var options = StreamingOptions.builder().quality(quality).build();
    var command = createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(service.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.InvalidPictureSize()));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  // An encode codec counts by its codec profile alone when the worker encodes no picture of the
  // source, so a client that lists no encode codec profile has no encode at all.
  @Test
  @DisplayName(
      "Should reject the session as having no eligible format when no entry lists an encode codec"
          + " profile and the worker encodes no picture of the source")
  void
      shouldRejectTheSessionAsHavingNoEligibleFormatWhenNoEntryListsAnEncodeCodecProfileAndTheWorkerEncodesNoPictureOfTheSource() {
    probeResults.setDefaultProbe(defaultProbeBuilder().width(1).height(3).build());

    var outcome =
        service.createSession(createCommandDeclaring(hevcMain10OnlyDeclarationBuilder().build()));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  static Stream<Arguments> picturesWithoutArea() {
    var pictures =
        List.of(
            PictureSize.builder().width(0).height(0).build(),
            PictureSize.builder().width(0).height(1080).build(),
            PictureSize.builder().width(1920).height(0).build());
    return Stream.of(VideoQuality.AUTO, VideoQuality.HIGH_720P)
        .flatMap(quality -> pictures.stream().map(picture -> Arguments.of(picture, quality)));
  }

  @Builder
  record PictureSize(int width, int height) {}

  @ParameterizedTest
  @CsvSource({"CHROMA_444, 1916", "CHROMA_422, 1914"})
  @DisplayName(
      "Should plan even output dimensions when a fixed quality encodes an odd cropped picture")
  void shouldPlanEvenOutputDimensionsWhenAFixedQualityEncodesAnOddCroppedPicture(
      ChromaSubsampling subsampling, int expectedWidth) {
    probeResults.setDefaultProbe(oddCroppedProbe(subsampling).build());
    var options = StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "test-token");

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.height()).isEqualTo(1076);
              assertThat(request.width()).isEqualTo(expectedWidth);
            });
    assertThat(playlist).contains(",RESOLUTION=" + expectedWidth + "x1076,");
    assertThat(session.getMediaProbe().displayedSize().height()).isEqualTo(1077);
  }

  @ParameterizedTest
  @CsvSource({"CHROMA_444, 316", "CHROMA_422, 314"})
  @DisplayName(
      "Should plan even output dimensions when Auto encodes a source smaller than the 360p tier's"
          + " box")
  void shouldPlanEvenOutputDimensionsWhenAutoEncodesASourceSmallerThanThe360pTiersBox(
      ChromaSubsampling subsampling, int expectedWidth) {
    probeResults.setDefaultProbe(oddCroppedProbe(subsampling).width(320).height(240).build());

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "test-token");

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.height()).isEqualTo(236);
              assertThat(request.width()).isEqualTo(expectedWidth);
            });
    assertThat(playlist).contains(",RESOLUTION=" + expectedWidth + "x236,");
    assertThat(session.getMediaProbe().displayedSize().height()).isEqualTo(237);
  }

  @ParameterizedTest
  @CsvSource({"AUTO", "HIGH_720P"})
  @DisplayName("Should reject an encode when the displayed height cannot contain a chroma sample")
  void shouldRejectAnEncodeWhenTheDisplayedHeightCannotContainAChromaSample(VideoQuality quality) {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").height(1).build());
    var options = StreamingOptions.builder().quality(quality).build();
    var command = createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(service.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.InvalidPictureSize()));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
    assertThat(service.getActiveSessionCount()).isZero();
  }

  @ParameterizedTest
  @CsvSource({"AUTO, 3", "HIGH_720P, 3"})
  @DisplayName("Should reject an encode when every planned output width rounds to zero")
  void shouldRejectAnEncodeWhenEveryPlannedOutputWidthRoundsToZero(
      VideoQuality quality, int height) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(1).height(height).build());
    var options = StreamingOptions.builder().quality(quality).build();
    var command = createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(service.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.InvalidPictureSize()));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
    assertThat(service.getActiveSessionCount()).isZero();
  }

  @Test
  @DisplayName(
      "Should encode the next codec family when the preferred family's entries shrink the source"
          + " below the smallest picture")
  void
      shouldEncodeTheNextCodecFamilyWhenThePreferredFamilysEntriesShrinkTheSourceBelowTheSmallestPicture() {
    transcodeExecutor.setHardwareEncodableVideoCodecFamilies(Set.of("av1"));
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(4096).height(2).build());
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    av1EntryBuilder().maxWidth(1280).maxHeight(720).build(),
                    h264EntryBuilder().maxWidth(4096).build()))
            .build();

    var outcome = service.createSession(createCommandDeclaring(declaration));

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.transcodeDecision().videoCodecFamily()).isEqualTo("h264");
              assertThat(request.width()).isEqualTo(4096);
              assertThat(request.height()).isEqualTo(2);
            });
  }

  @Test
  @DisplayName(
      "Should reject the session as having no eligible format when every entry shrinks the source"
          + " below the smallest picture")
  void
      shouldRejectTheSessionAsHavingNoEligibleFormatWhenEveryEntryShrinksTheSourceBelowTheSmallestPicture() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(4096).height(2).build());
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(1280).maxHeight(720).build()))
            .build();

    var outcome = service.createSession(createCommandDeclaring(declaration));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
    assertThat(service.getActiveSessionCount()).isZero();
  }

  @ParameterizedTest
  @MethodSource("viewerMaximumsThatLeaveNoPicture")
  @DisplayName(
      "Should reject an encode as an invalid picture size when the viewer's maximums leave no"
          + " picture")
  void shouldRejectAnEncodeAsAnInvalidPictureSizeWhenTheViewersMaximumsLeaveNoPicture(
      StreamingOptions options) {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    var command = createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(service.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.InvalidPictureSize()));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
    assertThat(service.getActiveSessionCount()).isZero();
  }

  // The worker encodes no picture below two pixels in either direction.
  static Stream<StreamingOptions> viewerMaximumsThatLeaveNoPicture() {
    return Stream.of(VideoQuality.AUTO, VideoQuality.FULL_HD_1080P)
        .flatMap(
            quality ->
                Stream.of(
                    StreamingOptions.builder().quality(quality).maxHeight(1).build(),
                    StreamingOptions.builder().quality(quality).maxWidth(1).build()));
  }

  @Test
  @DisplayName("Should encode at the viewer's maximum height when it is below every Auto tier")
  void shouldEncodeAtTheViewersMaximumHeightWhenItIsBelowEveryAutoTier() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).maxHeight(300).build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants())
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(tuple(534, 300, "300p"));
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(534);
              assertThat(request.height()).isEqualTo(300);
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("budgetsForASourceWithoutABitrate")
  @DisplayName(
      "Should dispatch a positive bitrate and advertise it in the playlist when the probe names no"
          + " source bitrate")
  void shouldDispatchAPositiveBitrateAndAdvertiseItInThePlaylistWhenTheProbeNamesNoSourceBitrate(
      UnknownSourceBitrate budget) {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").bitrate(0).build());

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), budget.options());
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    var audioBitrate = session.getTranscodeDecision().audioDecision().bitrate();
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(request -> assertThat(request.bitrate()).isEqualTo(budget.videoBitrate()));
    assertThat(playlist)
        .contains(",AVERAGE-BANDWIDTH=" + (budget.videoBitrate() + audioBitrate) + ",");
  }

  // ADR 0050: without a source bitrate, a single variant takes the nominal bitrate of the tier that
  // holds its picture within the maximum bitrate less 128 kb/s of audio. When no audio step leaves
  // the 360p tier's bitrate, the audio takes 96 kb/s and the video the rest, but at least 145 kb/s.
  static Stream<UnknownSourceBitrate> budgetsForASourceWithoutABitrate() {
    return Stream.of(
        unknownSourceBitrate(fixed1080p().build(), 5_000_000L),
        unknownSourceBitrate(fixed1080p().maxBitrate(20_000_000).build(), 5_000_000L),
        unknownSourceBitrate(fixed1080p().maxBitrate(2_000_000).build(), 1_872_000L),
        unknownSourceBitrate(fixed1080p().maxBitrate(700_000).build(), 604_000L),
        unknownSourceBitrate(fixed1080p().maxBitrate(100_000).build(), 145_000L),
        unknownSourceBitrate(autoAt300Lines().build(), 800_000L),
        unknownSourceBitrate(autoAt300Lines().maxBitrate(1_000_000).build(), 800_000L),
        unknownSourceBitrate(autoAt300Lines().maxBitrate(20_000_000).build(), 800_000L));
  }

  private static StreamingOptions.StreamingOptionsBuilder fixed1080p() {
    return StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P);
  }

  private static StreamingOptions.StreamingOptionsBuilder autoAt300Lines() {
    return StreamingOptions.builder().quality(VideoQuality.AUTO).maxHeight(300);
  }

  private static UnknownSourceBitrate unknownSourceBitrate(
      StreamingOptions options, long videoBitrate) {
    return UnknownSourceBitrate.builder().options(options).videoBitrate(videoBitrate).build();
  }

  @Builder
  record UnknownSourceBitrate(StreamingOptions options, long videoBitrate) {}

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixedQualitiesAndTheirTiersBitrate")
  @DisplayName(
      "Should encode a fixed quality at the nominal bitrate of the tier that holds the encoded"
          + " picture, capped by the source's known bitrate, when the viewer sets no maximum"
          + " bitrate")
  void
      shouldEncodeAFixedQualityAtTheNominalBitrateOfTheTierThatHoldsTheEncodedPictureCappedByTheSourcesKnownBitrateWhenTheViewerSetsNoMaximumBitrate(
          FixedQualitySource fixedQuality) {
    var source = fixedQuality.source();
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .width(source.width())
            .height(source.height())
            .bitrate(fixedQuality.sourceBitrate())
            .build());
    var options = StreamingOptions.builder().quality(fixedQuality.quality()).build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(request -> assertThat(request.bitrate()).isEqualTo(fixedQuality.videoBitrate()));
  }

  // The 2160p tier's H.264 nominal bitrate is 20 Mb/s, and the 1080p tier's is 5 Mb/s.
  static Stream<FixedQualitySource> fixedQualitiesAndTheirTiersBitrate() {
    var uhd = new PictureSize(3840, 2160);
    return Stream.of(
        fixedQualitySource().quality(VideoQuality.LOW_360P).videoBitrate(800_000L).build(),
        fixedQualitySource().quality(VideoQuality.HIGH_720P).videoBitrate(3_000_000L).build(),
        fixedQualitySource()
            .quality(VideoQuality.FULL_HD_1080P)
            .sourceBitrate(2_000_000L)
            .videoBitrate(2_000_000L)
            .build(),
        fixedQualitySource().quality(VideoQuality.UHD_4K).videoBitrate(5_000_000L).build(),
        fixedQualitySource()
            .quality(VideoQuality.UHD_4K)
            .sourceBitrate(0L)
            .videoBitrate(5_000_000L)
            .build(),
        fixedQualitySource()
            .quality(VideoQuality.UHD_4K)
            .source(uhd)
            .sourceBitrate(40_000_000L)
            .videoBitrate(20_000_000L)
            .build(),
        fixedQualitySource()
            .quality(VideoQuality.UHD_4K)
            .source(uhd)
            .videoBitrate(8_000_000L)
            .build());
  }

  // An 8 Mb/s 1920×1080 source.
  private static FixedQualitySource.FixedQualitySourceBuilder fixedQualitySource() {
    return FixedQualitySource.builder()
        .source(new PictureSize(1920, 1080))
        .sourceBitrate(8_000_000L);
  }

  @Builder
  record FixedQualitySource(
      VideoQuality quality, PictureSize source, long sourceBitrate, long videoBitrate) {}

  // ADR 0050: a variant costs its video bitrate plus the stream session's audio bitrate, here a
  // 128 kb/s stereo AAC encode.
  @ParameterizedTest(name = "{0}")
  @MethodSource("budgetsThatHoldThe360pTiersBitrateBesideTheAudio")
  @DisplayName(
      "Should encode the single variant within the maximum bitrate less the audio when that leaves"
          + " at least the 360p tier's bitrate")
  void
      shouldEncodeTheSingleVariantWithinTheMaximumBitrateLessTheAudioWhenThatLeavesAtLeastThe360pTiersBitrate(
          SingleVariantOutcome expected) {
    assertSingleVariantAndItsBandwidth(expected);
  }

  static Stream<SingleVariantOutcome> budgetsThatHoldThe360pTiersBitrateBesideTheAudio() {
    return Stream.of(
        SingleVariantOutcome.builder()
            .options(fixed1080p().maxBitrate(2_000_000).build())
            .picture(new PictureSize(1920, 1080))
            .videoBitrate(1_872_000L)
            .audioBitrate(128_000L)
            .build(),
        SingleVariantOutcome.builder()
            .options(fixed1080p().maxBitrate(1_000_000).build())
            .picture(new PictureSize(1920, 1080))
            .videoBitrate(872_000L)
            .audioBitrate(128_000L)
            .build(),
        SingleVariantOutcome.builder()
            .options(
                StreamingOptions.builder()
                    .quality(VideoQuality.AUTO)
                    .maxWidth(500)
                    .maxBitrate(1_000_000)
                    .build())
            .picture(new PictureSize(498, 280))
            .videoBitrate(872_000L)
            .audioBitrate(128_000L)
            .build());
  }

  // Under 896 kb/s, 800 kb/s of video plus 96 kb/s of AAC, no audio step leaves the 360p tier's
  // bitrate. The audio takes 96 kb/s and the video the rest, but at least 145 kb/s, because an
  // encoder reads a zero bitrate as no limit (ADR 0050).
  @ParameterizedTest(name = "{0}")
  @MethodSource("budgetsBelowThe360pTiersBitrateBesideTheAudio")
  @DisplayName(
      "Should encode the single variant in the 360p box beside the lowest audio step when no audio"
          + " step leaves the 360p tier's bitrate")
  void
      shouldEncodeTheSingleVariantInThe360pBoxBesideTheLowestAudioStepWhenNoAudioStepLeavesThe360pTiersBitrate(
          SingleVariantOutcome expected) {
    assertSingleVariantAndItsBandwidth(expected);
  }

  static Stream<SingleVariantOutcome> budgetsBelowThe360pTiersBitrateBesideTheAudio() {
    var box360p = new PictureSize(640, 360);
    return Stream.of(
        SingleVariantOutcome.builder()
            .options(fixed1080p().maxBitrate(700_000).build())
            .picture(box360p)
            .videoBitrate(604_000L)
            .audioBitrate(96_000L)
            .build(),
        SingleVariantOutcome.builder()
            .options(fixed1080p().maxBitrate(100_000).build())
            .picture(box360p)
            .videoBitrate(145_000L)
            .audioBitrate(96_000L)
            .build(),
        SingleVariantOutcome.builder()
            .options(
                StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(500_000).build())
            .picture(box360p)
            .videoBitrate(404_000L)
            .audioBitrate(96_000L)
            .build());
  }

  // The 8 Mb/s source's job attempt and its BANDWIDTH and AVERAGE-BANDWIDTH, which add the audio
  // and allow a fifth above the average.
  private void assertSingleVariantAndItsBandwidth(SingleVariantOutcome expected) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").bitrate(8_000_000L).build());

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), expected.options());
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().audioDecision().bitrate())
        .isEqualTo(expected.audioBitrate());
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(new PictureSize(request.width(), request.height()))
                  .isEqualTo(expected.picture());
              assertThat(request.bitrate()).isEqualTo(expected.videoBitrate());
            });
    var averageBandwidth = expected.videoBitrate() + expected.audioBitrate();
    assertThat(playlist)
        .contains(
            "BANDWIDTH="
                + (averageBandwidth + averageBandwidth / 5)
                + ",AVERAGE-BANDWIDTH="
                + averageBandwidth
                + ",");
  }

  @Builder
  record SingleVariantOutcome(
      StreamingOptions options, PictureSize picture, long videoBitrate, long audioBitrate) {}

  @ParameterizedTest(name = "{0}")
  @MethodSource("audioEncodesAndTheTiersTheyLeave")
  @DisplayName(
      "Should offer a tier only when its video bitrate plus the session's audio bitrate is within"
          + " the viewer's maximum bitrate")
  void shouldOfferATierOnlyWhenItsVideoPlusAudioBitrateIsWithinTheViewersMaximumBitrate(
      TiersBesideEncodedAudio expected) {
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .maxBitrate(expected.maxBitrate())
            .build();

    var session = createDtsSession(expected.sourceChannels(), options);
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().audioDecision().bitrate())
        .isEqualTo(expected.audioBitrate());
    assertThat(session.getVariants())
        .extracting(QualityVariant::label)
        .containsExactlyInAnyOrderElementsOf(expected.labels());
    assertThat(averageBandwidths(playlist))
        .isNotEmpty()
        .allSatisfy(bandwidth -> assertThat(bandwidth).isLessThanOrEqualTo(expected.maxBitrate()));
  }

  // A stereo DTS source encodes 128 kb/s AAC, and a 7.1 one 768 kb/s E-AC-3 5.1. 720p's 3 Mb/s
  // fits 3.1 Mb/s only without audio, and 480p's 1.5 Mb/s fits 1.8 Mb/s only beside less than 300
  // kb/s, which no multichannel step is.
  static Stream<TiersBesideEncodedAudio> audioEncodesAndTheTiersTheyLeave() {
    var stereo = TiersBesideEncodedAudio.builder().sourceChannels(2).audioBitrate(128_000L);
    var surround = TiersBesideEncodedAudio.builder().sourceChannels(8).audioBitrate(768_000L);
    return Stream.of(
        stereo.maxBitrate(3_100_000).labels(List.of("480p", "360p")).build(),
        stereo.maxBitrate(1_800_000).labels(List.of("480p", "360p")).build(),
        surround.maxBitrate(1_800_000).labels(List.of("360p")).build());
  }

  @Builder
  record TiersBesideEncodedAudio(
      int sourceChannels, long audioBitrate, int maxBitrate, List<String> labels) {}

  @ParameterizedTest
  @CsvSource({"2, 128000", "8, 768000"})
  @DisplayName(
      "Should advertise a BANDWIDTH of at least the video plus audio bitrate for each encoded"
          + " variant when the audio is encoded")
  void
      shouldAdvertiseABandwidthOfAtLeastTheVideoPlusAudioBitrateForEachEncodedVariantWhenTheAudioIsEncoded(
          int sourceChannels, long audioBitrate) {
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).build();

    var session = createDtsSession(sourceChannels, options);
    var peakBandwidths =
        peakBandwidthsByVariant(
            new HlsPlaylistService(streamingProperties())
                .generateMultivariantPlaylist(session, "t"));

    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(audioBitrate);
    assertThat(session.getVariants())
        .isNotEmpty()
        .hasSize(peakBandwidths.size())
        .allSatisfy(
            variant ->
                assertThat(peakBandwidths.get(variant.label()))
                    .isGreaterThanOrEqualTo(variant.videoBitrate() + audioBitrate));
  }

  // ADR 0050: an AAC encode has at most six channels, so a 7.1 track encodes AAC 5.1 at AAC's 5.1
  // bitrate even when the client's AAC entry accepts 8 channels, and the job names that layout.
  @Test
  @DisplayName(
      "Should encode AAC 5.1 and name its layout in the job when the client's AAC entry accepts 8"
          + " channels")
  void shouldEncodeAac51AndNameItsLayoutInTheJobWhenTheClientsAacEntryAccepts8Channels() {
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbe(
            defaultProbeBuilder()
                .videoCodec("hevc")
                .audioCodec("dts")
                .audioChannels(OptionalInt.of(8))
                .audioChannelLayout(Optional.of("7.1"))
                .build()));
    var declaration =
        defaultDeclarationBuilder()
            .audio(List.of(aacEntryBuilder().maxChannels(8).build()))
            .build();

    var session =
        accepted(
            service.createSession(
                createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                    .capabilityDeclaration(declaration)
                    .build()));
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(request -> request.transcodeDecision().audioDecision())
        .isEqualTo(
            AudioDecision.builder()
                .mode(AudioMode.TRANSCODE)
                .codec("aac")
                .channels(6)
                .channelLayout(Optional.of(ChannelLayout.FIVE_POINT_ONE))
                .bitrate(384_000L)
                .build());
    assertThat(playlist).contains("CHANNELS=\"6\"");
  }

  // ADR 0050: a 7.1 DTS source encodes E-AC-3 5.1 at 768 kb/s, which leaves less than the 360p
  // tier's 800 kb/s under each of these maximums. The session takes the first audio step that
  // leaves it, and the worker's job and the playlist carry that step.
  @ParameterizedTest(name = "{0}")
  @MethodSource("maximumsAndTheFirstStepThatLeavesThe360pTier")
  @DisplayName(
      "Should take the first audio step that leaves the 360p tier's video bitrate when the"
          + " session's audio leaves less under the maximum bitrate")
  void
      shouldTakeTheFirstAudioStepThatLeavesThe360pTiersVideoBitrateWhenTheSessionsAudioLeavesLessUnderTheMaximumBitrate(
          StepUnderMaximum expected) {
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .maxBitrate(expected.maxBitrate())
            .build();

    var session = createDtsSession(8, options);
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    var audio = session.getTranscodeDecision().audioDecision();
    assertThat(audio)
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(
            AudioMode.TRANSCODE, expected.codec(), expected.channels(), expected.bitrate());
    assertThat(session.getVariants()).extracting(QualityVariant::label).containsExactly("360p");
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> assertThat(request.transcodeDecision().audioDecision()).isEqualTo(audio));
    assertThat(playlist)
        .contains("CHANNELS=\"" + expected.channels() + "\"")
        .contains("," + expected.codecString() + "\"");
    assertThat(averageBandwidths(playlist))
        .isNotEmpty()
        .allSatisfy(bandwidth -> assertThat(bandwidth).isLessThanOrEqualTo(expected.maxBitrate()));
  }

  // Each step fits from the 360p tier's 800 kb/s plus its bitrate: E-AC-3 5.1 at 448 kb/s from
  // 1,248 kb/s, at 384 from 1,184, and stereo AAC at 128 from 928 and at 96 from 896.
  static Stream<StepUnderMaximum> maximumsAndTheFirstStepThatLeavesThe360pTier() {
    return Stream.of(
        eac351Step().maxBitrate(1_400_000).bitrate(448_000L).build(),
        eac351Step().maxBitrate(1_248_000).bitrate(448_000L).build(),
        eac351Step().maxBitrate(1_247_999).bitrate(384_000L).build(),
        stereoAacStep().maxBitrate(1_000_000).bitrate(128_000L).build(),
        stereoAacStep().maxBitrate(928_000).bitrate(128_000L).build(),
        stereoAacStep().maxBitrate(927_999).bitrate(96_000L).build(),
        stereoAacStep().maxBitrate(896_000).bitrate(96_000L).build());
  }

  private static StepUnderMaximum.StepUnderMaximumBuilder eac351Step() {
    return StepUnderMaximum.builder().codec("eac3").channels(6).codecString("ec-3");
  }

  private static StepUnderMaximum.StepUnderMaximumBuilder stereoAacStep() {
    return StepUnderMaximum.builder().codec("aac").channels(2).codecString("mp4a.40.2");
  }

  @Builder
  record StepUnderMaximum(
      int maxBitrate, String codec, int channels, long bitrate, String codecString) {}

  // Under 896 kb/s, 800 kb/s of video plus 96 kb/s of AAC, no audio step leaves the 360p tier's
  // video bitrate. The audio takes AAC stereo at 96 kb/s and the video the rest in the 360p box,
  // but never less than 145 kb/s, so only a maximum below 241 kb/s is exceeded.
  @ParameterizedTest(name = "{0}")
  @MethodSource("maximumsBelowEveryStepBesideThe360pTier")
  @DisplayName(
      "Should give the video the rest in the 360p box beside the last audio step when no audio step"
          + " leaves the 360p tier's video bitrate")
  void
      shouldGiveTheVideoTheRestInThe360pBoxBesideTheLastAudioStepWhenNoAudioStepLeavesThe360pTiersVideoBitrate(
          VideoBesideTheLastStep expected) {
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .maxBitrate(expected.maxBitrate())
            .build();

    var session = createDtsSession(8, options);
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::codec, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly("aac", 2, 96_000L);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(new PictureSize(request.width(), request.height()))
                  .isEqualTo(new PictureSize(640, 360));
              assertThat(request.bitrate()).isEqualTo(expected.videoBitrate());
            });
    assertThat(averageBandwidths(playlist)).containsExactly(expected.videoBitrate() + 96_000L);
  }

  static Stream<VideoBesideTheLastStep> maximumsBelowEveryStepBesideThe360pTier() {
    return Stream.of(
        VideoBesideTheLastStep.builder().maxBitrate(600_000).videoBitrate(504_000L).build(),
        VideoBesideTheLastStep.builder().maxBitrate(241_000).videoBitrate(145_000L).build(),
        VideoBesideTheLastStep.builder().maxBitrate(150_000).videoBitrate(145_000L).build());
  }

  @Builder
  record VideoBesideTheLastStep(int maxBitrate, long videoBitrate) {}

  // ADR 0050: a source whose own bitrate is below the 145 kb/s floor still caps the video. Under
  // 200 kb/s the audio takes 96 kb/s and the floor would ask for 145 kb/s, but the 100 kb/s source
  // holds less.
  @Test
  @DisplayName(
      "Should encode the single variant at the source's bitrate when the source's bitrate is below"
          + " the 145 kb/s floor")
  void
      shouldEncodeTheSingleVariantAtTheSourcesBitrateWhenTheSourcesBitrateIsBelowThe145KbpsFloor() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").bitrate(100_000L).build());
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(200_000).build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(96_000L);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(new PictureSize(request.width(), request.height()))
                  .isEqualTo(new PictureSize(640, 360));
              assertThat(request.bitrate()).isEqualTo(100_000L);
            });
    assertThat(averageBandwidths(playlist)).containsExactly(196_000L);
  }

  // E-AC-3 5.1 at 768 kb/s leaves room for the 360p tier at each maximum. At 2,000 kb/s the 480p
  // tier fits beside 448 kb/s, and at 3,500 kb/s the 720p tier does; 640 kb/s gains neither.
  @ParameterizedTest(name = "{0}")
  @MethodSource("maximumsAtWhichALowerStepGainsATier")
  @DisplayName(
      "Should take the first lower multichannel step that lets a higher top tier fit when the"
          + " viewer picks Auto")
  void shouldTakeTheFirstLowerMultichannelStepThatLetsAHigherTopTierFitWhenTheViewerPicksAuto(
      TopTierBesideAudio expected) {
    assertTopTierBesideTheAudio(expected);
  }

  static Stream<TopTierBesideAudio> maximumsAtWhichALowerStepGainsATier() {
    return Stream.of(
        TopTierBesideAudio.builder()
            .maxBitrate(2_000_000)
            .audioBitrate(448_000L)
            .labels(List.of("480p", "360p"))
            .build(),
        TopTierBesideAudio.builder()
            .maxBitrate(3_500_000)
            .audioBitrate(448_000L)
            .labels(List.of("720p", "480p", "360p"))
            .build());
  }

  // At 4,000 kb/s 768 kb/s already leaves the 720p tier, and the 1080p tier's 5,000 kb/s fits
  // beside no step. At 1,800 kb/s the 480p tier would fit only beside a stereo step.
  @ParameterizedTest(name = "{0}")
  @MethodSource("maximumsAtWhichNoMultichannelStepGainsATier")
  @DisplayName(
      "Should keep the session's audio when no lower multichannel step lets a higher top tier fit"
          + " in Auto")
  void shouldKeepTheSessionsAudioWhenNoLowerMultichannelStepLetsAHigherTopTierFitInAuto(
      TopTierBesideAudio expected) {
    assertTopTierBesideTheAudio(expected);
  }

  static Stream<TopTierBesideAudio> maximumsAtWhichNoMultichannelStepGainsATier() {
    var sessionAudio = TopTierBesideAudio.builder().audioBitrate(768_000L);
    return Stream.of(
        sessionAudio.maxBitrate(4_000_000).labels(List.of("720p", "480p", "360p")).build(),
        sessionAudio.maxBitrate(1_800_000).labels(List.of("360p")).build());
  }

  // A 7.1 DTS source in Auto: its E-AC-3 5.1 audio, its tiers and every AVERAGE-BANDWIDTH.
  private void assertTopTierBesideTheAudio(TopTierBesideAudio expected) {
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .maxBitrate(expected.maxBitrate())
            .build();

    var session = createDtsSession(8, options);
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::codec, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly("eac3", 6, expected.audioBitrate());
    assertThat(session.getVariants())
        .extracting(QualityVariant::label)
        .containsExactlyInAnyOrderElementsOf(expected.labels());
    assertThat(averageBandwidths(playlist))
        .isNotEmpty()
        .allSatisfy(bandwidth -> assertThat(bandwidth).isLessThanOrEqualTo(expected.maxBitrate()));
  }

  @Builder
  record TopTierBesideAudio(int maxBitrate, long audioBitrate, List<String> labels) {}

  // A fixed 720p quality's variant reaches its full 3,000 kb/s beside E-AC-3 5.1 at 448 kb/s under
  // 3,500 kb/s, but not beside 768 or 640 kb/s.
  @Test
  @DisplayName(
      "Should take a lower multichannel step when it gives a fixed quality's variant its full"
          + " bitrate")
  void shouldTakeALowerMultichannelStepWhenItGivesAFixedQualitysVariantItsFullBitrate() {
    var options =
        StreamingOptions.builder().quality(VideoQuality.HIGH_720P).maxBitrate(3_500_000).build();

    var session = createDtsSession(8, options);

    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(448_000L);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(new PictureSize(request.width(), request.height()))
                  .isEqualTo(new PictureSize(1280, 720));
              assertThat(request.bitrate()).isEqualTo(3_000_000L);
            });
  }

  // Under 3,000 kb/s a fixed 1080p quality's variant gets at most 2,616 kb/s beside 384 kb/s, short
  // of its full 5,000 kb/s, so the session keeps 768 kb/s and the variant gets 2,232 kb/s.
  @Test
  @DisplayName(
      "Should keep the session's audio when no lower multichannel step gives a fixed quality's"
          + " variant its full bitrate")
  void
      shouldKeepTheSessionsAudioWhenNoLowerMultichannelStepGivesAFixedQualitysVariantItsFullBitrate() {
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .maxBitrate(3_000_000)
            .build();

    var session = createDtsSession(8, options);

    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(768_000L);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(new PictureSize(request.width(), request.height()))
                  .isEqualTo(new PictureSize(1920, 1080));
              assertThat(request.bitrate()).isEqualTo(2_232_000L);
            });
  }

  // A fixed 720p quality at 1,200 kb/s leaves 432 kb/s beside 768 kb/s E-AC-3 5.1. At 384 kb/s the
  // single variant gets 816 kb/s and keeps the 720p box.
  @Test
  @DisplayName(
      "Should lower the audio and keep the fixed quality's box when the session's audio leaves less"
          + " than the 360p tier's video bitrate")
  void
      shouldLowerTheAudioAndKeepTheFixedQualitysBoxWhenTheSessionsAudioLeavesLessThanThe360pTiersVideoBitrate() {
    var options =
        StreamingOptions.builder().quality(VideoQuality.HIGH_720P).maxBitrate(1_200_000).build();

    var session = createDtsSession(8, options);

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::codec, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly("eac3", 6, 384_000L);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(new PictureSize(request.width(), request.height()))
                  .isEqualTo(new PictureSize(1280, 720));
              assertThat(request.bitrate()).isEqualTo(816_000L);
            });
  }

  // ADR 0052: an encode in Original plays Auto's ladder, so at 1,400 kb/s it takes the audio step
  // that Auto's ladder takes beside an 8-channel DTS source (ADR 0050).
  @Test
  @DisplayName(
      "Should take Auto's audio step when an encode in Original leaves less than the 360p tier's"
          + " video bitrate beside the session's audio")
  void
      shouldTakeAutosAudioStepWhenAnEncodeInOriginalLeavesLessThanThe360pTiersVideoBitrateBesideTheSessionsAudio() {
    var auto =
        createDtsSession(
            8, StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(1_400_000).build());
    var original =
        createDtsSession(
            8,
            StreamingOptions.builder()
                .quality(VideoQuality.ORIGINAL)
                .maxBitrate(1_400_000)
                .build());

    assertThat(original.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(original.getTranscodeDecision().audioDecision())
        .isEqualTo(auto.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::bitrate)
        .isNotEqualTo(768_000L);
    assertThat(original.getVariants()).isEqualTo(auto.getVariants());
  }

  // A copied 640 kb/s AC-3 5.1 track leaves 660 kb/s under 1,300 kb/s. The player declares AC-3 but
  // no E-AC-3, so the copy becomes a 448 kb/s AC-3 5.1 encode.
  @Test
  @DisplayName(
      "Should encode a copied audio track at a lower step when its bitrate leaves less than the"
          + " 360p tier's video bitrate")
  void
      shouldEncodeACopiedAudioTrackAtALowerStepWhenItsBitrateLeavesLessThanThe360pTiersVideoBitrate() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build()))
            .build();

    var session =
        createSessionCopyingAudio(
            CopiedAudioSession.builder()
                .track(surroundTrack("ac3").bitrate(OptionalLong.of(640_000L)))
                .declaration(declaration)
                .maxBitrate(1_300_000)
                .build());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.TRANSCODE, "ac3", 6, 448_000L);
  }

  // The player declares E-AC-3 5.1, so the server would encode the track as E-AC-3. A copied AAC
  // 5.1 track at 384 kb/s leaves 766 kb/s under 1,150 kb/s, and its own codec's 320 kb/s step
  // leaves 830. A copied AC-3 5.1 track at 640 kb/s leaves 660 kb/s under 1,300 kb/s, and its own
  // codec's 448 kb/s step leaves 852.
  @ParameterizedTest(name = "{0}")
  @MethodSource("surroundCopiesInACodecTheServerEncodes")
  @DisplayName(
      "Should keep surround in the copied codec at a lower step when the server encodes that codec"
          + " and the copy leaves less than the 360p tier's video bitrate")
  void
      shouldKeepSurroundInTheCopiedCodecAtALowerStepWhenTheServerEncodesThatCodecAndTheCopyLeavesLessThanThe360pTiersVideoBitrate(
          CopiedSurroundStep expected) {
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                Stream.of(AudioCodec.AAC, expected.codec(), AudioCodec.EAC3)
                    .distinct()
                    .map(declared -> audioEntryBuilder(declared).maxChannels(6).build())
                    .toList())
            .build();

    var session =
        createSessionCopyingAudio(
            CopiedAudioSession.builder()
                .track(
                    surroundTrack(expected.codec().probedName())
                        .bitrate(OptionalLong.of(expected.copiedBitrate())))
                .declaration(declaration)
                .maxBitrate(expected.maxBitrate())
                .build());

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(
            AudioMode.TRANSCODE, expected.codec().probedName(), 6, expected.stepBitrate());
    assertThat(session.getVariants()).extracting(QualityVariant::label).containsExactly("360p");
  }

  static Stream<CopiedSurroundStep> surroundCopiesInACodecTheServerEncodes() {
    return Stream.of(
        CopiedSurroundStep.builder()
            .codec(AudioCodec.AAC)
            .copiedBitrate(384_000L)
            .maxBitrate(1_150_000)
            .stepBitrate(320_000L)
            .build(),
        CopiedSurroundStep.builder()
            .codec(AudioCodec.AC3)
            .copiedBitrate(640_000L)
            .maxBitrate(1_300_000)
            .stepBitrate(448_000L)
            .build());
  }

  @Builder
  record CopiedSurroundStep(
      AudioCodec codec, long copiedBitrate, int maxBitrate, long stepBitrate) {}

  // A copied AAC 7.1 track at 512 kb/s leaves 638 kb/s under 1,150 kb/s and its 448 kb/s step
  // leaves 702. Beside E-AC-3 5.1, the 384 kb/s E-AC-3 step leaves 766. AAC 5.1 at 320 kb/s leaves
  // 830 and comes before stereo; under 1,184 kb/s the E-AC-3 step already leaves 800.
  @ParameterizedTest(name = "{0}")
  @MethodSource("declarationsAndMaximumsForACopiedAac71Track")
  @DisplayName(
      "Should step a copied AAC 7.1 track through AAC 5.1 after E-AC-3 5.1 and before stereo when"
          + " the copy leaves less than the 360p tier's video bitrate")
  void
      shouldStepACopiedAac71TrackThroughAac51AfterEac351AndBeforeStereoWhenTheCopyLeavesLessThanThe360pTiersVideoBitrate(
          Aac71Step expected) {
    var entries = new ArrayList<>(List.of(aacEntryBuilder().maxChannels(8).build()));
    if (expected.declaresEac3()) {
      entries.add(audioEntryBuilder(AudioCodec.EAC3).maxChannels(6).build());
    }

    var session =
        createSessionCopyingAudio(
            CopiedAudioSession.builder()
                .track(
                    ProbeFixture.audioStreamBuilder("aac")
                        .index(1)
                        .channels(OptionalInt.of(8))
                        .bitrate(OptionalLong.of(512_000L)))
                .declaration(defaultDeclarationBuilder().audio(entries).build())
                .maxBitrate(expected.maxBitrate())
                .build());

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.TRANSCODE, expected.codec(), 6, expected.bitrate());
    assertThat(session.getVariants()).extracting(QualityVariant::label).containsExactly("360p");
  }

  static Stream<Aac71Step> declarationsAndMaximumsForACopiedAac71Track() {
    var aac51 = Aac71Step.builder().codec("aac").bitrate(320_000L).maxBitrate(1_150_000);
    return Stream.of(
        aac51.declaresEac3(false).build(),
        aac51.declaresEac3(true).build(),
        Aac71Step.builder()
            .declaresEac3(true)
            .maxBitrate(1_184_000)
            .codec("eac3")
            .bitrate(384_000L)
            .build());
  }

  @Builder
  record Aac71Step(boolean declaresEac3, int maxBitrate, String codec, long bitrate) {}

  // A copied AAC 4.0 track at 384 kb/s leaves 716 kb/s under 1,100 kb/s, and its own 256 kb/s step
  // leaves 844. A copied AAC 3.0 track at 320 kb/s leaves 730 kb/s under 1,050 kb/s, and its own
  // 192 kb/s step leaves 858. AAC carries 3.0 and 4.0 exactly, so each step keeps the copy's layout
  // (ADR 0050) and comes before stereo. AAC carries quad only as channel configuration 0, so a
  // copied AAC quad track steps to stereo at 128 kb/s, which leaves 972.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiedAacTracksOfThreeAndFourChannels")
  @DisplayName(
      "Should keep a copied 3- or 4-channel AAC track's layout at its own lower step when AAC"
          + " carries the layout and the copy leaves less than the 360p tier's video bitrate")
  void
      shouldKeepACopied3Or4ChannelAacTracksLayoutAtItsOwnLowerStepWhenAacCarriesTheLayoutAndTheCopyLeavesLessThanThe360pTiersVideoBitrate(
          CopiedAacStep expected) {
    var session =
        createSessionCopyingAudio(
            CopiedAudioSession.builder()
                .track(
                    ProbeFixture.audioStreamBuilder("aac")
                        .index(1)
                        .channels(OptionalInt.of(expected.stepLayout().getChannels()))
                        .bitrate(OptionalLong.of(expected.copiedBitrate()))
                        .audioProperties(
                            Optional.of(
                                AudioStreamProperties.builder()
                                    .channelLayout(Optional.of(expected.trackLayout()))
                                    .build())))
                .declaration(
                    defaultDeclarationBuilder()
                        .audio(List.of(aacEntryBuilder().maxChannels(6).build()))
                        .build())
                .maxBitrate(expected.maxBitrate())
                .build());

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate,
            AudioDecision::channelLayout)
        .containsExactly(
            AudioMode.TRANSCODE,
            "aac",
            expected.stepLayout().getChannels(),
            expected.stepBitrate(),
            Optional.of(expected.stepLayout()));
    assertThat(session.getVariants()).extracting(QualityVariant::label).containsExactly("360p");
  }

  static Stream<CopiedAacStep> copiedAacTracksOfThreeAndFourChannels() {
    return Stream.of(
        CopiedAacStep.builder()
            .trackLayout("4.0")
            .copiedBitrate(384_000L)
            .maxBitrate(1_100_000)
            .stepLayout(ChannelLayout.FOUR_POINT_ZERO)
            .stepBitrate(256_000L)
            .build(),
        CopiedAacStep.builder()
            .trackLayout("3.0")
            .copiedBitrate(320_000L)
            .maxBitrate(1_050_000)
            .stepLayout(ChannelLayout.THREE_POINT_ZERO)
            .stepBitrate(192_000L)
            .build(),
        CopiedAacStep.builder()
            .trackLayout("quad")
            .copiedBitrate(384_000L)
            .maxBitrate(1_100_000)
            .stepLayout(ChannelLayout.STEREO)
            .stepBitrate(128_000L)
            .build());
  }

  @Builder
  record CopiedAacStep(
      String trackLayout,
      long copiedBitrate,
      int maxBitrate,
      ChannelLayout stepLayout,
      long stepBitrate) {}

  // The server does not encode FLAC, so a copied FLAC 5.1 track steps through the server's AC-3 5.1
  // encode only: 448 and 384 kb/s. Under 1,440 kb/s, 448 kb/s leaves 992 for the video. The AC-3
  // encode's own 640 kb/s is not a step.
  @Test
  @DisplayName(
      "Should step a copied track through the server's encode only when the server does not encode"
          + " the copied codec")
  void shouldStepACopiedTrackThroughTheServersEncodeOnlyWhenTheServerDoesNotEncodeTheCopiedCodec() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.FLAC).maxChannels(6).build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build()))
            .build();

    var session =
        createSessionCopyingAudio(
            CopiedAudioSession.builder()
                .track(surroundTrack("flac").bitrate(OptionalLong.of(2_500_000L)))
                .declaration(declaration)
                .maxBitrate(1_440_000)
                .build());

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.TRANSCODE, "ac3", 6, 448_000L);
  }

  // A copied 128 kb/s mono AC-3 track leaves 772 kb/s under 900 kb/s. A mono track's only lower
  // step is AAC mono at 96 kb/s, which leaves 804.
  @Test
  @DisplayName(
      "Should step a copied mono track to AAC mono at 96 kb/s when the copy leaves less than the"
          + " 360p tier's video bitrate")
  void
      shouldStepACopiedMonoTrackToAacMonoAt96KbpsWhenTheCopyLeavesLessThanThe360pTiersVideoBitrate() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build()))
            .build();

    var session =
        createSessionCopyingAudio(
            CopiedAudioSession.builder()
                .track(
                    ProbeFixture.audioStreamBuilder("ac3")
                        .index(1)
                        .channels(OptionalInt.of(1))
                        .bitrate(OptionalLong.of(128_000L)))
                .declaration(declaration)
                .maxBitrate(900_000)
                .build());

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.TRANSCODE, "aac", 1, 96_000L);
    assertThat(session.getVariants()).extracting(QualityVariant::label).containsExactly("360p");
  }

  // A 5.1 track at an index after the video stream, whose codec and bitrate each test sets.
  private static StreamInfo.StreamInfoBuilder surroundTrack(String codec) {
    return ProbeFixture.audioStreamBuilder(codec).index(1).channels(OptionalInt.of(6));
  }

  // An HEVC source, which the default declaration cannot copy, beside one audio track, in Auto.
  private StreamSession createSessionCopyingAudio(CopiedAudioSession copied) {
    var probe = defaultProbeBuilder().videoCodec("hevc").build();
    var streams = List.of(ProbeFixture.videoStreamBuilder(probe).build(), copied.track().build());
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbe(
            defaultProbeBuilder().videoCodec("hevc").streams(streams).build()));
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .maxBitrate(copied.maxBitrate())
            .build();
    return accepted(
        service.createSession(
            createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                .options(options)
                .capabilityDeclaration(copied.declaration())
                .build()));
  }

  @Builder
  record CopiedAudioSession(
      StreamInfo.StreamInfoBuilder track,
      ClientCapabilityDeclaration declaration,
      int maxBitrate) {}

  // Original copies the H.264 video whatever the viewer's limits, and the server keeps a copied
  // 640 kb/s AC-3 5.1 track beside that copy.
  @Test
  @DisplayName(
      "Should keep a copied audio track when Original copies the video under a low maximum bitrate")
  void shouldKeepACopiedAudioTrackWhenOriginalCopiesTheVideoUnderALowMaximumBitrate() {
    var probe = defaultProbeBuilder().build();
    var streams =
        List.of(
            ProbeFixture.videoStreamBuilder(probe).build(),
            ProbeFixture.audioStreamBuilder("ac3")
                .index(1)
                .channels(OptionalInt.of(6))
                .bitrate(OptionalLong.of(640_000L))
                .build());
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbe(defaultProbeBuilder().streams(streams).build()));
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build()))
            .build();
    var options =
        StreamingOptions.builder().quality(VideoQuality.ORIGINAL).maxBitrate(1_000_000).build();

    var session =
        accepted(
            service.createSession(
                createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                    .options(options)
                    .capabilityDeclaration(declaration)
                    .build()));

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.COPY, "ac3", 6, 640_000L);
  }

  // Original copies the H.264 video whatever the viewer's limits, and the server keeps the audio
  // beside that copy.
  @Test
  @DisplayName(
      "Should keep the session's audio when Original copies the video under a low maximum bitrate")
  void shouldKeepTheSessionsAudioWhenOriginalCopiesTheVideoUnderALowMaximumBitrate() {
    var options =
        StreamingOptions.builder().quality(VideoQuality.ORIGINAL).maxBitrate(1_000_000).build();

    var session = createDtsSession(defaultProbeBuilder(), 8, options);

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::codec, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly("eac3", 6, 768_000L);
  }

  // ADR 0050: a copy costs its own video bitrate plus the stream session's audio bitrate, here a
  // 768 kb/s E-AC-3 5.1 encode of a 450 kb/s 7.1 Opus track. The source's 5 Mb/s holds 4.5 Mb/s of
  // video. Original, Auto and a fixed quality whose box holds the source each copy the video.
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"ORIGINAL", "AUTO", "FULL_HD_1080P"})
  @DisplayName(
      "Should advertise the copied video's bitrate plus the encoded audio's bitrate when the"
          + " session copies the video and encodes the audio")
  void
      shouldAdvertiseTheCopiedVideosBitratePlusTheEncodedAudiosBitrateWhenTheSessionCopiesTheVideoAndEncodesTheAudio(
          VideoQuality quality) {
    var session = createCopySessionWithSurroundOpus(quality, OptionalLong.of(4_500_000L));
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(768_000L);
    assertThat(playlist).contains("BANDWIDTH=6321600,AVERAGE-BANDWIDTH=5268000,");
  }

  // A known container bitrate holds the copied video and every other track, so counting all of the
  // source's 5 Mb/s as video never falls below the video's own bitrate.
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"ORIGINAL", "AUTO", "FULL_HD_1080P"})
  @DisplayName(
      "Should count the container bitrate as the copied video's bitrate when the session copies"
          + " the video and the probe does not know the video stream's bitrate")
  void
      shouldCountTheContainerBitrateAsTheCopiedVideosBitrateWhenTheSessionCopiesTheVideoAndTheProbeDoesNotKnowTheVideoStreamsBitrate(
          VideoQuality quality) {
    var session = createCopySessionWithSurroundOpus(quality, OptionalLong.empty());
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(768_000L);
    assertThat(playlist).contains("BANDWIDTH=6921600,AVERAGE-BANDWIDTH=5768000,");
  }

  // A copied track whose bitrate the probe does not know takes a nominal 128 kb/s, far below a
  // stereo FLAC track's rate. The 2.2 Mb/s source holds 1 Mb/s of video and that track.
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"ORIGINAL", "AUTO", "FULL_HD_1080P"})
  @DisplayName(
      "Should advertise at least the container bitrate when the session copies the video and an"
          + " audio track whose bitrate the probe does not know")
  void
      shouldAdvertiseAtLeastTheContainerBitrateWhenTheSessionCopiesTheVideoAndAnAudioTrackWhoseBitrateTheProbeDoesNotKnow(
          VideoQuality quality) {
    var probe = defaultProbeBuilder().bitrate(2_200_000L);
    var streams =
        List.of(
            ProbeFixture.videoStreamBuilder(probe.build())
                .bitrate(OptionalLong.of(1_000_000L))
                .build(),
            ProbeFixture.audioStreamBuilder("flac").index(1).channels(OptionalInt.of(2)).build());
    probeResults.setDefaultOutcome(ProbeFixture.completeProbe(probe.streams(streams).build()));
    var declaration =
        defaultDeclarationBuilder()
            .audio(List.of(aacEntryBuilder().build(), audioEntryBuilder(AudioCodec.FLAC).build()))
            .build();

    var session =
        accepted(
            service.createSession(
                createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                    .options(StreamingOptions.builder().quality(quality).build())
                    .capabilityDeclaration(declaration)
                    .build()));
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(128_000L);
    assertThat(playlist).contains("BANDWIDTH=2640000,AVERAGE-BANDWIDTH=2200000,");
  }

  // ADR 0050: a copy of 4.5 Mb/s video beside 768 kb/s E-AC-3 5.1 costs 5,268 kb/s, so it fits a
  // maximum of exactly that much beside the session's audio.
  @Test
  @DisplayName(
      "Should keep the session's audio beside a video copy when the copy's cost equals the maximum"
          + " bitrate")
  void shouldKeepTheSessionsAudioBesideAVideoCopyWhenTheCopysCostEqualsTheMaximumBitrate() {
    var session =
        createCopySessionWithSurroundOpus(
            surroundOpusSource().options(autoOptions().maxBitrate(5_268_000).build()).build());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::codec, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly("eac3", 6, 768_000L);
    assertDeliversTheSessionsAudioWithinTheMaximumBitrate(session, 5_268_000);
  }

  // ADR 0054: the server measured the copied video at 2,700,000,000 bytes in 172,800 packets, 24
  // fps over the 2-hour source. Its 1,200 segments add 2,880,000 bytes of framing, and the
  // session's
  // E-AC-3 at its highest rate of 48 kHz writes 225,001 frames, whose framing adds 3,676,816 bytes.
  // The copy averages 3,007,286 b/s beside the E-AC-3's 768 kb/s, 3,775,286 b/s in all, far below
  // the 4.5 Mb/s probed video that no audio step fits beside.
  @Test
  @DisplayName(
      "Should copy the video beside the session's audio when the copy's measured average equals the"
          + " maximum bitrate")
  void
      shouldCopyTheVideoBesideTheSessionsAudioWhenTheCopysMeasuredAverageEqualsTheMaximumBitrate() {
    var measurement =
        new StreamMeasurements(
            StreamMeasurementSource.SAMPLE_TABLE,
            List.of(
                StreamMeasurementFixture.measuredStreamBuilder(0, 2_700_000_000L)
                    .packetCount(172_800)
                    .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(172_800))
                    .measuredDuration(7_200_000)
                    .build()));

    var session =
        createCopySessionWithSurroundOpus(
            surroundOpusSource()
                .options(autoOptions().maxBitrate(3_775_286).build())
                .streamMeasurements(Optional.of(measurement))
                .build());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::codec, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly("eac3", 6, 768_000L);
    assertThat(
            new HlsPlaylistService(streamingProperties())
                .generateMultivariantPlaylist(session, "t"))
        .contains("AVERAGE-BANDWIDTH=3775286,");
  }

  // ADR 0050: under a maximum below the copy's 5,268 kb/s the audio steps down first, so the video
  // copy keeps the source's full quality. Each maximum is at or above the copy's cost beside its
  // step and below its cost beside the step above.
  @ParameterizedTest(name = "{0}")
  @MethodSource("maximumsAndTheFirstAudioStepBesideAVideoCopy")
  @DisplayName(
      "Should take the first audio step at which a video copy fits when the session's audio leaves"
          + " the copy over the maximum bitrate")
  void
      shouldTakeTheFirstAudioStepAtWhichAVideoCopyFitsWhenTheSessionsAudioLeavesTheCopyOverTheMaximumBitrate(
          StepBesideCopy expected) {
    var options =
        StreamingOptions.builder()
            .quality(expected.quality())
            .maxBitrate(expected.maxBitrate())
            .build();

    var session = createCopySessionWithSurroundOpus(surroundOpusSource().options(options).build());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::codec, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly(expected.codec(), expected.channels(), expected.bitrate());
    assertDeliversTheSessionsAudioWithinTheMaximumBitrate(session, expected.maxBitrate());
  }

  static Stream<StepBesideCopy> maximumsAndTheFirstAudioStepBesideAVideoCopy() {
    var eac351 = StepBesideCopy.builder().quality(VideoQuality.AUTO).codec("eac3").channels(6);
    var aacStereo = StepBesideCopy.builder().quality(VideoQuality.AUTO).codec("aac").channels(2);
    return Stream.of(
        eac351.maxBitrate(5_267_999).bitrate(640_000L).build(),
        eac351.maxBitrate(4_950_000).bitrate(448_000L).build(),
        eac351.maxBitrate(4_884_000).bitrate(384_000L).build(),
        aacStereo.maxBitrate(4_628_000).bitrate(128_000L).build(),
        aacStereo.maxBitrate(4_596_000).bitrate(96_000L).build(),
        StepBesideCopy.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .maxBitrate(4_950_000)
            .codec("eac3")
            .channels(6)
            .bitrate(448_000L)
            .build());
  }

  @Builder
  record StepBesideCopy(
      VideoQuality quality, int maxBitrate, String codec, int channels, long bitrate) {}

  // At 4,595,999 kb/s even AAC stereo at 96 kb/s leaves the 4.5 Mb/s copy over the maximum, so the
  // video is encoded. The encode then takes its audio step by its own rule (ADR 0050): E-AC-3 5.1
  // at 768 kb/s leaves room for the 360p tier there, and under 1,400 kb/s the 360p tier's 800 kb/s
  // fits only beside 448 kb/s.
  @ParameterizedTest(name = "{0}")
  @MethodSource("maximumsBelowTheCopyBesideEveryAudioStep")
  @DisplayName(
      "Should encode the video beside the encode's own audio step when no audio step fits beside a"
          + " video copy")
  void shouldEncodeTheVideoBesideTheEncodesOwnAudioStepWhenNoAudioStepFitsBesideAVideoCopy(
      EncodeBesideStep expected) {
    var session =
        createCopySessionWithSurroundOpus(
            surroundOpusSource()
                .options(autoOptions().maxBitrate(expected.maxBitrate()).build())
                .build());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::codec, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly("eac3", 6, expected.bitrate());
    assertDeliversTheSessionsAudioWithinTheMaximumBitrate(session, expected.maxBitrate());
  }

  static Stream<EncodeBesideStep> maximumsBelowTheCopyBesideEveryAudioStep() {
    return Stream.of(
        EncodeBesideStep.builder().maxBitrate(4_595_999).bitrate(768_000L).build(),
        EncodeBesideStep.builder().maxBitrate(1_400_000).bitrate(448_000L).build());
  }

  @Builder
  record EncodeBesideStep(int maxBitrate, long bitrate) {}

  // Every job that the worker starts carries the session's audio, and every variant that the
  // multivariant playlist advertises costs at most the maximum bitrate.
  private void assertDeliversTheSessionsAudioWithinTheMaximumBitrate(
      StreamSession session, int maxBitrate) {
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .allSatisfy(
            request ->
                assertThat(request.transcodeDecision().audioDecision())
                    .isEqualTo(session.getTranscodeDecision().audioDecision()));
    assertThat(averageBandwidths(playlist))
        .isNotEmpty()
        .allSatisfy(bandwidth -> assertThat(bandwidth).isLessThanOrEqualTo(maxBitrate));
  }

  // The probe knows the 4.5 Mb/s copied video's bitrate but not the container's, so the copy still
  // costs the video plus E-AC-3 5.1, and under 5 Mb/s it takes 448 kb/s.
  @Test
  @DisplayName(
      "Should lower the audio beside a copy whose video bitrate the probe knows when the probe does"
          + " not know the container bitrate")
  void
      shouldLowerTheAudioBesideACopyWhoseVideoBitrateTheProbeKnowsWhenTheProbeDoesNotKnowTheContainerBitrate() {
    var session =
        createCopySessionWithSurroundOpus(
            surroundOpusSource()
                .options(autoOptions().maxBitrate(5_000_000).build())
                .containerBitrate(0L)
                .build());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(448_000L);
  }

  // ADR 0050: a copy whose video bitrate and container bitrate are both unknown passes the bitrate
  // check, so the copied stereo AAC track stays a copy under a maximum below its own 128 kb/s.
  @Test
  @DisplayName(
      "Should keep the copied audio track beside a copy when the probe knows neither the copied"
          + " video's bitrate nor the container bitrate")
  void
      shouldKeepTheCopiedAudioTrackBesideACopyWhenTheProbeKnowsNeitherTheCopiedVideosBitrateNorTheContainerBitrate() {
    probeResults.setDefaultProbe(defaultProbeBuilder().bitrate(0L).build());

    var session =
        createSession(
            seedMediaFile().getId(), UUID.randomUUID(), autoOptions().maxBitrate(100_000).build());

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::mode, AudioDecision::bitrate)
        .containsExactly(AudioMode.COPY, 128_000L);
  }

  // ADR 0050: the 1920×1080 source has no usable frame rate, so it copies over the fixed 720p's
  // box. Beside that copy the server still takes the first audio step that fits: the copy of
  // 4.5 Mb/s video fits 5 Mb/s beside E-AC-3 5.1 at 448 kb/s.
  @Test
  @DisplayName(
      "Should take the first audio step at which a copy fits when a source without a usable frame"
          + " rate copies over the fixed quality's picture box")
  void
      shouldTakeTheFirstAudioStepAtWhichACopyFitsWhenASourceWithoutAUsableFrameRateCopiesOverTheFixedQualitysPictureBox() {
    var options =
        StreamingOptions.builder().quality(VideoQuality.HIGH_720P).maxBitrate(5_000_000).build();

    var session =
        createCopySessionWithSurroundOpus(
            surroundOpusSource().options(options).framerate(OptionalDouble.empty()).build());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::codec, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly("eac3", 6, 448_000L);
  }

  // A Matroska source names no bitrate for its 5 Mb/s video stream. Encoded 768 kb/s E-AC-3 5.1
  // adds its bitrate to the container bitrate, which also holds the source's own audio tracks, so
  // under 5,500 kb/s the copy takes 448 kb/s.
  @Test
  @DisplayName(
      "Should lower encoded audio beside a copy whose video bitrate the probe does not know when the"
          + " container bitrate plus the audio exceeds the maximum bitrate")
  void
      shouldLowerEncodedAudioBesideACopyWhoseVideoBitrateTheProbeDoesNotKnowWhenTheContainerBitratePlusTheAudioExceedsTheMaximumBitrate() {
    var session =
        createCopySessionWithSurroundOpus(
            surroundOpusSource()
                .options(autoOptions().maxBitrate(5_500_000).build())
                .videoBitrate(OptionalLong.empty())
                .build());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(448_000L);
  }

  // The container bitrate of a Matroska source holds the copied stereo AAC track, so a copy costs
  // its 5 Mb/s alone and fits a 5 Mb/s maximum without changing the audio.
  @Test
  @DisplayName(
      "Should keep the copied audio track beside a copy whose video bitrate the probe does not know"
          + " when the container bitrate equals the maximum bitrate")
  void
      shouldKeepTheCopiedAudioTrackBesideACopyWhoseVideoBitrateTheProbeDoesNotKnowWhenTheContainerBitrateEqualsTheMaximumBitrate() {
    var session =
        createCopySessionWithCopiedStereoAac(
            autoOptions().maxBitrate(5_000_000).build(), OptionalLong.of(192_000L));

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().audioDecision().mode()).isEqualTo(AudioMode.COPY);
  }

  // No encoded audio step lowers a cost that the container bitrate alone sets, so one bit/s below
  // the 5 Mb/s container bitrate the video is encoded beside the copied track.
  @Test
  @DisplayName(
      "Should encode the video beside the copied audio track when the container bitrate of a copy"
          + " whose video bitrate the probe does not know exceeds the maximum bitrate")
  void
      shouldEncodeTheVideoBesideTheCopiedAudioTrackWhenTheContainerBitrateOfACopyWhoseVideoBitrateTheProbeDoesNotKnowExceedsTheMaximumBitrate() {
    var session =
        createCopySessionWithCopiedStereoAac(
            autoOptions().maxBitrate(4_999_999).build(), OptionalLong.of(192_000L));

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision().mode()).isEqualTo(AudioMode.COPY);
  }

  // A Matroska source names no bitrate for its video stream, so its 5 Mb/s container bitrate stands
  // in for the copied video. That rate already holds the copied stereo AAC track, so the copy costs
  // the container bitrate alone, whether the probe knows the track's 192 kb/s or not.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiedStereoAacTracksBesideACopyOfUnknownVideoBitrate")
  @DisplayName(
      "Should advertise the container bitrate alone when the session copies an audio track that the"
          + " container bitrate holds and the probe knows no bitrate for the copied video")
  void
      shouldAdvertiseTheContainerBitrateAloneWhenTheSessionCopiesAnAudioTrackThatTheContainerBitrateHoldsAndTheProbeKnowsNoBitrateForTheCopiedVideo(
          CopiedTrackBesideCopy copied) {
    var session =
        createCopySessionWithCopiedStereoAac(
            StreamingOptions.builder().quality(copied.quality()).build(), copied.audioBitrate());
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(playlist).contains("BANDWIDTH=6000000,AVERAGE-BANDWIDTH=5000000,");
  }

  static Stream<CopiedTrackBesideCopy> copiedStereoAacTracksBesideACopyOfUnknownVideoBitrate() {
    return Stream.of(VideoQuality.ORIGINAL, VideoQuality.AUTO)
        .flatMap(
            quality ->
                Stream.of(OptionalLong.of(192_000L), OptionalLong.empty())
                    .map(
                        audioBitrate ->
                            CopiedTrackBesideCopy.builder()
                                .quality(quality)
                                .audioBitrate(audioBitrate)
                                .build()));
  }

  @Builder
  record CopiedTrackBesideCopy(VideoQuality quality, OptionalLong audioBitrate) {}

  // A copy of 4.5 Mb/s video beside a copied 384 kb/s AAC 5.1 track costs 4,884 kb/s, so it fits a
  // maximum of exactly that much with the track still copied.
  @Test
  @DisplayName(
      "Should keep a copied surround track beside a video copy when the copy's cost equals the"
          + " maximum bitrate")
  void shouldKeepACopiedSurroundTrackBesideAVideoCopyWhenTheCopysCostEqualsTheMaximumBitrate() {
    var session = createCopySessionWithCopiedAac51(4_884_000);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.COPY, "aac", 6, 384_000L);
  }

  // The player declares E-AC-3 5.1, so the server would encode the track as E-AC-3, but the copied
  // codec's own 320 kb/s 5.1 step comes first and keeps surround beside the video copy. Below
  // 4,820 kb/s, the copy of 4.5 Mb/s video beside that step, the copy takes AAC stereo.
  @ParameterizedTest(name = "{0}")
  @MethodSource("maximumsBelowTheCopyBesideACopiedAac51Track")
  @DisplayName(
      "Should keep surround in the copied codec beside a video copy when the copied audio leaves"
          + " the copy over the maximum bitrate")
  void
      shouldKeepSurroundInTheCopiedCodecBesideAVideoCopyWhenTheCopiedAudioLeavesTheCopyOverTheMaximumBitrate(
          CopiedAac51Step expected) {
    var session = createCopySessionWithCopiedAac51(expected.maxBitrate());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.TRANSCODE, "aac", expected.channels(), expected.bitrate());
  }

  static Stream<CopiedAac51Step> maximumsBelowTheCopyBesideACopiedAac51Track() {
    return Stream.of(
        CopiedAac51Step.builder().maxBitrate(4_883_999).channels(6).bitrate(320_000L).build(),
        CopiedAac51Step.builder().maxBitrate(4_819_999).channels(2).bitrate(128_000L).build());
  }

  @Builder
  record CopiedAac51Step(int maxBitrate, int channels, long bitrate) {}

  // A 5 Mb/s source of 4.5 Mb/s video beside a copyable 384 kb/s AAC 5.1 track, for a player that
  // declares AAC 5.1 and E-AC-3 5.1.
  private StreamSession createCopySessionWithCopiedAac51(int maxBitrate) {
    var probe = defaultProbeBuilder().bitrate(5_000_000L);
    var streams =
        List.of(
            ProbeFixture.videoStreamBuilder(probe.build())
                .bitrate(OptionalLong.of(4_500_000L))
                .build(),
            ProbeFixture.audioStreamBuilder("aac")
                .index(1)
                .channels(OptionalInt.of(6))
                .bitrate(OptionalLong.of(384_000L))
                .build());
    probeResults.setDefaultOutcome(ProbeFixture.completeProbe(probe.streams(streams).build()));
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().maxChannels(6).build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(6).build()))
            .build();
    return accepted(
        service.createSession(
            createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                .options(autoOptions().maxBitrate(maxBitrate).build())
                .capabilityDeclaration(declaration)
                .build()));
  }

  // A 5 Mb/s source whose probe knows no bitrate for its video stream, beside a copyable stereo
  // AAC track.
  private StreamSession createCopySessionWithCopiedStereoAac(
      StreamingOptions options, OptionalLong audioBitrate) {
    var probe = defaultProbeBuilder().bitrate(5_000_000L);
    var streams =
        List.of(
            ProbeFixture.videoStreamBuilder(probe.build()).build(),
            ProbeFixture.audioStreamBuilder("aac")
                .index(1)
                .channels(OptionalInt.of(2))
                .bitrate(audioBitrate)
                .build());
    probeResults.setDefaultOutcome(ProbeFixture.completeProbe(probe.streams(streams).build()));
    return accepted(
        service.createSession(
            createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                .options(options)
                .build()));
  }

  private StreamSession createCopySessionWithSurroundOpus(
      VideoQuality quality, OptionalLong videoBitrate) {
    return createCopySessionWithSurroundOpus(
        surroundOpusSource()
            .options(StreamingOptions.builder().quality(quality).build())
            .videoBitrate(videoBitrate)
            .build());
  }

  // The declaration lists stereo AAC and 7.1 E-AC-3 but no Opus, so the session encodes the audio.
  private StreamSession createCopySessionWithSurroundOpus(SurroundOpusSource source) {
    var probe =
        defaultProbeBuilder().bitrate(source.containerBitrate()).framerate(source.framerate());
    var streams =
        List.of(
            ProbeFixture.videoStreamBuilder(probe.build()).bitrate(source.videoBitrate()).build(),
            ProbeFixture.audioStreamBuilder("opus")
                .index(1)
                .channels(OptionalInt.of(8))
                .bitrate(OptionalLong.of(450_000L))
                .build());
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbeBuilder(probe.streams(streams).build())
            .streamMeasurements(source.streamMeasurements())
            .build());
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(8).build()))
            .build();
    return accepted(
        service.createSession(
            createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                .options(source.options())
                .capabilityDeclaration(declaration)
                .build()));
  }

  // A 5 Mb/s source of 4.5 Mb/s video at 24 fps beside a 450 kb/s Opus 7.1 track, played in Auto.
  private static SurroundOpusSource.SurroundOpusSourceBuilder surroundOpusSource() {
    return SurroundOpusSource.builder()
        .options(autoOptions().build())
        .containerBitrate(5_000_000L)
        .videoBitrate(OptionalLong.of(4_500_000L))
        .framerate(OptionalDouble.of(24.0))
        .streamMeasurements(Optional.empty());
  }

  @Builder
  record SurroundOpusSource(
      StreamingOptions options,
      long containerBitrate,
      OptionalLong videoBitrate,
      OptionalDouble framerate,
      Optional<StreamMeasurements> streamMeasurements) {}

  private StreamSession createDtsSession(int sourceChannels, StreamingOptions options) {
    return createDtsSession(defaultProbeBuilder().videoCodec("hevc"), sourceChannels, options);
  }

  // The declaration lists stereo AAC and 7.1 E-AC-3 but no DTS, so the session encodes the audio.
  private StreamSession createDtsSession(
      MediaProbe.MediaProbeBuilder probe, int sourceChannels, StreamingOptions options) {
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbe(
            probe.audioCodec("dts").audioChannels(OptionalInt.of(sourceChannels)).build()));
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(8).build()))
            .build();
    return accepted(
        service.createSession(
            createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                .options(options)
                .capabilityDeclaration(declaration)
                .build()));
  }

  // Each encoded variant's BANDWIDTH, keyed by the label that names its media playlist.
  private static Map<String, Long> peakBandwidthsByVariant(String playlist) {
    return Pattern.compile("#EXT-X-STREAM-INF:BANDWIDTH=(\\d+),.*\n([^/\n]+)/stream\\.m3u8")
        .matcher(playlist)
        .results()
        .collect(
            Collectors.toMap(match -> match.group(2), match -> Long.parseLong(match.group(1))));
  }

  private static List<Long> averageBandwidths(String playlist) {
    return Pattern.compile("AVERAGE-BANDWIDTH=(\\d+)")
        .matcher(playlist)
        .results()
        .map(match -> Long.parseLong(match.group(1)))
        .toList();
  }

  @Test
  @DisplayName(
      "Should offer only the tiers the worker encodes when a narrow picture rounds the smaller"
          + " tiers to zero width")
  void shouldOfferOnlyTheTiersTheWorkerEncodesWhenANarrowPictureRoundsTheSmallerTiersToZeroWidth() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(1).height(1080).build());

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getVariants())
        .extracting(QualityVariant::width, QualityVariant::height)
        .containsExactly(tuple(2, 1080));
  }

  @Test
  @DisplayName("Should encode a narrow picture when its planned output has positive dimensions")
  void shouldEncodeANarrowPictureWhenItsPlannedOutputHasPositiveDimensions() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(1).height(1080).build());
    var options = StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "test-token");

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(2);
              assertThat(request.height()).isEqualTo(1080);
            });
    assertThat(playlist).contains(",RESOLUTION=2x1080,");
  }

  @Test
  @DisplayName("Should preserve odd displayed dimensions when the source video is copied")
  void shouldPreserveOddDisplayedDimensionsWhenTheSourceVideoIsCopied() {
    probeResults.setDefaultProbe(oddCroppedProbe(ChromaSubsampling.CHROMA_444).build());
    var session = createSession(seedMediaFile().getId(), declarationBuilderFor(HEVC).build());
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "test-token");

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(1917);
              assertThat(request.height()).isEqualTo(1077);
            });
    assertThat(playlist).contains(",RESOLUTION=1917x1077,");
  }

  @Test
  @DisplayName("Should advertise the copied frame rate when the source video is copied")
  void shouldAdvertiseTheCopiedFrameRateWhenTheSourceVideoIsCopied() {
    probeResults.setDefaultProbe(probeAt(VideoPlaybackFactsFixture.FILM_RATE).build());
    var command =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    var session = accepted(service.createSession(command));
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(playlist.lines().filter(line -> line.startsWith("#EXT-X-STREAM-INF:")))
        .singleElement()
        .asString()
        .contains(",FRAME-RATE=23.976");
  }

  private static MediaProbe.MediaProbeBuilder oddCroppedProbe(ChromaSubsampling subsampling) {
    return defaultProbeBuilder()
        .videoCodec("hevc")
        .width(1920)
        .height(1080)
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .chromaSubsampling(Optional.of(subsampling))
                    .codecString(Optional.of("hvc1.1.6.L120.B0"))
                    .containerCropping(
                        Optional.of(
                            ContainerCropping.builder().top(1).bottom(2).left(1).right(2).build()))
                    .build()));
  }

  @Test
  @DisplayName(
      "Should report the unknown frame rate when the video must be encoded and no transcode slot is"
          + " free")
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
      "Should copy the video with no encoded variants when the probe has no frame rate and the"
          + " codec is supported")
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

  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "HIGH_720P", "ORIGINAL"})
  @DisplayName(
      "Should encode and deinterlace an interlaced H.264 source when the client plays H.264 in any quality")
  void shouldEncodeAndDeinterlaceAnInterlacedH264SourceWhenTheClientPlaysH264InAnyQuality(
      VideoQuality quality) {
    probeResults.setDefaultOutcome(outcomeWithFieldOrder(FieldOrder.TT));

    var session = accepted(service.createSession(interlacedSourceCommand(quality)));
    var started = transcodeExecutor.getStartedRequests().getFirst();

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(started.transcodeDecision().deinterlace())
        .contains(new Deinterlace(FieldOrder.TT, DeinterlaceOutput.ONE_FRAME_PER_FIELD));
    assertThat(started.transcodeDecision().outputFrameRate()).contains(new Fraction(60_000, 1_001));
    assertThat(session.getVariants())
        .extracting(QualityVariant::frameRate)
        .containsOnly(new Fraction(60_000, 1_001));
    assertThat(started.framerate()).isEqualTo(OptionalDouble.of(60_000.0 / 1_001));
  }

  // Deinterlaced one frame per field, as item 1.15 of the HLS Authoring Specification for Apple
  // Devices recommends, a 30i source runs at 60000/1001. A progressive 29.97 source encoded at 720p
  // keeps its stored rate. RFC 8216 section 4.3.4.2 gives FRAME-RATE to three decimals.
  @ParameterizedTest(name = "{0} source in {1} says FRAME-RATE={2}")
  @CsvSource({
    "TT, AUTO, 59.940",
    "TT, HIGH_720P, 59.940",
    "TT, ORIGINAL, 59.940",
    "BB, AUTO, 59.940",
    "BB, HIGH_720P, 59.940",
    "BB, ORIGINAL, 59.940",
    "PROGRESSIVE, HIGH_720P, 29.970"
  })
  @DisplayName(
      "Should give every encoded variant the encode's output frame rate as FRAME-RATE when the"
          + " source is 30i or progressive 29.97")
  void
      shouldGiveEveryEncodedVariantTheEncodesOutputFrameRateAsFrameRateWhenTheSourceIs30iOrProgressive2997(
          FieldOrder fieldOrder, VideoQuality quality, String frameRate) {
    probeResults.setDefaultOutcome(outcomeWithFieldOrder(fieldOrder));

    var session = accepted(service.createSession(interlacedSourceCommand(quality)));
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(playlist.lines().filter(line -> line.startsWith("#EXT-X-STREAM-INF:")))
        .hasSize(session.getVariants().size())
        .isNotEmpty()
        .allSatisfy(
            line ->
                assertThat(line)
                    .containsPattern(",FRAME-RATE=" + Pattern.quote(frameRate) + "(,|$)"));
  }

  // ADR 0052: Original encodes a source that it cannot copy, and that encode plays Auto's ladder
  // within the viewer's limits. The probe reported no keyframes, so no copy carries this
  // progressive source. A maximum of 0 is no maximum.
  @ParameterizedTest
  @CsvSource({"0, 0", "2000000, 0", "0, 720"})
  @DisplayName(
      "Should offer Auto's ladder within the viewer's limits when Original encodes a progressive"
          + " source")
  void shouldOfferAutosLadderWithinTheViewersLimitsWhenOriginalEncodesAProgressiveSource(
      int maxBitrate, int maxHeight) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().keyframes(new KeyframeKnowledge.NotReported()).build());
    var autoOptions =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .maxBitrate(maxBitrate)
            .maxHeight(maxHeight)
            .build();
    var originalOptions =
        StreamingOptions.builder()
            .quality(VideoQuality.ORIGINAL)
            .maxBitrate(maxBitrate)
            .maxHeight(maxHeight)
            .build();

    var auto = createSession(seedMediaFile().getId(), UUID.randomUUID(), autoOptions);
    var original = createSession(seedMediaFile().getId(), UUID.randomUUID(), originalOptions);

    assertThat(original.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(auto.getVariants()).hasSizeGreaterThan(1);
    assertThat(original.getVariants()).isEqualTo(auto.getVariants());
    assertThat(original.getTranscodeDecision().audioDecision())
        .isEqualTo(auto.getTranscodeDecision().audioDecision());
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "ORIGINAL"})
  @DisplayName(
      "Should report missing capacity instead of no eligible format when Auto or Original must"
          + " encode an interlaced source and every encoder is busy")
  void
      shouldReportMissingCapacityInsteadOfNoEligibleFormatWhenAutoOrOriginalMustEncodeAnInterlacedSourceAndEveryEncoderIsBusy(
          VideoQuality quality) {
    probeResults.setDefaultOutcome(outcomeWithFieldOrder(FieldOrder.TT));
    transcodeExecutor.setEncodeSlots(Map.of("h264", 0));

    var outcome = service.createSession(interlacedSourceCommand(quality));

    assertThat(outcome)
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  // At 25 frames per second a 10 fps entry would need a third of the rate, 8.33, which is below
  // the lowest rate an encode runs at. Deinterlaced one frame per field, the source runs at 50, and
  // a fifth of that is 10 (ADR 0049).
  @Test
  @DisplayName(
      "Should encode an interlaced source at a divided field rate when the capability entry caps the"
          + " frame rate below the stored rate")
  void
      shouldEncodeAnInterlacedSourceAtADividedFieldRateWhenTheCapabilityEntryCapsTheFrameRateBelowTheStoredRate() {
    probeResults.setDefaultOutcome(
        outcomeWithFrameRateRule(
            VideoPlaybackFactsFixture.interlacedRateRule(new Fraction(25, 1), FieldOrder.TT)));
    var declaration =
        defaultDeclarationBuilder().video(List.of(h264EntryBuilder().maxFrameRate(10).build()));

    var session = accepted(service.createSession(createCommandDeclaring(declaration.build())));

    assertThat(session.getVariants())
        .isNotEmpty()
        .extracting(variant -> variant.frameRate().toDouble())
        .containsOnly(10.0);
  }

  // AV1 is encoded in hardware, which the codec preference puts first on a tie, but its entry caps
  // the deinterlaced 59.94 at 29.97 while H.264 keeps it (ADR 0049).
  @Test
  @DisplayName(
      "Should encode an interlaced source in the codec whose entry keeps the field rate when another"
          + " entry caps it")
  void shouldEncodeAnInterlacedSourceInTheCodecWhoseEntryKeepsTheFieldRateWhenAnotherEntryCapsIt() {
    probeResults.setDefaultOutcome(outcomeWithFieldOrder(FieldOrder.TT));
    transcodeExecutor.setHardwareEncodableVideoCodecFamilies(Set.of("av1"));
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(av1EntryBuilder().maxFrameRate(30).build(), h264EntryBuilder().build()));

    var session = accepted(service.createSession(createCommandDeclaring(declaration.build())));

    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
    assertThat(session.getVariants())
        .isNotEmpty()
        .extracting(QualityVariant::frameRate)
        .containsOnly(new Fraction(60_000, 1_001));
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"ORIGINAL", "AUTO"})
  @DisplayName(
      "Should copy an H.264 source whose stored field order is unspecified when the client plays H.264")
  void shouldCopyAnH264SourceWhoseStoredFieldOrderIsUnspecifiedWhenTheClientPlaysH264(
      VideoQuality quality) {
    var rule = VideoPlaybackFactsFixture.declaredRateRule(new Fraction(30_000, 1_001));
    probeResults.setDefaultOutcome(outcomeWithFrameRateRule(rule));

    var session = accepted(service.createSession(interlacedSourceCommand(quality)));

    assertThat(rule.inputs().fieldOrder()).isEmpty();
    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(transcodeExecutor.getStartedRequests().getFirst().transcodeDecision().deinterlace())
        .isEmpty();
  }

  @Test
  @DisplayName(
      "Should copy the video on a merged segment list when the source's keyframes lie 10 s apart")
  void shouldCopyTheVideoOnAMergedSegmentListWhenTheSourcesKeyframesLieTenSecondsApart() {
    // At the 6 s period, the keyframes at 0, 10, 20 and 30 s leave the slots [12 s, 18 s) and
    // [24 s, 30 s) without one, so each joins the segment before it.
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(36))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(10), Duration.ofSeconds(36)))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var playlist = mediaPlaylist(session);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(extinfDurations(playlist))
        .containsExactly("10.000000", "10.000000", "10.000000", "6.000000");
    assertThat(playlist).contains("#EXT-X-TARGETDURATION:10\n");
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.exactSegmentStarts().orElseThrow().ticks())
                  .containsExactly(0L, 10_000L, 20_000L, 30_000L);
              assertThat(request.mediaSegmentCount()).isEqualTo(4);
            });
  }

  @Test
  @DisplayName(
      "Should end the copy playlist at the last keyframe's segment when no keyframe opens the"
          + " final grid interval")
  void shouldEndTheCopyPlaylistAtTheLastKeyframesSegmentWhenNoKeyframeOpensTheFinalGridInterval() {
    // The 6 s grid divides 20 s into four intervals. No keyframe opens [18 s, 20 s), so that
    // interval joins the segment that the keyframe at 12 s opens.
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(20))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(6), Duration.ofSeconds(12)))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var playlist = mediaPlaylist(session);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(extinfDurations(playlist)).containsExactly("6.000000", "6.000000", "8.000000");
    // RFC 8216 §4.3.3.1: each EXTINF, rounded to the nearest integer, fits the target duration.
    assertThat(playlist).contains("#EXT-X-TARGETDURATION:8\n");
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(TranscodeRequest::mediaSegmentCount)
        .isEqualTo(3);
  }

  // No duration bound limits a merged segment (ADR 0037): the segment that the keyframe at 6 s
  // opens takes every later slot and runs to the end of the 2 h source.
  @Test
  @DisplayName("Should copy the video when the source's keyframes stop long before the media end")
  void shouldCopyTheVideoWhenTheSourcesKeyframesStopLongBeforeTheMediaEnd() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofMinutes(120))
            .keyframes(keyframesAtMillis(0, 6_000))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(extinfDurations(mediaPlaylist(session))).containsExactly("6.000000", "7194.000000");
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(request -> request.exactSegmentStarts().orElseThrow().ticks())
        .isEqualTo(List.of(0L, 6_000L));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("keyframeTimesTooLargeToPlace")
  @DisplayName("Should encode the video when a stored keyframe time is too large to place")
  void shouldEncodeTheVideoWhenAStoredKeyframeTimeIsTooLargeToPlace(
      String description, KeyframePosition keyframe) {
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1001)
            .timeBaseDenominator(24_000)
            .source(KeyframePositionSource.PACKET_SCAN)
            .keyframes(List.of(new KeyframePosition(0, 0), keyframe))
            .build();
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(12))
            .keyframes(new KeyframeKnowledge.Known(positions))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  // In a 1001/24000 time base, 144 ticks present at 6.006 s, so that keyframe opens segment 1 of
  // the 12 s source.
  static Stream<Arguments> keyframeTimesTooLargeToPlace() {
    return Stream.of(
        Arguments.of(
            "presentation time",
            new KeyframePosition(Long.MAX_VALUE / 1000, Long.MAX_VALUE / 1000)),
        Arguments.of(
            "decode time of an opening keyframe",
            new KeyframePosition(144, Long.MAX_VALUE / 1000)));
  }

  @ParameterizedTest
  @MethodSource("keyframesThatAreNotKnown")
  @DisplayName("Should encode the video when the source's keyframe positions are not known yet")
  void shouldEncodeTheVideoWhenTheSourcesKeyframePositionsAreNotKnownYet(
      KeyframeKnowledge keyframes) {
    probeResults.setDefaultProbe(defaultProbeBuilder().keyframes(keyframes).build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  static Stream<KeyframeKnowledge> keyframesThatAreNotKnown() {
    return ProbeFixture.keyframesThatAreNotKnown();
  }

  @Test
  @DisplayName("Should copy the video when every segment interval holds a keyframe")
  void shouldCopyTheVideoWhenEverySegmentIntervalHoldsAKeyframe() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .keyframes(
                ProbeFixture.keyframesEvery(Duration.ofMillis(5_005), Duration.ofMinutes(120)))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getVariants()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should advertise each segment's real duration when the video is copied from an"
          + " irregular-keyframe source")
  void shouldAdvertiseEachSegmentsRealDurationWhenTheVideoIsCopiedFromAnIrregularKeyframeSource() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(24))
            .keyframes(keyframesAtMillis(0, 6_500, 12_000, 18_000))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var playlist = mediaPlaylist(session);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(extinfDurations(playlist))
        .containsExactly("6.500000", "5.500000", "6.000000", "6.000000");
    // RFC 8216 §4.3.3.1: each EXTINF, rounded to the nearest integer, fits the target duration.
    assertThat(playlist).contains("#EXT-X-TARGETDURATION:7\n");
  }

  @Test
  @DisplayName(
      "Should send each segment's exact start in the source's keyframe ticks when the video is copied")
  void shouldSendEachSegmentsExactStartInTheSourcesKeyframeTicksWhenTheVideoIsCopied() {
    // Keyframes at 0.04, 6.5, 12.5 and 18.5 s on a 90 kHz time base. Segment 0 starts at zero,
    // because it also holds the media before its keyframe.
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(90_000)
            .source(KeyframePositionSource.PACKET_SCAN)
            .keyframes(
                LongStream.of(3_600, 585_000, 1_125_000, 1_665_000)
                    .mapToObj(ticks -> new KeyframePosition(ticks, ticks))
                    .toList())
            .build();
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(24))
            .keyframes(new KeyframeKnowledge.Known(positions))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(TranscodeRequest::exactSegmentStarts)
        .isEqualTo(
            Optional.of(
                new ExactSegmentStarts(
                    List.of(0L, 585_000L, 1_125_000L, 1_665_000L), new Fraction(1, 90_000))));
  }

  // At the 6 s period, the keyframes at 0, 10, 20 and 30 s open segments. The keyframes at 3 and
  // 22 s lie in a slot that another keyframe already opens, and the slots [12 s, 18 s) and
  // [24 s, 30 s) hold none, so each merges into the segment before it.
  @Test
  @DisplayName(
      "Should send one exact start for each merged segment when the copy's keyframes lie unevenly")
  void shouldSendOneExactStartForEachMergedSegmentWhenTheCopysKeyframesLieUnevenly() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(36))
            .keyframes(keyframesAtMillis(0, 3_000, 10_000, 20_000, 22_000, 30_000))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(TranscodeRequest::exactSegmentStarts)
        .isEqualTo(
            Optional.of(
                new ExactSegmentStarts(
                    List.of(0L, 10_000L, 20_000L, 30_000L), new Fraction(1, 1_000))));
  }

  @Test
  @DisplayName("Should send no exact segment starts when the video is encoded")
  void shouldSendNoExactSegmentStartsWhenTheVideoIsEncoded() {
    // The keyframes are known, but the client cannot play HEVC, so the video is encoded.
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(24))
            .videoCodec("hevc")
            .keyframes(keyframesAtMillis(0, 6_000, 12_000, 18_000))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .allSatisfy(request -> assertThat(request.exactSegmentStarts()).isEmpty());
  }

  // At the 6 s period, the 24.5 s media ends 0.5 s into slot 4, so that slot merges into segment 3.
  // An encode has no stored keyframe time base for its starts, so they count whole seconds (ADR
  // 0037, segment list).
  @Test
  @DisplayName(
      "Should send each segment's whole-second start in time base 1/1 when an encode's plain grid"
          + " merges its final slot")
  void shouldSendEachSegmentsWholeSecondStartWhenAnEncodesPlainGridMergesItsFinalSlot() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofMillis(24_500))
            .videoCodec("hevc")
            .keyframes(keyframesAtMillis(0, 6_000, 12_000, 18_000, 24_000))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .allSatisfy(
            request ->
                assertThat(request.exactSegmentStarts())
                    .hasValue(
                        new ExactSegmentStarts(List.of(0L, 6L, 12L, 18L), new Fraction(1, 1))));
  }

  // At the 6 s period, the keyframe at 12 s opens slot 2 only 14 ms after the segment that the
  // keyframe at 11.986 s opens, so it stays in that segment. A 24 fps encode would have no frame
  // between the two starts (ADR 0037, segment list).
  @Test
  @DisplayName(
      "Should keep a keyframe that presents less than 2 s after a segment's start inside that"
          + " segment when the video is copied")
  void shouldKeepAKeyframeLessThanTwoSecondsAfterASegmentsStartInsideThatSegmentWhenCopied() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(30))
            .keyframes(keyframesAtMillis(0, 11_986, 12_000, 18_000))
            .build());
    var file = seedMediaFile();

    createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(TranscodeRequest::exactSegmentStarts)
        .isEqualTo(
            Optional.of(
                new ExactSegmentStarts(List.of(0L, 11_986L, 18_000L), new Fraction(1, 1_000))));
  }

  // The keyframe at 19 s opens slot 3 only when it presents at least 2 s before the media's end, so
  // that the last segment lasts at least 2 s too.
  @ParameterizedTest(name = "media ends at {0} ms")
  @CsvSource({"20000, 3", "21000, 4"})
  @DisplayName(
      "Should open a segment at a keyframe only when it presents at least 2 s before the media's"
          + " end when the video is copied")
  void shouldOpenASegmentAtAKeyframeOnlyWhenItPresentsTwoSecondsBeforeTheMediasEnd(
      long durationMillis, int segmentCount) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofMillis(durationMillis))
            .keyframes(keyframesAtMillis(0, 6_000, 12_000, 19_000))
            .build());
    var file = seedMediaFile();

    createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.mediaSegmentCount()).isEqualTo(segmentCount);
              assertThat(request.exactSegmentStarts().orElseThrow().ticks())
                  .containsExactlyElementsOf(
                      List.of(0L, 6_000L, 12_000L, 19_000L).subList(0, segmentCount));
            });
  }

  private static KeyframeKnowledge keyframesAtMillis(long... presentationTimes) {
    return new KeyframeKnowledge.Known(
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(1_000)
            .source(KeyframePositionSource.CONTAINER_INDEX)
            .keyframes(
                Arrays.stream(presentationTimes)
                    .mapToObj(time -> new KeyframePosition(time, time))
                    .toList())
            .build());
  }

  private static String mediaPlaylist(StreamSession session) {
    return new HlsPlaylistService(streamingProperties())
        .generateMediaPlaylist(session, StreamSession.defaultVariant(), "token");
  }

  private static List<String> extinfDurations(String playlist) {
    return playlist
        .lines()
        .filter(line -> line.startsWith("#EXTINF:"))
        .map(line -> line.substring("#EXTINF:".length(), line.indexOf(',')))
        .toList();
  }

  @Nested
  @DisplayName("Session audio language")
  class SessionAudioLanguage {

    private final FakeMovieRepository movies = new FakeMovieRepository();
    private final FakeSeriesRepository seriesRepository = new FakeSeriesRepository();
    private final FakeSeasonRepository seasons = new FakeSeasonRepository();
    private final FakeEpisodeRepository episodes = new FakeEpisodeRepository();
    private final UUID profileId = UUID.randomUUID();
    private final AuthenticatedIdentity viewer =
        AuthenticatedIdentityFixture.profileScopedBuilder().profileId(profileId).build();
    private LanguagePreferenceService languages;
    private Episode episode;

    @BeforeEach
    void preferEnglishThenGermanForAnEpisodeWithEnglishAndJapaneseTracks() {
      languages =
          new LanguagePreferenceService(
              new FakeAuthorizationService(viewer),
              new FakeLanguagePreferenceRepository(movies, seriesRepository, seasons, episodes),
              movies,
              seriesRepository,
              seasons,
              episodes);
      languagePreferences = languages;
      var series = seriesRepository.save(MediaEntityFixture.buildSeries("Series"));
      var season = MediaEntityFixture.buildSeason("Season 1", 1);
      season.setSeries(series);
      seasons.save(season);
      var unsavedEpisode = MediaEntityFixture.buildEpisode("Episode 1", 1);
      unsavedEpisode.setSeason(season);
      episode = episodes.save(unsavedEpisode);
      languages.setProfilePreferences(
          viewer,
          ProfileLanguagePreferences.builder()
              .audioLanguages(List.of(Language.EN, Language.DE))
              .build());
      useAudioTracks(
          audioTrack(1, "eng").build(),
          audioTrack(2, "jpn").build(),
          audioTrack(3, "fre").comment(Optional.of(true)).build());
    }

    @Test
    @DisplayName(
        "Should play the Japanese track when the series overrides the audio although the Profile"
            + " lists English first")
    void shouldPlayTheJapaneseTrackWhenTheSeriesOverridesTheAudio() {
      overrideAudio(episode.getSeason().getSeries().getId(), Language.JA);

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
    }

    @Test
    @DisplayName("Should play the Profile's first language when no override names one")
    void shouldPlayTheProfilesFirstLanguageWhenNoOverrideNamesOne() {
      useAudioTracks(audioTrack(1, "jpn").build(), audioTrack(2, "en").build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
    }

    @Test
    @DisplayName("Should try the next language when the file holds no track in the first")
    void shouldTryTheNextLanguageWhenTheFileHoldsNoTrackInTheFirst() {
      overrideAudio(episode.getId(), Language.KO);
      useAudioTracks(audioTrack(1, "jpn").build(), audioTrack(2, "ger").build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
    }

    @Test
    @DisplayName(
        "Should play the file's first audio track when the file holds none of the languages")
    void shouldPlayTheFilesFirstAudioTrackWhenTheFileHoldsNoneOfTheLanguages() {
      useAudioTracks(audioTrack(1, "jpn").build(), audioTrack(2, "kor").build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(1);
    }

    @Test
    @DisplayName(
        "Should play the file's default audio track when the file holds none of the languages")
    void shouldPlayTheFilesDefaultAudioTrackWhenTheFileHoldsNoneOfTheLanguages() {
      useAudioTracks(
          audioTrack(1, "jpn").build(),
          audioTrack(2, "kor").isDefault(true).build(),
          audioTrack(3, "ita").build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
    }

    @Test
    @DisplayName(
        "Should play the next track when the file's first track is a commentary and no language"
            + " matches")
    void shouldPlayTheNextTrackWhenTheFilesFirstTrackIsACommentaryAndNoLanguageMatches() {
      useAudioTracks(
          audioTrack(1, "jpn").comment(Optional.of(true)).isDefault(true).build(),
          audioTrack(2, "kor").build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
    }

    @Test
    @DisplayName("Should play a regional track when the preferred language names its base language")
    void shouldPlayARegionalTrackWhenThePreferredLanguageNamesItsBaseLanguage() {
      overrideAudio(episode.getId(), Language.PT);
      useAudioTracks(audioTrack(1, "eng").build(), audioTrack(2, "pt-BR").build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
    }

    @Test
    @DisplayName(
        "Should play the exact regional track when a track of the base language comes first")
    void shouldPlayTheExactRegionalTrackWhenATrackOfTheBaseLanguageComesFirst() {
      overrideAudio(episode.getId(), Language.PT_BR);
      useAudioTracks(audioTrack(1, "por").build(), audioTrack(2, "pt-BR").build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
    }

    @Test
    @DisplayName("Should play the session's own language when the file belongs to no title")
    void shouldPlayTheSessionsOwnLanguageWhenTheFileBelongsToNoTitle() {
      var file = seedMediaFile();

      var session =
          accepted(
              service.createSession(
                  createStreamSessionCommandBuilder(file.getId(), profileId)
                      .options(
                          StreamingOptions.builder()
                              .quality(VideoQuality.AUTO)
                              .audioLanguage(Language.JA)
                              .build())
                      .build()));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
    }

    // ADR 0043: the Profile's preferences come before the file's default, with or without a title.
    @Test
    @DisplayName(
        "Should play the Profile's first language when the file belongs to no title and the session"
            + " names none")
    void shouldPlayTheProfilesFirstLanguageWhenTheFileBelongsToNoTitleAndTheSessionNamesNone() {
      useAudioTracks(audioTrack(1, "jpn").isDefault(true).build(), audioTrack(2, "eng").build());
      var file = seedMediaFile();

      var session =
          accepted(
              service.createSession(
                  createStreamSessionCommandBuilder(file.getId(), profileId).build()));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
    }

    @Test
    @DisplayName("Should name no audio stream when the session carries no audio")
    void shouldNameNoAudioStreamWhenTheSessionCarriesNoAudio() {
      useAudioTracks(StreamInfo.builder().index(1).codecType("audio").build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioDecision().mode()).isEqualTo(AudioMode.NONE);
      assertThat(session.getTranscodeDecision().audioStreamIndex()).isEmpty();
    }

    @Test
    @DisplayName(
        "Should play the session's own language when it names one over an episode override")
    void shouldPlayTheSessionsOwnLanguageWhenItNamesOneOverAnEpisodeOverride() {
      overrideAudio(episode.getId(), Language.EN);

      var session =
          createEpisodeSession(
              StreamingOptions.builder().quality(VideoQuality.AUTO).audioLanguage(Language.JA));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
    }

    @Test
    @DisplayName("Should pass over a commentary track when it is the only track in a language")
    void shouldPassOverACommentaryTrackWhenItIsTheOnlyTrackInALanguage() {
      overrideAudio(episode.getId(), Language.FR);

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(1);
    }

    @Test
    @DisplayName("Should pass over an audio description track when it is in the preferred language")
    void shouldPassOverAnAudioDescriptionTrackWhenItIsInThePreferredLanguage() {
      useAudioTracks(
          audioTrack(1, "jpn").build(),
          audioTrack(2, "eng")
              .audioProperties(
                  Optional.of(AudioStreamProperties.builder().visualImpaired(true).build()))
              .build(),
          audioTrack(3, "eng").build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(3);
    }

    // ADR 0043: a track whose title marks a commentary counts as flagged for the default track.
    @Test
    @DisplayName(
        "Should pass over a track whose title marks a commentary when it is in the preferred"
            + " language")
    void shouldPassOverATrackWhoseTitleMarksACommentaryWhenItIsInThePreferredLanguage() {
      useAudioTracks(
          audioTrack(1, "jpn").build(),
          audioTrack(2, "eng").title(Optional.of("Director's Commentary")).build(),
          audioTrack(3, "eng").build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(3);
    }

    // ADR 0043: without a title, a handler name that is not generic marks the track.
    @Test
    @DisplayName(
        "Should pass over a track whose handler name marks an audio description when it has no"
            + " title")
    void shouldPassOverATrackWhoseHandlerNameMarksAnAudioDescriptionWhenItHasNoTitle() {
      useAudioTracks(
          audioTrack(1, "jpn").build(),
          audioTrack(2, "eng").handlerName(Optional.of("Descriptive audio")).build(),
          audioTrack(3, "eng").build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(3);
    }

    // ADR 0043: a dependent track played alone carries only what a receiver mixes in.
    @Test
    @DisplayName(
        "Should play a complete audio description track when the only other track is dependent")
    void shouldPlayACompleteAudioDescriptionTrackWhenTheOnlyOtherTrackIsDependent() {
      useAudioTracks(
          audioTrack(1, "eng")
              .audioProperties(Optional.of(AudioStreamProperties.builder().dependent(true).build()))
              .build(),
          audioTrack(2, "eng")
              .audioProperties(
                  Optional.of(AudioStreamProperties.builder().descriptions(true).build()))
              .build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
    }

    @Test
    @DisplayName("Should describe the chosen track's codec when the chosen track is not the first")
    void shouldDescribeTheChosenTracksCodecWhenTheChosenTrackIsNotTheFirst() {
      useAudioTracks(
          audioTrack(1, "jpn").build(),
          ProbeFixture.audioStreamBuilder("ac3")
              .index(2)
              .language(Optional.of("eng"))
              .channels(OptionalInt.of(6))
              .build());

      var session = createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO));

      assertThat(session.getTranscodeDecision().audioStreamIndex()).hasValue(2);
      assertThat(session.getMediaProbe().audioCodec()).isEqualTo("ac3");
      assertThat(session.getMediaProbe().audioChannels()).hasValue(6);
    }

    // ADR 0050: AAC encodes a 7.0 track in 5.0, so the encode must read the chosen track's layout.
    @Test
    @DisplayName(
        "Should encode the chosen track's layout when the chosen track is not the first audio"
            + " track")
    void shouldEncodeTheChosenTracksLayoutWhenTheChosenTrackIsNotTheFirstAudioTrack() {
      useAudioTracks(
          audioTrack(1, "jpn")
              .audioProperties(
                  Optional.of(
                      AudioStreamProperties.builder().channelLayout(Optional.of("stereo")).build()))
              .build(),
          ProbeFixture.audioStreamBuilder("dts")
              .index(2)
              .language(Optional.of("eng"))
              .channels(OptionalInt.of(7))
              .audioProperties(
                  Optional.of(
                      AudioStreamProperties.builder().channelLayout(Optional.of("7.0")).build()))
              .build());
      var declaration =
          defaultDeclarationBuilder()
              .audio(List.of(aacEntryBuilder().maxChannels(8).build()))
              .build();

      var session =
          createEpisodeSession(StreamingOptions.builder().quality(VideoQuality.AUTO), declaration);

      assertThat(session.getTranscodeDecision().audioDecision())
          .isEqualTo(
              AudioDecision.builder()
                  .mode(AudioMode.TRANSCODE)
                  .codec("aac")
                  .channels(5)
                  .channelLayout(Optional.of(ChannelLayout.FIVE_POINT_ZERO))
                  .bitrate(384_000L)
                  .build());
    }

    private void overrideAudio(UUID titleId, Language language) {
      languages.setLanguageOverride(
          viewer,
          SetLanguageOverrideCommand.builder()
              .itemId(titleId)
              .change(
                  LanguageOverrideChange.builder()
                      .audioLanguage(OverrideFieldChange.replaceWith(Optional.of(language)))
                      .build())
              .build());
    }

    private StreamInfo.StreamInfoBuilder audioTrack(int index, String language) {
      return ProbeFixture.audioStreamBuilder("aac")
          .index(index)
          .language(Optional.of(language))
          .channels(OptionalInt.of(2));
    }

    private void useAudioTracks(StreamInfo... audioTracks) {
      var probe = ProbeFixture.completeProbe(defaultProbeBuilder().build());
      var streams = new ArrayList<StreamInfo>();
      streams.add(probe.streams().getFirst());
      streams.addAll(List.of(audioTracks));
      probeResults.setDefaultOutcome(
          ProbeOutcome.Success.builder()
              .container(probe.container())
              .streams(streams)
              .keyframes(probe.keyframes())
              .chosenVideoStream(probe.chosenVideoStream())
              .streamTitlesOmitted(probe.streamTitlesOmitted())
              .build());
    }

    private StreamSession createEpisodeSession(StreamingOptions.StreamingOptionsBuilder options) {
      return createEpisodeSession(options, defaultDeclarationBuilder().build());
    }

    private StreamSession createEpisodeSession(
        StreamingOptions.StreamingOptionsBuilder options, ClientCapabilityDeclaration declaration) {
      var file =
          mediaFileRepository.save(
              MediaFile.builder()
                  .mediaId(episode.getId())
                  .filepathUri("file:///media/series/episode.mkv")
                  .filename("episode.mkv")
                  .status(MediaFileStatus.MATCHED)
                  .size(1_000_000L)
                  .build());
      return accepted(
          service.createSession(
              createStreamSessionCommandBuilder(file.getId(), profileId)
                  .options(options.build())
                  .capabilityDeclaration(declaration)
                  .build()));
    }
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
    meters = new SimpleMeterRegistry();
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
      FakeTranscodeExecutor executor, RuntimeStreamSessionRegistry registry) {
    var properties = streamingProperties();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(executor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(registry)
            .heldRequests(executor.heldSegmentRequests())
            .sessionMutex(sessionMutex)
            .build();
    coordinator = rig.coordinator();
    return HlsStreamingService.builder()
        .mediaFileRepository(mediaFileRepository)
        .transcodeExecutor(executor)
        .segmentStore(segmentStore)
        .playbackProbeService(
            playbackProbeServiceBuilder(probeResults).eventPublisher(probeEvents).build())
        .transcodeDecisionService(new TranscodeDecisionService(properties, meters))
        .qualityLadderService(new QualityLadderService())
        .properties(properties)
        .authorityGate(authorityGate)
        .runtimeRegistry(registry)
        .producerLifecycle(rig.lifecycle())
        .deliveryCoordinator(rig.coordinator())
        .meterRegistry(meters)
        .languagePreferences(
            (profileId, file) -> languagePreferences.audioLanguagesFor(profileId, file))
        .build();
  }

  private double capacityRefusals(String cause) {
    var counter =
        meters.find("streamarr.streaming.capacity_refusals").tag("cause", cause).counter();
    if (counter == null) {
      return 0;
    }

    return counter.count();
  }

  private double missingEncoderSessions(String codecFamily) {
    return meters
        .find(MISSING_ENCODER_SESSIONS_METRIC)
        .tag("codec_family", codecFamily)
        .counters()
        .stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  private double missingEncoderSessions() {
    return meters.find(MISSING_ENCODER_SESSIONS_METRIC).counters().stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  private static WorkerVerifiedCodecFamilies connectedWorker(Set<String> codecFamilies) {
    return WorkerVerifiedCodecFamilies.builder()
        .workerId(UUID.randomUUID())
        .codecFamilies(codecFamilies)
        .build();
  }

  // One connected worker that encodes exactly these codec families, with free slots for each.
  private void connectWorkerEncoding(Set<String> codecFamilies) {
    transcodeExecutor.setEncodableVideoCodecFamilies(codecFamilies);
    transcodeExecutor.setVerifiedVideoCodecFamiliesByWorker(
        List.of(connectedWorker(codecFamilies)));
  }

  @Test
  @DisplayName("Should encode a new child when the copied format mismatches")
  void shouldEncodeANewChildWhenTheCopiedFormatMismatches() {
    var playback = logicalPlayback();
    var command = logicalStart().build();
    var initial = accepted(playback.start(command));
    var oldId = initial.currentFormatAttempt().streamSessionId();

    playback.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(oldId, UUID.randomUUID(), "default"));

    var recovered =
        playback
            .access(
                PlaybackSessionRequest.builder()
                    .playbackSessionId(initial.playbackSessionId())
                    .identity(command.identity())
                    .build())
            .orElseThrow();
    assertThat(recovered.formatAttemptCount()).isEqualTo(2);
    assertThat(recovered.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.streamSessionId()).isNotEqualTo(oldId);
              assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                  .isFalse();
              assertThat(ready.session().getTranscodeDecision().videoCodecFamily())
                  .isEqualTo("h264");
              assertThat(service.getAllSessions()).containsExactly(ready.session());
            });
  }

  @Test
  @DisplayName("Should encode the video when only two capability entries together accept the copy")
  void shouldEncodeTheVideoWhenOnlyTwoCapabilityEntriesTogetherAcceptTheCopy() {
    probeResults.setDefaultProbe(probeVersion5UhdAt60().build());

    var session = createSession(seedMediaFile().getId(), uhdAt30AndHdAt60Declaration());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName(
      "Should start Original with an encode in Auto when only two capability entries together"
          + " accept the copy")
  void shouldStartOriginalWithAnEncodeInAutoWhenOnlyTwoCapabilityEntriesTogetherAcceptTheCopy() {
    probeResults.setDefaultProbe(probeVersion5UhdAt60().build());

    var snapshot =
        accepted(
            logicalPlayback()
                .start(
                    logicalStart()
                        .options(StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build())
                        .capabilityDeclaration(uhdAt30AndHdAt60Declaration())
                        .build()));

    assertThatOriginalStartedWithAnEncodeInAuto(snapshot);
  }

  // Auto and a fixed quality start playback that may copy or encode the video.
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "UHD_4K"})
  @DisplayName(
      "Should encode the video in Auto or a fixed quality when only two capability entries"
          + " together accept the copy")
  void shouldEncodeTheVideoInAutoOrAFixedQualityWhenOnlyTwoCapabilityEntriesTogetherAcceptTheCopy(
      VideoQuality quality) {
    probeResults.setDefaultProbe(probeVersion5UhdAt60().build());

    var attempt = startPlayback(quality, uhdAt30AndHdAt60Declaration());

    assertThat(attempt)
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().transcodeMode())
                    .isEqualTo(TranscodeMode.VIDEO_TRANSCODE));
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "UHD_4K", "ORIGINAL"})
  @DisplayName(
      "Should copy the video in Auto, a fixed quality or Original when one capability entry accepts"
          + " the copy")
  void shouldCopyTheVideoInAutoAFixedQualityOrOriginalWhenOneCapabilityEntryAcceptsTheCopy(
      VideoQuality quality) {
    probeResults.setDefaultProbe(probeVersion5UhdAt60().build());
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(3840).maxHeight(2160).build()))
            .build();

    var attempt = startPlayback(quality, declaration);

    assertThat(attempt)
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().transcodeMode())
                    .isEqualTo(TranscodeMode.REMUX));
  }

  // A phone records portrait video as a portrait picture, so a landscape maximum bounds it turned.
  @Test
  @DisplayName(
      "Should copy a portrait source when its picture fits the capability entry's maximum turned on"
          + " its side")
  void shouldCopyAPortraitSourceWhenItsPictureFitsTheCapabilityEntrysMaximumTurnedOnItsSide() {
    probeResults.setDefaultProbe(probeVersion5At24(new PictureSize(1080, 1920)).build());

    var session = createSession(seedMediaFile().getId(), landscapeHdDeclaration());

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName(
      "Should encode a portrait source when its longer side exceeds the capability entry's larger"
          + " maximum")
  void shouldEncodeAPortraitSourceWhenItsLongerSideExceedsTheCapabilityEntrysLargerMaximum() {
    probeResults.setDefaultProbe(probeVersion5At24(new PictureSize(1080, 2400)).build());

    var session = createSession(seedMediaFile().getId(), landscapeHdDeclaration());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  // Decoder limits bound decoded frames, so the container's cropping does not shrink the picture
  // that a capability entry's maximum bounds.
  @Test
  @DisplayName(
      "Should encode the video when its decoded picture exceeds the capability entry's maximum and"
          + " the container crops it to fit")
  void
      shouldEncodeTheVideoWhenItsDecodedPictureExceedsTheCapabilityEntrysMaximumAndTheContainerCropsItToFit() {
    var croppedTo1920x1080 = ContainerCropping.builder().top(0).bottom(8).left(0).right(0).build();
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .width(1920)
            .height(1088)
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .streamIndex(0)
                        .frameRateRule(
                            VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                        .containerCropping(Optional.of(croppedTo1920x1080))
                        .videoProperties(
                            Optional.of(
                                VideoPlaybackFactsFixture.h264HighPropertiesBuilder().build()))
                        .build()))
            .build());

    var session = createSession(seedMediaFile().getId(), landscapeHdDeclaration());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  // An 8-bit SDR H.264 High profile source at level 5.1 and 24 fps that probe version 5 described.
  private static MediaProbe.MediaProbeBuilder probeVersion5At24(PictureSize decodedSize) {
    return defaultProbeBuilder()
        .width(decodedSize.width())
        .height(decodedSize.height())
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                    .videoProperties(
                        Optional.of(
                            VideoPlaybackFactsFixture.h264HighPropertiesBuilder()
                                .codecLevel(OptionalInt.of(51))
                                .build()))
                    .build()));
  }

  // A player that plays H.264 up to 1920×1080.
  private static ClientCapabilityDeclaration landscapeHdDeclaration() {
    return defaultDeclarationBuilder()
        .video(List.of(h264EntryBuilder().maxWidth(1920).maxHeight(1080).build()))
        .build();
  }

  // ADR 0050: outside Original, the server copies the video only when the source fits every one
  // of the viewer's limits.
  @Test
  @DisplayName(
      "Should encode the fixed quality's picture box when a copyable source is larger than the box")
  void shouldEncodeTheFixedQualitysPictureBoxWhenACopyableSourceIsLargerThanTheBox() {
    var options = StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(1280);
              assertThat(request.height()).isEqualTo(720);
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("copyableSourcesOverOneViewerLimit")
  @DisplayName(
      "Should encode the video in Auto when a copyable source exceeds one of the viewer's limits")
  void shouldEncodeTheVideoInAutoWhenACopyableSourceExceedsOneOfTheViewersLimits(
      SourceAndLimits sourceAndLimits) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().bitrate(sourceAndLimits.sourceBitrate()).build());

    var session =
        createSession(seedMediaFile().getId(), UUID.randomUUID(), sourceAndLimits.options());

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(session.getVariants())
        .extracting(QualityVariant::label)
        .containsExactlyInAnyOrder("720p", "480p", "360p");
  }

  // The 1920×1080 H.264 source fits the default declaration, so only a viewer's limit refuses the
  // copy. Each limit also leaves out the 1080p tier. A source whose bitrate is unknown passes the
  // maximum bitrate, but its picture must still fit the maximum width (ADR 0050).
  static Stream<SourceAndLimits> copyableSourcesOverOneViewerLimit() {
    return Stream.of(
        SourceAndLimits.builder()
            .options(autoOptions().maxWidth(1919).build())
            .sourceBitrate(5_000_000L)
            .build(),
        SourceAndLimits.builder()
            .options(autoOptions().maxHeight(1079).build())
            .sourceBitrate(5_000_000L)
            .build(),
        SourceAndLimits.builder()
            .options(autoOptions().maxBitrate(4_000_000).build())
            .sourceBitrate(40_000_000L)
            .build(),
        SourceAndLimits.builder()
            .options(autoOptions().maxWidth(1919).maxBitrate(4_000_000).build())
            .sourceBitrate(0L)
            .build());
  }

  private static StreamingOptions.StreamingOptionsBuilder autoOptions() {
    return StreamingOptions.builder().quality(VideoQuality.AUTO);
  }

  @Builder
  record SourceAndLimits(StreamingOptions options, long sourceBitrate) {}

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesWithinEveryViewerLimit")
  @DisplayName("Should copy the video when the source fits every one of the viewer's limits")
  void shouldCopyTheVideoWhenTheSourceFitsEveryOneOfTheViewersLimits(
      SourceAndLimits sourceAndLimits) {
    assertCopiesTheVideo(sourceAndLimits);
  }

  static Stream<SourceAndLimits> sourcesWithinEveryViewerLimit() {
    return Stream.of(
        SourceAndLimits.builder()
            .options(autoOptions().maxWidth(1920).maxHeight(1080).maxBitrate(5_000_000).build())
            .sourceBitrate(5_000_000L)
            .build(),
        SourceAndLimits.builder()
            .options(StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build())
            .sourceBitrate(5_000_000L)
            .build());
  }

  // A probe that names no container bitrate reports 0 (ADR 0050).
  @Test
  @DisplayName(
      "Should copy the video when the viewer sets a maximum bitrate and the source's bitrate is"
          + " unknown")
  void shouldCopyTheVideoWhenTheViewerSetsAMaximumBitrateAndTheSourcesBitrateIsUnknown() {
    assertCopiesTheVideo(
        SourceAndLimits.builder()
            .options(autoOptions().maxBitrate(4_000_000).build())
            .sourceBitrate(0L)
            .build());
  }

  private void assertCopiesTheVideo(SourceAndLimits sourceAndLimits) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().bitrate(sourceAndLimits.sourceBitrate()).build());

    var session =
        createSession(seedMediaFile().getId(), UUID.randomUUID(), sourceAndLimits.options());

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(session.getVariants()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = VideoQuality.class,
      names = {"FULL_HD_1080P", "UHD_4K"})
  @DisplayName(
      "Should copy a portrait source when it fits the fixed quality's box turned to its"
          + " orientation")
  void shouldCopyAPortraitSourceWhenItFitsTheFixedQualitysBoxTurnedToItsOrientation(
      VideoQuality quality) {
    assertThat(portraitSessionCopiesVideo(StreamingOptions.builder().quality(quality).build()))
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should encode a portrait source when it is larger than the fixed quality's box in either"
          + " orientation")
  void shouldEncodeAPortraitSourceWhenItIsLargerThanTheFixedQualitysBoxInEitherOrientation() {
    var options = StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build();

    assertThat(portraitSessionCopiesVideo(options)).isFalse();
  }

  // ADR 0050: a client's maximum width and height describe a window or a screen, which has an
  // orientation, so they are not turned with the picture.
  @Test
  @DisplayName(
      "Should copy a portrait source when it fits the viewer's maximum width and height as the"
          + " client gives them")
  void shouldCopyAPortraitSourceWhenItFitsTheViewersMaximumWidthAndHeightAsTheClientGivesThem() {
    var options = autoOptions().maxWidth(1080).maxHeight(1920).build();

    assertThat(portraitSessionCopiesVideo(options)).isTrue();
  }

  @Test
  @DisplayName(
      "Should encode a portrait source when it fits the viewer's maximum width and height only"
          + " turned on their side")
  void shouldEncodeAPortraitSourceWhenItFitsTheViewersMaximumWidthAndHeightOnlyTurnedOnTheirSide() {
    var options = autoOptions().maxWidth(1920).maxHeight(1080).build();

    assertThat(portraitSessionCopiesVideo(options)).isFalse();
  }

  // Whether a session copies a 1080×1920 H.264 source for a player whose entry plays it.
  private boolean portraitSessionCopiesVideo(StreamingOptions options) {
    probeResults.setDefaultProbe(probeVersion5At24(new PictureSize(1080, 1920)).build());

    var session =
        accepted(
            service.createSession(
                createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                    .options(options)
                    .capabilityDeclaration(landscapeHdDeclaration())
                    .build()));

    return session.getTranscodeDecision().transcodeMode().copiesVideo();
  }

  @Test
  @DisplayName(
      "Should copy the video in Original when the source exceeds every one of the viewer's limits")
  void shouldCopyTheVideoInOriginalWhenTheSourceExceedsEveryOneOfTheViewersLimits() {
    probeResults.setDefaultProbe(defaultProbeBuilder().bitrate(40_000_000L).build());
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.ORIGINAL)
            .maxWidth(640)
            .maxHeight(360)
            .maxBitrate(4_000_000)
            .build();

    var attempt = accepted(logicalPlayback().start(logicalStart().options(options).build()));

    assertThat(attempt.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                    .isTrue());
  }

  @ParameterizedTest
  @MethodSource("viewerLimitsThatRefuseTheDefaultSourcesCopy")
  @DisplayName(
      "Should copy a source that has no usable frame rate when the viewer's limits refuse its copy")
  void shouldCopyASourceThatHasNoUsableFrameRateWhenTheViewersLimitsRefuseItsCopy(
      StreamingOptions options) {
    probeResults.setDefaultProbe(defaultProbeBuilder().framerate(OptionalDouble.empty()).build());

    var outcome =
        service.createSession(
            createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), options));

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(accepted(outcome).getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
  }

  // The fixed quality's box, the maximum height and the maximum bitrate each refuse the copy of
  // the 1920×1080 source at 5 Mb/s.
  static Stream<StreamingOptions> viewerLimitsThatRefuseTheDefaultSourcesCopy() {
    return Stream.of(
        StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build(),
        autoOptions().maxHeight(720).build(),
        autoOptions().maxBitrate(1_000_000).build());
  }

  // ADR 0050: the 5 Mb/s source without a usable frame rate copies over a 1 Mb/s maximum, beside
  // which no audio step fits. A lower step would not bring the copy within the maximum, and would
  // turn the copied stereo AAC track into an encode, so the track stays a copy.
  @Test
  @DisplayName(
      "Should keep the session's audio beside a copy of a source without a usable frame rate when"
          + " no audio step fits the maximum bitrate")
  void
      shouldKeepTheSessionsAudioBesideACopyOfASourceWithoutAUsableFrameRateWhenNoAudioStepFitsTheMaximumBitrate() {
    probeResults.setDefaultProbe(defaultProbeBuilder().framerate(OptionalDouble.empty()).build());

    var session =
        createSession(
            seedMediaFile().getId(),
            UUID.randomUUID(),
            autoOptions().maxBitrate(1_000_000).build());

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::mode, AudioDecision::codec, AudioDecision::bitrate)
        .containsExactly(AudioMode.COPY, "aac", 128_000L);
  }

  @Test
  @DisplayName(
      "Should report transient capacity when the viewer's limits refuse a copy and every video"
          + " encoder is busy")
  void shouldReportTransientCapacityWhenTheViewersLimitsRefuseACopyAndEveryVideoEncoderIsBusy() {
    transcodeExecutor.setEncodeSlots(Map.of("h264", 0));
    var options = StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build();
    var command = createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(service.createSession(command))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(capacityRefusals("no_encoder_with_free_slot")).isOne();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  // The 49 Mb/s copy exceeds the maximum bitrate beside every audio step, and its 3840x1920
  // picture exceeds the 720p tier and each maximum. The declaration takes the HEVC copy but lists
  // only HEVC Main 10, which no encode forces, so the server has nothing to encode to.
  @ParameterizedTest(name = "{0}")
  @MethodSource("viewerLimitsThatTheWideUhdCopyExceeds")
  @DisplayName(
      "Should copy the video over the viewer's limits when the capability declaration accepts no"
          + " encode of the source")
  void shouldCopyTheVideoOverTheViewersLimitsWhenTheCapabilityDeclarationAcceptsNoEncodeOfTheSource(
      StreamingOptions options) {
    probeResults.setDefaultProbe(wideUhdHevcProbe().build());

    var session =
        accepted(createSessionDeclaring(hevcMain10OnlyDeclarationBuilder().build(), options));

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::mode, AudioDecision::codec, AudioDecision::bitrate)
        .containsExactly(AudioMode.COPY, "aac", 128_000L);
    assertThat(session.getVariants()).isEmpty();
  }

  static Stream<StreamingOptions> viewerLimitsThatTheWideUhdCopyExceeds() {
    return Stream.of(
        OVER_THE_MAXIMUM_BITRATE,
        StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build(),
        StreamingOptions.builder().quality(VideoQuality.AUTO).maxWidth(1920).build(),
        StreamingOptions.builder().quality(VideoQuality.AUTO).maxHeight(1080).build());
  }

  @ParameterizedTest
  @CsvSource({"H264, h264", "AV1, av1"})
  @DisplayName(
      "Should encode the video when the source exceeds the viewer's limits and the capability"
          + " declaration accepts an encode of it beside the copy")
  void shouldEncodeTheVideoWhenTheSourceExceedsTheViewersLimitsAndTheDeclarationAcceptsAnEncode(
      ProbedCodecFamily encodedFamily, String expectedFamily) {
    probeResults.setDefaultProbe(wideUhdHevcProbe().build());

    var session =
        accepted(
            createSessionDeclaring(
                declarationBuilderFor(HEVC, encodedFamily).build(), OVER_THE_MAXIMUM_BITRATE));

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo(expectedFamily);
  }

  // Without a connected worker no verified encoder is registered either.
  @Test
  @DisplayName(
      "Should report transient capacity instead of copying over the viewer's limits when no worker"
          + " is connected")
  void shouldReportTransientCapacityInsteadOfCopyingOverTheViewersLimitsWhenNoWorkerIsConnected() {
    probeResults.setDefaultProbe(wideUhdHevcProbe().build());
    transcodeExecutor.setHealthy(false);
    transcodeExecutor.setEncodeSlots(Map.of());

    assertThat(
            createSessionDeclaring(
                declarationBuilderFor(HEVC, H264).build(), OVER_THE_MAXIMUM_BITRATE))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(capacityRefusals("no_connected_worker")).isOne();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  // A worker with an H.264 encoder is connected again right after the server reads the verified
  // encoders, either after none was connected or after a momentary disconnect. The read describes
  // a moment without a connected worker, so it never admits a copy over the viewer's limits.
  @ParameterizedTest(name = "a worker was connected before the read: {0}")
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should report transient capacity instead of copying over the viewer's limits when a worker"
          + " connects right after the server reads the verified encoders")
  void
      shouldReportTransientCapacityInsteadOfCopyingOverTheViewersLimitsWhenAWorkerConnectsRightAfterTheVerifiedEncodersAreRead(
          boolean connectedBeforeTheRead) {
    probeResults.setDefaultProbe(wideUhdHevcProbe().build());
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of("h264"));
    transcodeExecutor.setHealthy(connectedBeforeTheRead);
    transcodeExecutor.changeWorkersAroundNextVerifiedFamiliesRead(
        () -> transcodeExecutor.setHealthy(false), () -> transcodeExecutor.setHealthy(true));

    assertThat(
            createSessionDeclaring(
                declarationBuilderFor(HEVC, H264).build(), OVER_THE_MAXIMUM_BITRATE))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(capacityRefusals("no_connected_worker")).isOne();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  // A worker with an AV1 encoder connects after the server reads the verified encoders but before
  // it reads the encoders with a free slot. An encoder with a free slot is verified, so the
  // server encodes in AV1 instead of copying over the viewer's limits.
  @Test
  @DisplayName(
      "Should encode the video instead of copying over the viewer's limits when a worker with an"
          + " accepted encoder connects right after the server reads the verified encoders")
  void
      shouldEncodeTheVideoInsteadOfCopyingOverTheViewersLimitsWhenAWorkerWithAnAcceptedEncoderConnectsRightAfterTheVerifiedEncodersAreRead() {
    probeResults.setDefaultProbe(wideUhdHevcProbe().build());
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of("h264"));
    transcodeExecutor.changeWorkersAroundNextVerifiedFamiliesRead(
        () -> {}, () -> transcodeExecutor.setEncodableVideoCodecFamilies(Set.of("h264", "av1")));

    var session =
        accepted(
            createSessionDeclaring(
                declarationBuilderFor(HEVC, AV1).build(), OVER_THE_MAXIMUM_BITRATE));

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("av1");
  }

  // The only worker with an H.264 encoder leaves right after the server reads the verified
  // encoders, beside a connected worker without one. The read refused the copy because an H.264
  // encoder was verified, so the stream session fails for capacity with an answer that the client
  // can retry, not with a refusal that blames the viewer's device.
  @Test
  @DisplayName(
      "Should report transient capacity instead of refusing the video format when the worker with"
          + " the accepted encoder leaves right after the server reads the verified encoders")
  void
      shouldReportTransientCapacityInsteadOfRefusingTheVideoFormatWhenTheWorkerWithTheAcceptedEncoderLeavesRightAfterTheVerifiedEncodersAreRead() {
    probeResults.setDefaultProbe(wideUhdHevcProbe().build());
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of("h264"));
    transcodeExecutor.changeWorkersAroundNextVerifiedFamiliesRead(
        () -> {}, () -> transcodeExecutor.setEncodeSlots(Map.of()));

    assertThat(
            createSessionDeclaring(
                declarationBuilderFor(HEVC, H264).build(), OVER_THE_MAXIMUM_BITRATE))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(capacityRefusals("no_encoder_with_free_slot")).isOne();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  // A busy worker's verified encoder counts, so the session waits for its slot instead of copying.
  // A free slot on an encoder for a codec that the declaration does not accept changes nothing.
  @ParameterizedTest(name = "{0}")
  @MethodSource("busyEncodersForACodecThatTheDeclarationAccepts")
  @DisplayName(
      "Should report transient capacity instead of copying over the viewer's limits when the"
          + " encoder that the declaration accepts is busy")
  void shouldReportTransientCapacityInsteadOfCopyingOverTheViewersLimitsWhenTheEncoderIsBusy(
      BusyEncoder busy) {
    probeResults.setDefaultProbe(wideUhdHevcProbe().build());
    transcodeExecutor.setEncodeSlots(busy.encodeSlots());

    assertThat(
            createSessionDeclaring(
                declarationBuilderFor(HEVC, busy.acceptedEncode()).build(),
                OVER_THE_MAXIMUM_BITRATE))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(capacityRefusals("no_encoder_with_free_slot")).isOne();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  static Stream<BusyEncoder> busyEncodersForACodecThatTheDeclarationAccepts() {
    return Stream.of(
        BusyEncoder.builder().acceptedEncode(H264).encodeSlots(Map.of("h264", 0)).build(),
        BusyEncoder.builder().acceptedEncode(AV1).encodeSlots(Map.of("h264", 1, "av1", 0)).build());
  }

  @Builder
  record BusyEncoder(ProbedCodecFamily acceptedEncode, Map<String, Integer> encodeSlots) {}

  // Every worker image ships an H.264 and an AV1 encoder, so a connected worker without a verified
  // encoder for the codec that the declaration accepts is faulty, and no free slot would let the
  // server encode.
  @ParameterizedTest(name = "{0}")
  @MethodSource("connectedWorkersWithoutAnEncoderForTheAcceptedCodec")
  @DisplayName(
      "Should copy the video over the viewer's limits when no connected worker has a verified"
          + " encoder for a codec that the declaration accepts")
  void
      shouldCopyTheVideoOverTheViewersLimitsWhenNoConnectedWorkerHasAVerifiedEncoderForACodecThatTheDeclarationAccepts(
          UnverifiedEncode unverified) {
    probeResults.setDefaultProbe(wideUhdHevcProbe().build());
    transcodeExecutor.setEncodableVideoCodecFamilies(unverified.verifiedFamilies());

    var session =
        accepted(
            createSessionDeclaring(
                declarationBuilderFor(HEVC, unverified.acceptedEncode()).build(),
                OVER_THE_MAXIMUM_BITRATE));

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getVariants()).isEmpty();
  }

  static Stream<UnverifiedEncode> connectedWorkersWithoutAnEncoderForTheAcceptedCodec() {
    return Stream.of(
        UnverifiedEncode.builder().acceptedEncode(AV1).verifiedFamilies(Set.of("h264")).build(),
        UnverifiedEncode.builder().acceptedEncode(H264).verifiedFamilies(Set.of("av1")).build(),
        UnverifiedEncode.builder().acceptedEncode(H264).verifiedFamilies(Set.of()).build());
  }

  @Builder
  record UnverifiedEncode(ProbedCodecFamily acceptedEncode, Set<String> verifiedFamilies) {}

  // An encode-only format attempt follows a copy that the client could not play, so it never falls
  // back to a copy, even over the viewer's limits with nothing to encode to.
  @ParameterizedTest(name = "{0}")
  @MethodSource("declarationsAndWorkersThatLeaveNothingToEncodeTo")
  @DisplayName(
      "Should refuse the video format instead of copying over the viewer's limits when the format"
          + " attempt requires an encode and the server has nothing to encode to")
  void
      shouldRefuseTheVideoFormatInsteadOfCopyingOverTheViewersLimitsWhenTheFormatAttemptRequiresAnEncodeAndTheServerHasNothingToEncodeTo(
          NothingToEncodeTo nothing) {
    probeResults.setDefaultProbe(wideUhdHevcProbe().build());
    transcodeExecutor.setEncodableVideoCodecFamilies(nothing.verifiedFamilies());

    var outcome =
        service.createSession(
            createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                .options(OVER_THE_MAXIMUM_BITRATE)
                .capabilityDeclaration(
                    declarationBuilderFor(nothing.declaredVideo().toArray(ProbedCodecFamily[]::new))
                        .build())
                .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                .build());

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  static Stream<NothingToEncodeTo> declarationsAndWorkersThatLeaveNothingToEncodeTo() {
    return Stream.of(
        NothingToEncodeTo.builder()
            .declaredVideo(List.of(HEVC))
            .verifiedFamilies(Set.of("h264", "av1"))
            .build(),
        NothingToEncodeTo.builder()
            .declaredVideo(List.of(HEVC, AV1))
            .verifiedFamilies(Set.of("h264"))
            .build());
  }

  @Builder
  record NothingToEncodeTo(List<ProbedCodecFamily> declaredVideo, Set<String> verifiedFamilies) {}

  // A 1920x1 picture has no encoded size, so the H.264 entry accepts no encode that a worker runs.
  @Test
  @DisplayName(
      "Should copy the video over the viewer's limits when the worker encodes the source's picture"
          + " at no size")
  void shouldCopyTheVideoOverTheViewersLimitsWhenTheWorkerEncodesTheSourcesPictureAtNoSize() {
    probeResults.setDefaultProbe(wideUhdHevcProbe().width(1920).height(1).build());

    var session =
        accepted(
            createSessionDeclaring(
                declarationBuilderFor(HEVC, H264).build(), OVER_THE_MAXIMUM_BITRATE));

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  // The Auto picture maximum of 1080 lines refuses the 3840x1920 copy, and the declaration accepts
  // no encode of it. The 4.5 Mb/s video beside the copied 384 kb/s AAC 5.1 track costs 4,884 kb/s,
  // which fits a maximum of exactly that much.
  @Test
  @DisplayName(
      "Should keep the copied audio beside a copy over the viewer's picture limits when the copy's"
          + " cost equals the maximum bitrate")
  void
      shouldKeepTheCopiedAudioBesideACopyOverTheViewersPictureLimitsWhenTheCopysCostEqualsTheMaximumBitrate() {
    var session = createCopyOverThePictureLimitsWithAac51(4_884_000);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::mode, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly(AudioMode.COPY, 6, 384_000L);
    assertDeliversTheSessionsAudioWithinTheMaximumBitrate(session, 4_884_000);
  }

  // Under a maximum below the copy's 4,884 kb/s the audio steps down as beside a copy within the
  // viewer's limits (ADR 0050): AAC 5.1 at 320 kb/s fits down to 4,820 kb/s, and AAC stereo at
  // 128 kb/s below that.
  @ParameterizedTest(name = "{0}")
  @MethodSource("maximumsAndTheFirstAudioStepBesideACopyOverThePictureLimits")
  @DisplayName(
      "Should take the first audio step at which a copy over the viewer's picture limits fits the"
          + " maximum bitrate when the session's audio leaves it over the maximum")
  void
      shouldTakeTheFirstAudioStepAtWhichACopyOverTheViewersPictureLimitsFitsWhenTheSessionsAudioLeavesItOverTheMaximum(
          StepBesideCopyOverTheLimits expected) {
    var session = createCopyOverThePictureLimitsWithAac51(expected.maxBitrate());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::codec, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly("aac", expected.channels(), expected.bitrate());
    assertDeliversTheSessionsAudioWithinTheMaximumBitrate(session, expected.maxBitrate());
  }

  static Stream<StepBesideCopyOverTheLimits>
      maximumsAndTheFirstAudioStepBesideACopyOverThePictureLimits() {
    var aac51 = StepBesideCopyOverTheLimits.builder().channels(6).bitrate(320_000L);
    var aacStereo = StepBesideCopyOverTheLimits.builder().channels(2).bitrate(128_000L);
    return Stream.of(
        aac51.maxBitrate(4_883_999).build(),
        aac51.maxBitrate(4_820_000).build(),
        aacStereo.maxBitrate(4_819_999).build());
  }

  @Builder
  record StepBesideCopyOverTheLimits(int maxBitrate, int channels, long bitrate) {}

  // At 1 Mb/s even the lowest audio step leaves the 4.5 Mb/s copy over the maximum. A lower step
  // would not bring it within the maximum, so the copied track stays (ADR 0050).
  @Test
  @DisplayName(
      "Should keep the session's audio beside a copy over the viewer's picture limits when no audio"
          + " step fits the maximum bitrate")
  void shouldKeepTheSessionsAudioBesideACopyOverTheViewersPictureLimitsWhenNoAudioStepFits() {
    var session = createCopyOverThePictureLimitsWithAac51(1_000_000);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::mode, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly(AudioMode.COPY, 6, 384_000L);
  }

  // A 3840x1920 HEVC source with 4.5 Mb/s of video beside a 384 kb/s AAC 5.1 track, for a client
  // that accepts the HEVC copy and AAC up to 5.1 but no encode of the video, because it lists only
  // HEVC Main 10, under Auto's 1080-line maximum and the given maximum bitrate.
  private StreamSession createCopyOverThePictureLimitsWithAac51(int maxBitrate) {
    var probe = wideUhdHevcProbe().bitrate(5_000_000L);
    var streams =
        List.of(
            ProbeFixture.videoStreamBuilder(probe.build())
                .bitrate(OptionalLong.of(4_500_000L))
                .build(),
            ProbeFixture.audioStreamBuilder("aac")
                .index(1)
                .channels(OptionalInt.of(6))
                .bitrate(OptionalLong.of(384_000L))
                .build());
    probeResults.setDefaultOutcome(ProbeFixture.completeProbe(probe.streams(streams).build()));
    var declaration =
        hevcMain10OnlyDeclarationBuilder()
            .audio(List.of(aacEntryBuilder().maxChannels(6).build()))
            .build();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .maxHeight(1080)
            .maxBitrate(maxBitrate)
            .build();

    return accepted(createSessionDeclaring(declaration, options));
  }

  // ADR 0013's third amendment and ADR 0036: an HDR10 copy reaches a client that declares PQ, and
  // its job carries the probed static values for the worker to write into hvcC.
  @Test
  @DisplayName(
      "Should copy a PQ source with its static HDR values and announce PQ when the client declares"
          + " PQ and a connected worker lists the planned output")
  void
      shouldCopyAPqSourceWithItsStaticHdrValuesAndAnnouncePqWhenTheClientDeclaresPqAndAConnectedWorkerListsThePlannedOutput() {
    probeResults.setDefaultProbe(hdr10HevcProbe());
    transcodeExecutor.setVideoCopySupportByWorker(
        List.of(
            WorkerVideoCopySupport.builder()
                .plannedOutput(true)
                .bitstreamRewrites(Set.of())
                .build()));

    var session =
        accepted(createSessionDeclaring(pqDeclaration(), StreamingOptions.builder().build()));

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(request -> request.transcodeDecision().plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.StreamCopy.class,
            copy -> {
              assertThat(copy.dynamicRange().compatibleBase()).isEqualTo(CompatibleBase.PQ);
              assertThat(copy.masteringDisplay())
                  .contains(VideoPlaybackFactsFixture.MASTERING_DISPLAY);
              assertThat(copy.contentLightLevel())
                  .contains(VideoPlaybackFactsFixture.CONTENT_LIGHT_LEVEL);
            });
    assertThat(
            new HlsPlaylistService(streamingProperties())
                .generateMultivariantPlaylist(session, "t"))
        .contains(",VIDEO-RANGE=PQ");
  }

  @Test
  @DisplayName(
      "Should encode a PQ source when the client declares PQ and no connected worker lists the"
          + " planned output")
  void shouldEncodeAPqSourceWhenTheClientDeclaresPqAndNoConnectedWorkerListsThePlannedOutput() {
    probeResults.setDefaultProbe(hdr10HevcProbe());

    var session =
        accepted(createSessionDeclaring(pqDeclaration(), StreamingOptions.builder().build()));

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(
            new HlsPlaylistService(streamingProperties())
                .generateMultivariantPlaylist(session, "t"))
        .doesNotContain("VIDEO-RANGE");
  }

  // ADR 0036: the job of a copy for a client that declares PQ without HDR10+ lists the strip.
  @Test
  @DisplayName(
      "Should start a copy that strips HDR10+ when the client declares PQ without HDR10+ and a"
          + " connected worker strips it")
  void
      shouldStartACopyThatStripsHdr10PlusWhenTheClientDeclaresPqWithoutHdr10PlusAndAConnectedWorkerStripsIt() {
    probeResults.setDefaultProbe(hdr10HevcProbe(Presence.PRESENT));
    transcodeExecutor.setVideoCopySupportByWorker(
        List.of(
            WorkerVideoCopySupport.builder()
                .plannedOutput(true)
                .bitstreamRewrites(Set.of(BitstreamRewrite.STRIP_HDR10_PLUS))
                .build()));

    accepted(createSessionDeclaring(pqDeclaration(), StreamingOptions.builder().build()));

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(request -> request.transcodeDecision().plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.StreamCopy.class,
            copy -> {
              assertThat(copy.bitstreamRewrites())
                  .containsExactly(BitstreamRewrite.STRIP_HDR10_PLUS);
              assertThat(copy.dynamicRange().hdr10Plus()).isEqualTo(Presence.ABSENT);
            });
  }

  // ADR 0013: a client that declares profile 8.1 at the copy's level gets the copy that keeps Dolby
  // Vision, its job carries the record that the playlist describes, and the playlist announces the
  // Dolby Vision beside the base's CODECS.
  @Test
  @DisplayName(
      "Should copy a profile 8.1 source with its Dolby Vision and announce it when the client"
          + " declares profile 8.1")
  void shouldCopyAProfile81SourceWithItsDolbyVisionAndAnnounceItWhenTheClientDeclaresProfile81() {
    probeResults.setDefaultProbe(profile81HevcProbe());
    transcodeExecutor.setVideoCopySupportByWorker(
        List.of(
            WorkerVideoCopySupport.builder()
                .plannedOutput(true)
                .bitstreamRewrites(Set.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
                .build()));
    var profile81 =
        declarationBuilderFor(H264)
            .video(
                List.of(
                    h264EntryBuilder().build(),
                    hevcEntryBuilder()
                        .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                        .dolbyVisionProfiles(
                            List.of(
                                DolbyVisionProfileCapability.builder()
                                    .profile(DolbyVisionProfile.PROFILE_8_1)
                                    .maxLevel(6)
                                    .build()))
                        .build()))
            .build();

    var session = accepted(createSessionDeclaring(profile81, StreamingOptions.builder().build()));

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(request -> request.transcodeDecision().plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.StreamCopy.class,
            copy -> {
              assertThat(copy.bitstreamRewrites())
                  .containsExactly(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD);
              assertThat(copy.dolbyVisionOutput())
                  .contains(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, 6));
            });
    assertThat(
            new HlsPlaylistService(streamingProperties())
                .generateMultivariantPlaylist(session, "t"))
        .contains("CODECS=\"hvc1.2.4.L150.90,")
        .contains(",VIDEO-RANGE=PQ")
        .contains(",SUPPLEMENTAL-CODECS=\"dvh1.08.06/db1p\"");
  }

  // The HDR10 source below with a profile 8.1 record at level 6.
  private static MediaProbe profile81HevcProbe() {
    return hevcMain10Probe(
        new VideoDynamicRange(
            CompatibleBase.PQ,
            Presence.ABSENT,
            new DolbyVision.Present(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(8))
                        .level(OptionalInt.of(6))
                        .baseLayerCompatibilityId(OptionalInt.of(1))
                        .baseLayer(true)
                        .rpu(true)
                        .build()),
                Presence.PRESENT)));
  }

  // HEVC with a PQ base beside H.264, and stereo AAC.
  private static ClientCapabilityDeclaration pqDeclaration() {
    return declarationBuilderFor(H264)
        .video(
            List.of(
                h264EntryBuilder().build(),
                hevcEntryBuilder()
                    .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                    .build()))
        .build();
  }

  // A 1920x1080 HDR10 HEVC Main 10 source that probe version 5 described, with its mastering
  // display and content light level.
  private static MediaProbe hdr10HevcProbe() {
    return hdr10HevcProbe(Presence.ABSENT);
  }

  private static MediaProbe hdr10HevcProbe(Presence hdr10Plus) {
    return hevcMain10Probe(
        new VideoDynamicRange(CompatibleBase.PQ, hdr10Plus, new DolbyVision.Absent()));
  }

  private static MediaProbe hevcMain10Probe(VideoDynamicRange range) {
    return defaultProbeBuilder()
        .videoCodec("hevc")
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .codecFamily(Optional.of(ProbedCodecFamily.HEVC))
                    .codecString(Optional.of("hvc1.2.4.L150.90"))
                    .bitDepth(OptionalInt.of(10))
                    .dynamicRange(range)
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                    .videoProperties(
                        Optional.of(
                            VideoStreamProperties.builder()
                                .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
                                .codecLevel(OptionalInt.of(50))
                                .codecTier(Optional.of(VideoCodecTier.MAIN))
                                .firstFramesRead(true)
                                .build()))
                    .masteringDisplay(Optional.of(VideoPlaybackFactsFixture.MASTERING_DISPLAY))
                    .contentLightLevel(Optional.of(VideoPlaybackFactsFixture.CONTENT_LIGHT_LEVEL))
                    .build()))
        .build();
  }

  // A 3840x1920 HEVC source at 49 Mb/s whose keyframes open every segment interval, beside a
  // copyable stereo AAC track.
  // An HEVC entry that lists only HEVC Main 10. No encode forces Main 10, so the entry takes an
  // HEVC
  // copy whose probe names no codec profile but no encode.
  private static ClientCapabilityDeclaration.ClientCapabilityDeclarationBuilder
      hevcMain10OnlyDeclarationBuilder() {
    return defaultDeclarationBuilder()
        .video(
            List.of(
                hevcEntryBuilder()
                    .codecProfiles(
                        List.of(
                            VideoCodecProfileCapability.builder()
                                .profile(VideoCodecProfile.HEVC_MAIN_10)
                                .maxLevel(51)
                                .maxTier(Optional.of(VideoCodecTier.MAIN))
                                .build()))
                    .build()));
  }

  // An HEVC entry that applies only to direct play, so no HLS copy or encode fits it.
  private static ClientCapabilityDeclaration directPlayOnlyHevcDeclaration() {
    return defaultDeclarationBuilder()
        .video(List.of(hevcEntryBuilder().hls(false).build()))
        .build();
  }

  private static MediaProbe.MediaProbeBuilder wideUhdHevcProbe() {
    return defaultProbeBuilder()
        .videoCodec("hevc")
        .width(3840)
        .height(1920)
        .bitrate(49_000_000L)
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .codecFamily(Optional.of(ProbedCodecFamily.HEVC))
                    .codecString(Optional.of("hvc1.1.6.L150.90"))
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                    .build()));
  }

  private Outcome<StreamSession, CreateStreamSessionRejection> createSessionDeclaring(
      ClientCapabilityDeclaration declaration, StreamingOptions options) {
    return service.createSession(
        createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
            .options(options)
            .capabilityDeclaration(declaration)
            .build());
  }

  @Test
  @DisplayName(
      "Should encode a portrait source at its full size when the capability entry's maximum fits"
          + " it turned on its side")
  void
      shouldEncodeAPortraitSourceAtItsFullSizeWhenTheCapabilityEntrysMaximumFitsItTurnedOnItsSide() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(1080).height(1920).build());

    accepted(
        service.createSession(
            createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                .options(StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build())
                .capabilityDeclaration(landscapeHdDeclaration())
                .build()));

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(1080);
              assertThat(request.height()).isEqualTo(1920);
            });
  }

  private PlaybackFormatAttempt startPlayback(
      VideoQuality quality, ClientCapabilityDeclaration declaration) {
    var result =
        logicalPlayback()
            .start(
                logicalStart()
                    .options(StreamingOptions.builder().quality(quality).build())
                    .capabilityDeclaration(declaration)
                    .build());
    return accepted(result).currentFormatAttempt();
  }

  // An 8-bit SDR H.264 High profile source at 3840×2160 and 60 fps that probe version 5 described.
  private static MediaProbe.MediaProbeBuilder probeVersion5UhdAt60() {
    return defaultProbeBuilder()
        .width(3840)
        .height(2160)
        .framerate(OptionalDouble.of(60))
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60, 1)))
                    .videoProperties(
                        Optional.of(
                            VideoPlaybackFactsFixture.h264HighPropertiesBuilder()
                                .codecLevel(OptionalInt.of(52))
                                .build()))
                    .build()));
  }

  @Test
  @DisplayName(
      "Should cap Auto's variants at the largest size the only entry accepts when the source is"
          + " larger")
  void shouldCapAutosVariantsAtTheLargestSizeTheOnlyEntryAcceptsWhenTheSourceIsLarger() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());

    var session = createSession(seedMediaFile().getId(), hdAt30Declaration());

    assertThat(session.getVariants())
        .extracting(
            QualityVariant::label,
            QualityVariant::width,
            QualityVariant::height,
            QualityVariant::videoBitrate)
        .containsExactlyInAnyOrder(
            tuple("720p", 1280, 720, 3_000_000L),
            tuple("480p", 854, 480, 1_500_000L),
            tuple("360p", 640, 360, 800_000L));
  }

  @Test
  @DisplayName(
      "Should encode a fixed quality at the largest size the only entry accepts when the source is"
          + " larger")
  void shouldEncodeAFixedQualityAtTheLargestSizeTheOnlyEntryAcceptsWhenTheSourceIsLarger() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    var command =
        createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
            .options(StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build())
            .capabilityDeclaration(hdAt30Declaration())
            .build();

    var session = accepted(service.createSession(command));
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(1280);
              assertThat(request.height()).isEqualTo(720);
            });
    assertThat(playlist).contains(",RESOLUTION=1280x720,");
  }

  @Test
  @DisplayName(
      "Should encode a 60 fps source at 30 fps in the job and the playlist when the only entry"
          + " allows 30 fps")
  void shouldEncodeA60FpsSourceAt30FpsInTheJobAndThePlaylistWhenTheOnlyEntryAllows30Fps() {
    probeResults.setDefaultProbe(probeAt(new Fraction(60, 1)).videoCodec("hevc").build());

    var session = createSession(seedMediaFile().getId(), hdAt30Declaration());
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getVariants())
        .extracting(QualityVariant::frameRate)
        .containsOnly(new Fraction(30, 1));
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.outputFrameRate()).contains(new Fraction(30, 1));
              assertThat(request.framerate()).hasValue(30.0);
              assertThat(request.videoEncodeDecision().orElseThrow().level())
                  .isLessThanOrEqualTo(31);
            });
    assertThat(playlist.lines().filter(line -> line.startsWith("#EXT-X-STREAM-INF:")))
        .isNotEmpty()
        .allSatisfy(line -> assertThat(line).contains(",FRAME-RATE=30.000"));
  }

  @Test
  @DisplayName(
      "Should reject the stream session when only dividing the frame rate below 10 fps fits the"
          + " entry")
  void shouldRejectTheStreamSessionWhenOnlyDividingTheFrameRateBelow10FpsFitsTheEntry() {
    probeResults.setDefaultProbe(probeAt(new Fraction(60, 1)).videoCodec("hevc").build());
    var declaration =
        defaultDeclarationBuilder().video(List.of(h264EntryBuilder().maxFrameRate(0.1).build()));

    var result = service.createSession(createCommandDeclaring(declaration.build()));

    assertThat(result)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should encode each Auto variant in the entry that keeps its frame rate when two entries"
          + " trade size for frame rate")
  void
      shouldEncodeEachAutoVariantInTheEntryThatKeepsItsFrameRateWhenTwoEntriesTradeSizeForFrameRate() {
    probeResults.setDefaultProbe(probeAt(new Fraction(60, 1)).videoCodec("hevc").build());
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().maxWidth(1920).maxHeight(1080).maxFrameRate(30).build(),
                    h264EntryBuilder().maxWidth(1280).maxHeight(720).maxFrameRate(60).build()))
            .build();

    var session = createSession(seedMediaFile().getId(), declaration);

    assertThat(session.getVariants())
        .extracting(QualityVariant::label, QualityVariant::frameRate)
        .containsExactlyInAnyOrder(
            tuple("720p", new Fraction(60, 1)),
            tuple("480p", new Fraction(60, 1)),
            tuple("360p", new Fraction(60, 1)));
  }

  @Test
  @DisplayName(
      "Should encode a fixed quality at the smaller picture that keeps the frame rate when two"
          + " entries trade size for frame rate")
  void
      shouldEncodeAFixedQualityAtTheSmallerPictureThatKeepsTheFrameRateWhenTwoEntriesTradeSizeForFrameRate() {
    probeResults.setDefaultProbe(
        probeAt(new Fraction(60, 1)).videoCodec("hevc").width(3840).height(2160).build());
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().maxWidth(3840).maxHeight(2160).maxFrameRate(30).build(),
                    h264EntryBuilder().maxWidth(1920).maxHeight(1080).maxFrameRate(60).build()))
            .build();
    var command =
        createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
            .options(StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build())
            .capabilityDeclaration(declaration)
            .build();

    var session = accepted(service.createSession(command));
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(1920);
              assertThat(request.height()).isEqualTo(1080);
              assertThat(request.outputFrameRate()).contains(new Fraction(60, 1));
            });
    assertThat(playlist).contains(",RESOLUTION=1920x1080,").contains(",FRAME-RATE=60.000");
  }

  @Test
  @DisplayName(
      "Should keep the source's frame rate when the entry that allows it accepts the largest"
          + " picture after the encoder rounds its width")
  void
      shouldKeepTheSourcesFrameRateWhenTheEntryThatAllowsItAcceptsTheLargestPictureAfterRounding() {
    probeResults.setDefaultProbe(
        probeAt(new Fraction(30, 1)).videoCodec("hevc").width(1080).height(1920).build());
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().maxWidth(1000).maxHeight(1920).maxFrameRate(30).build(),
                    h264EntryBuilder().maxWidth(1002).maxHeight(1778).maxFrameRate(15).build()))
            .build();

    var attempt = startPlayback(VideoQuality.FULL_HD_1080P, declaration);

    assertThat(attempt)
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getVariants())
                    .extracting(
                        QualityVariant::width, QualityVariant::height, QualityVariant::frameRate)
                    .containsExactly(tuple(1000, 1778, new Fraction(30, 1))));
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(request -> assertThat(request.outputFrameRate()).contains(new Fraction(30, 1)));
  }

  @Test
  @DisplayName(
      "Should encode H.264 at the source's frame rate when the client's AV1 entry allows only half"
          + " of it")
  void shouldEncodeH264AtTheSourcesFrameRateWhenTheClientsAv1EntryAllowsOnlyHalfOfIt() {
    probeResults.setDefaultProbe(probeAt(new Fraction(60, 1)).videoCodec("hevc").build());
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    av1EntryBuilder().maxFrameRate(30).build(),
                    h264EntryBuilder().maxWidth(1920).maxHeight(1080).maxFrameRate(60).build()))
            .build();

    var session = createSession(seedMediaFile().getId(), declaration);

    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .allSatisfy(request -> assertThat(request.outputFrameRate()).contains(new Fraction(60, 1)));
  }

  // A player that plays H.264 High up to level 3.1 at 1280×720 and 30 fps.
  private static ClientCapabilityDeclaration hdAt30Declaration() {
    return defaultDeclarationBuilder()
        .video(
            List.of(
                h264EntryBuilder()
                    .maxWidth(1280)
                    .maxHeight(720)
                    .maxFrameRate(30)
                    .codecProfiles(
                        List.of(
                            VideoCodecProfileCapability.builder()
                                .profile(VideoCodecProfile.H264_HIGH)
                                .maxLevel(31)
                                .build()))
                    .build()))
        .build();
  }

  // The default 1920×1080 source whose frame-rate rule chose this output frame rate.
  private static MediaProbe.MediaProbeBuilder probeAt(Fraction rate) {
    return defaultProbeBuilder()
        .framerate(OptionalDouble.of(rate.toDouble()))
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(rate))
                    .build()));
  }

  // A player that plays H.264 at 3840×2160 up to 30 fps and at 1920×1080 up to 60 fps.
  private static ClientCapabilityDeclaration uhdAt30AndHdAt60Declaration() {
    return defaultDeclarationBuilder()
        .video(
            List.of(
                h264EntryBuilder().maxWidth(3840).maxHeight(2160).maxFrameRate(30).build(),
                h264EntryBuilder().maxWidth(1920).maxHeight(1080).maxFrameRate(60).build()))
        .build();
  }

  // ADR 0052: Original encodes a source that no capability entry copies, on Auto's ladder, and the
  // playback session plays it in Auto.
  @Test
  @DisplayName(
      "Should start Original with an encode on Auto's ladder when the source cannot be copied")
  void shouldStartOriginalWithAnEncodeOnAutosLadderWhenTheSourceCannotBeCopied() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());

    var snapshot =
        accepted(
            logicalPlayback()
                .start(
                    logicalStart()
                        .options(StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build())
                        .build()));

    assertThatOriginalStartedWithAnEncodeInAuto(snapshot);
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> assertThat(ready.session().getVariants()).hasSizeGreaterThan(1));
  }

  private void assertThatOriginalStartedWithAnEncodeInAuto(PlaybackSessionSnapshot snapshot) {
    assertThat(snapshot.requestedChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(snapshot.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(snapshot.formatChange()).contains(PlaybackFormatChange.STARTED_WITH_ENCODE);
    assertThat(snapshot.formatAttemptCount()).isEqualTo(1);
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                  .isFalse();
              assertThat(service.getAllSessions()).containsExactly(ready.session());
            });
  }

  // ADR 0052: a fixed quality's failed copy falls back to the fixed quality's own encode, which is
  // one variant (ADR 0050).
  @Test
  @DisplayName("Should encode one variant at the fixed quality when the fixed quality's copy fails")
  void shouldEncodeOneVariantAtTheFixedQualityWhenTheFixedQualitysCopyFails() {
    var playback = logicalPlayback();
    var command =
        logicalStart()
            .options(StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build())
            .build();
    var initial = accepted(playback.start(command));
    assertThat(initial.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                    .isTrue());

    var recovered =
        playback
            .reportFormatFailure(
                ReportPlaybackFormatFailureCommand.builder()
                    .playbackSessionId(initial.playbackSessionId())
                    .streamSessionId(initial.currentFormatAttempt().streamSessionId())
                    .identity(command.identity())
                    .reason(PlaybackFormatFailure.DECODE_ERROR)
                    .build())
            .orElseThrow();

    assertThat(recovered.playbackChoice()).isEqualTo(VideoQuality.FULL_HD_1080P);
    assertThat(recovered.formatChange()).contains(PlaybackFormatChange.COPY_FAILED);
    assertThat(recovered.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                  .isFalse();
              assertThat(ready.session().getVariants())
                  .extracting(QualityVariant::width, QualityVariant::height)
                  .containsExactly(tuple(1920, 1080));
              assertThat(service.getAllSessions()).containsExactly(ready.session());
            });
  }

  @Test
  @DisplayName("Should exclude a failed encoded family when the client reports a format failure")
  void shouldExcludeAFailedEncodedFamilyWhenTheClientReportsAFormatFailure() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("vp9").build());
    var playback = logicalPlayback();
    var command = logicalStart().capabilityDeclaration(av1AndH264Declaration()).build();
    var initial = accepted(playback.start(command));
    assertThat(initial.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().videoCodecFamily())
                    .isEqualTo("h264"));

    var recovered =
        playback
            .reportFormatFailure(
                ReportPlaybackFormatFailureCommand.builder()
                    .playbackSessionId(initial.playbackSessionId())
                    .streamSessionId(initial.currentFormatAttempt().streamSessionId())
                    .identity(command.identity())
                    .reason(PlaybackFormatFailure.DECODE_ERROR)
                    .build())
            .orElseThrow();

    assertThat(recovered.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().videoCodecFamily())
                    .isEqualTo("av1"));
  }

  // ADR 0045: a player that cannot select an audio track direct-plays the file's first track, and
  // the viewer's switch to another track moves it to HLS, which plays the chosen track.
  @Test
  @DisplayName(
      "Should play the chosen audio track over HLS when the viewer switches tracks during direct"
          + " play")
  void shouldPlayTheChosenAudioTrackOverHlsWhenTheViewerSwitchesTracksDuringDirectPlay() {
    probeResults.setDefaultProbe(directPlayableProbeWithAudioIn("eng", "fra"));
    var playback = logicalPlayback();
    var command =
        logicalStart()
            .options(StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build())
            .capabilityDeclaration(
                directPlayDeclarationBuilder().selectsAudioTrackInDirectPlay(false).build())
            .build();
    var directPlay = accepted(playback.start(command));
    assertThat(directPlay.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getDelivery()).isEqualTo(StreamDelivery.DIRECT_PLAY));

    var switched =
        accepted(
            playback.switchAudioTrack(
                SwitchPlaybackAudioTrackCommand.builder()
                    .playbackSessionId(directPlay.playbackSessionId())
                    .streamSessionId(directPlay.currentFormatAttempt().streamSessionId())
                    .identity(command.identity())
                    .audioTrackPosition(1)
                    .build()));

    assertThat(switched.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(switched.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getDelivery()).isEqualTo(StreamDelivery.HLS);
              assertThat(ready.session().getMediaProbe().sessionAudioTrackPosition()).hasValue(1);
              assertThat(service.getAllSessions()).containsExactly(ready.session());
            });
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(request -> request.transcodeDecision().audioStreamIndex())
        .isEqualTo(OptionalInt.of(2));
  }

  // ADR 0045: direct play needs no connected worker, so a switch that HLS cannot serve leaves the
  // viewer on the track that direct play plays.
  @Test
  @DisplayName("Should keep direct play when the viewer switches tracks and no worker is connected")
  void shouldKeepDirectPlayWhenTheViewerSwitchesTracksAndNoWorkerIsConnected() {
    probeResults.setDefaultProbe(directPlayableProbeWithAudioIn("eng", "fra"));
    transcodeExecutor.setHealthy(false);
    var playback = logicalPlayback();
    var command =
        logicalStart()
            .options(StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build())
            .capabilityDeclaration(
                directPlayDeclarationBuilder().selectsAudioTrackInDirectPlay(false).build())
            .build();
    var directPlay = accepted(playback.start(command));

    var outcome =
        playback.switchAudioTrack(
            SwitchPlaybackAudioTrackCommand.builder()
                .playbackSessionId(directPlay.playbackSessionId())
                .streamSessionId(directPlay.currentFormatAttempt().streamSessionId())
                .identity(command.identity())
                .audioTrackPosition(1)
                .build());

    assertThat(outcome)
        .isInstanceOfSatisfying(
            Outcome.Rejected.class,
            rejected ->
                assertThat(rejected.rejections())
                    .singleElement()
                    .isInstanceOf(AudioTrackSwitchRejection.StreamSessionRefused.class));
    assertThat(
            playback.access(
                PlaybackSessionRequest.builder()
                    .playbackSessionId(directPlay.playbackSessionId())
                    .identity(command.identity())
                    .build()))
        .contains(directPlay);
    assertThat(service.getAllSessions())
        .singleElement()
        .extracting(StreamSession::getDelivery)
        .isEqualTo(StreamDelivery.DIRECT_PLAY);
  }

  private PlaybackSessionService logicalPlayback() {
    return PlaybackSessionService.builder()
        .eventPublisher(_ -> {})
        .streamingService(service)
        .authorityGate(authorityGate)
        .executor(Runnable::run)
        .maximumFormatAttempts(2)
        .build();
  }

  private StartPlaybackSessionCommand.StartPlaybackSessionCommandBuilder logicalStart() {
    return StartPlaybackSessionCommand.builder()
        .mediaFileId(seedMediaFile().getId())
        .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
        .options(defaultOptions())
        .capabilityDeclaration(defaultDeclarationBuilder().build());
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder().quality(VideoQuality.AUTO).build();
  }

  private static ClientCapabilityDeclaration av1AndH264Declaration() {
    return declarationBuilderFor(AV1, H264).build();
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

  private StreamSession createSession(UUID mediaFileId, ClientCapabilityDeclaration declaration) {
    return accepted(
        service.createSession(
            createStreamSessionCommandBuilder(mediaFileId, UUID.randomUUID())
                .capabilityDeclaration(declaration)
                .build()));
  }

  private CreateStreamSessionCommand createCommandDeclaring(
      ClientCapabilityDeclaration declaration) {
    return createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
        .capabilityDeclaration(declaration)
        .build();
  }

  // A 29.97 fps H.264 outcome that meets the copy rule apart from its field order.
  private static ProbeOutcome.Success outcomeWithFieldOrder(FieldOrder fieldOrder) {
    return outcomeWithFrameRateRule(
        VideoPlaybackFactsFixture.interlacedRateRule(new Fraction(30_000, 1_001), fieldOrder));
  }

  private static ProbeOutcome.Success outcomeWithFrameRateRule(FrameRateRule rule) {
    return ProbeFixture.completeProbeBuilder(
            defaultProbeBuilder().framerate(OptionalDouble.of(29.97)).build())
        .chosenVideoStream(Optional.of(new ChosenVideoStream(0, rule)))
        .build();
  }

  private CreateStreamSessionCommand interlacedSourceCommand(VideoQuality quality) {
    return createStreamSessionCommand(
        seedMediaFile().getId(),
        UUID.randomUUID(),
        StreamingOptions.builder().quality(quality).build());
  }

  private Optional<StreamSession> accessSession(StreamSession session) {
    return service.accessSession(playbackRequest(session));
  }

  private static PlaybackRequest requestFor(StreamSession session, StreamDelivery delivery) {
    return PlaybackRequest.builder()
        .streamSessionId(session.getSessionId())
        .identity(identityFor(session.getAuthority()))
        .delivery(delivery)
        .build();
  }

  private Optional<StreamSession> accessMissingSession(UUID streamSessionId) {
    var authority = defaultPlaybackAuthorityBuilder().build();
    return service.accessSession(
        PlaybackRequest.builder()
            .streamSessionId(streamSessionId)
            .identity(identityFor(authority))
            .delivery(StreamDelivery.HLS)
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
  @DisplayName(
      "Should encode H.264 and start no AV1 job when the client declares AV1 and no worker-verified"
          + " encoder encodes AV1")
  void shouldEncodeH264AndStartNoAv1JobWhenClientDeclaresAv1AndNoWorkerVerifiedEncoderEncodesAv1() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of("h264"));
    var file = seedMediaFile();
    var session = createSession(file.getId(), av1AndH264Declaration());

    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .extracting(request -> request.transcodeDecision().videoCodecFamily())
        .containsOnly("h264");
  }

  @ParameterizedTest(name = "encodable {0}")
  @CsvSource(
      delimiter = '|',
      value = {"h264", "h264,hevc"})
  @DisplayName("Should reject the session when no worker encoder supports the client's only codec")
  void shouldRejectTheSessionWhenNoWorkerEncoderSupportsTheClientsOnlyCodec(
      String encodableFamilies) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());
    transcodeExecutor.setEncodableVideoCodecFamilies(
        Stream.of(encodableFamilies.split(",")).collect(Collectors.toSet()));
    var file = seedMediaFile();
    var command =
        createStreamSessionCommandBuilder(file.getId(), UUID.randomUUID())
            .options(StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build())
            .capabilityDeclaration(declarationBuilderFor(AV1).build())
            .build();

    var outcome = service.createSession(command);

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should encode AV1 with the whole ladder when one hardware AV1 encode slot is free and"
          + " workers that encode only H.264 have more")
  void
      shouldEncodeAv1WithTheWholeLadderWhenOneHardwareAv1EncodeSlotIsFreeAndWorkersThatEncodeOnlyH264HaveMore() {
    transcodeExecutor.setAvailableSlots(4);
    transcodeExecutor.setEncodeSlots(Map.of("h264", 3, "av1", 1));
    transcodeExecutor.setHardwareEncodableVideoCodecFamilies(Set.of("av1"));
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), av1AndH264Declaration());

    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("av1");
    assertThat(session.getVariants()).hasSize(4);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(request -> request.transcodeDecision().videoCodecFamily())
        .isEqualTo("av1");
  }

  @Test
  @DisplayName(
      "Should encode H.264 when every worker that encodes the declared AV1 has no free slot")
  void shouldEncodeH264WhenEveryWorkerThatEncodesTheDeclaredAv1HasNoFreeSlot() {
    transcodeExecutor.setAvailableSlots(3);
    transcodeExecutor.setEncodeSlots(Map.of("h264", 3, "av1", 0));
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), av1AndH264Declaration());

    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
    assertThat(session.getVariants()).hasSizeGreaterThan(1);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(request -> request.transcodeDecision().videoCodecFamily())
        .isEqualTo("h264");
  }

  // A software AV1 encoder falls behind real time on a large source, while H.264 keeps up.
  @ParameterizedTest(name = "hardware encoders for {0}")
  @ArgumentsSource(HardwareEncodedFamiliesAndExpectedEncode.class)
  @DisplayName(
      "Should encode AV1 for a client that declares AV1 and H.264 only when a worker with a free"
          + " slot encodes AV1 in hardware")
  void
      shouldEncodeAv1ForAClientThatDeclaresAv1AndH264OnlyWhenAWorkerWithAFreeSlotEncodesAv1InHardware(
          Set<String> hardwareEncodedFamilies, String expectedCodecFamily) {
    transcodeExecutor.setHardwareEncodableVideoCodecFamilies(hardwareEncodedFamilies);
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(3840).height(2160).build());

    var session = createSession(seedMediaFile().getId(), av1AndH264Declaration());

    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo(expectedCodecFamily);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(request -> request.transcodeDecision().videoCodecFamily())
        .isEqualTo(expectedCodecFamily);
  }

  // libx265 costs more CPU than libx264, so only a hardware HEVC encoder moves a client that
  // declares HEVC and H.264 off H.264 (ADR 0009). The worker advertises its encoders.
  @ParameterizedTest(name = "{0}")
  @MethodSource("hevcWorkerEncodersAndExpectedEncode")
  @DisplayName(
      "Should encode HEVC for a client that declares HEVC and H.264 only when a worker advertises a"
          + " hardware HEVC encoder")
  void
      shouldEncodeHevcForAClientThatDeclaresHevcAndH264OnlyWhenAWorkerAdvertisesAHardwareHevcEncoder(
          String scenario, List<WorkerVerifiedEncoder> encoders, String expectedCodecString) {
    transcodeExecutor.setTargetEncoders(ExecutionTargetId.LOCAL, encoders);
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("mpeg2video").build());

    createSession(seedMediaFile().getId(), declarationBuilderFor(HEVC, H264).build());

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(
            TranscodeRequest::variantLabel,
            request -> request.videoEncodeDecision().orElseThrow().codecString())
        .containsExactly("480p", expectedCodecString);
  }

  // The first variant, 854x480 at 23.976 fps, needs H.265 level 3 and H.264 level 3.
  static Stream<Arguments> hevcWorkerEncodersAndExpectedEncode() {
    return Stream.of(
        Arguments.of("hevc_nvenc and libx264", List.of(hevcNvenc(), libx264()), "hvc1.1.6.L90.90"),
        Arguments.of("libx265 and libx264", List.of(libx265(), libx264()), "avc1.64001E"));
  }

  @Test
  @DisplayName(
      "Should encode HEVC with libx265 when the client declares only HEVC and cannot play the"
          + " source")
  void shouldEncodeHevcWithLibx265WhenTheClientDeclaresOnlyHevcAndCannotPlayTheSource() {
    transcodeExecutor.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(libx264(), libx265()));
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("h264").build());

    var session = createSession(seedMediaFile().getId(), declarationBuilderFor(HEVC).build());

    assertThat(session.getTranscodeDecision().encodeCodecProfile())
        .contains(EncodeCodecProfile.HEVC_MAIN);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(
            TranscodeRequest::variantLabel,
            request -> request.videoEncodeDecision().orElseThrow().codecString())
        .containsExactly("480p", "hvc1.1.6.L90.90");
  }

  @Test
  @DisplayName(
      "Should reject the session for lack of capacity when only workers that cannot encode H.264"
          + " have free slots")
  void shouldRejectTheSessionForLackOfCapacityWhenOnlyWorkersThatCannotEncodeH264HaveFreeSlots() {
    transcodeExecutor.setAvailableSlots(2);
    transcodeExecutor.setEncodeSlots(Map.of("h264", 0, "av1", 2));
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());
    var file = seedMediaFile();
    var options = StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build();

    var outcome =
        service.createSession(createStreamSessionCommand(file.getId(), UUID.randomUUID(), options));

    assertThat(outcome)
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
    assertThat(capacityRefusals("no_encoder_with_free_slot")).isOne();
  }

  @Test
  @DisplayName(
      "Should reject the session as having no eligible format when a busy encoder's family fits no"
          + " entry")
  void shouldRejectTheSessionAsHavingNoEligibleFormatWhenABusyEncodersFamilyFitsNoEntry() {
    transcodeExecutor.setAvailableSlots(2);
    transcodeExecutor.setEncodeSlots(Map.of("h264", 0));
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().compatibleBases(List.of(CompatibleBase.PQ)).build()))
            .build();

    var outcome = service.createSession(createCommandDeclaring(declaration));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(capacityRefusals("no_encoder_with_free_slot")).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  @Test
  @DisplayName("Should downmix stereo audio to mono when the client supports only one channel")
  void shouldDownmixStereoAudioToMonoWhenTheClientSupportsOnlyOneChannel() {
    probeResults.setDefaultProbe(defaultProbeBuilder().audioChannels(OptionalInt.of(2)).build());
    var file = seedMediaFile();
    var declaration =
        declarationBuilderFor(AV1).audio(List.of(aacEntryBuilder().maxChannels(1).build())).build();

    var session = createSession(file.getId(), declaration);

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.FULL_TRANSCODE);
    var audio = session.getTranscodeDecision().audioDecision();
    assertThat(audio.mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(audio.codec()).isEqualTo("aac");
    assertThat(audio.channels()).isEqualTo(1);
    assertThat(audio.bitrate()).isEqualTo(96_000L);
  }

  @Test
  @DisplayName("Should encode E-AC-3 as 5.1 when the source and the client have more channels")
  void shouldEncodeEac3As51WhenTheSourceAndTheClientHaveMoreChannels() {
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbe(
            defaultProbeBuilder().audioCodec("dts").audioChannels(OptionalInt.of(8)).build()));
    var file = seedMediaFile();
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(8).build()))
            .build();

    var session = createSession(file.getId(), declaration);
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "token");

    var decision = session.getTranscodeDecision().audioDecision();
    assertThat(decision.codec()).isEqualTo("eac3");
    assertThat(decision.channels()).isEqualTo(6);
    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .allSatisfy(
            request -> assertThat(request.transcodeDecision().audioDecision()).isEqualTo(decision));
    assertThat(playlist).contains("CHANNELS=\"6\"");
  }

  @ParameterizedTest(name = "{0} source channels")
  @ValueSource(ints = {3, 4})
  @DisplayName(
      "Should encode the audio to stereo when the source has more channels than the client plays")
  void shouldEncodeTheAudioToStereoWhenTheSourceHasMoreChannelsThanTheClientPlays(
      int sourceChannels) {
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbe(
            defaultProbeBuilder().audioChannels(OptionalInt.of(sourceChannels)).build()));
    var file = seedMediaFile();
    var session = createSession(file.getId(), defaultDeclarationBuilder().build());
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "token");

    var decision = session.getTranscodeDecision().audioDecision();
    assertThat(decision.mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.channels()).isEqualTo(2);
    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .allSatisfy(
            request -> assertThat(request.transcodeDecision().audioDecision()).isEqualTo(decision));
    assertThat(playlist).contains("CHANNELS=\"2\"");
  }

  @Test
  @DisplayName(
      "Should announce stereo for a copied audio track when the probe reports zero channels")
  void shouldAnnounceStereoForACopiedAudioTrackWhenTheProbeReportsZeroChannels() {
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbe(defaultProbeBuilder().audioChannels(OptionalInt.of(0)).build()));
    var file = seedMediaFile();
    var session = createSession(file.getId(), defaultDeclarationBuilder().build());
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "token");

    var decision = session.getTranscodeDecision().audioDecision();
    assertThat(decision.mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.channels()).isEqualTo(2);
    assertThat(playlist).contains("CHANNELS=\"2\"").doesNotContain("CHANNELS=\"0\"");
  }

  @ParameterizedTest(name = "{0}, encodeVideo={1}")
  @MethodSource("copiedAudioCodecs")
  @DisplayName(
      "Should advertise the copied audio codec in the playlist when the client supports the source"
          + " audio codec")
  void shouldAdvertiseCopiedAudioCodecInPlaylistWhenClientSupportsSourceAudioCodec(
      CopiedAudio copied, boolean encodeVideo) {
    var probe =
        ProbeFixture.completeProbe(
            defaultProbeBuilder()
                .videoCodec(encodeVideo ? "hevc" : "h264")
                .audioCodec(copied.probedCodec())
                .build());
    var audio =
        StreamInfo.builder()
            .index(1)
            .codecType("audio")
            .codec(Optional.of(copied.probedCodec()))
            .codecString(Optional.of(copied.codecString()))
            .channels(OptionalInt.of(2))
            .build();
    probeResults.setDefaultOutcome(
        ProbeOutcome.Success.builder()
            .container(probe.container())
            .streams(
                List.of(
                    probe.streams().getFirst(),
                    audio,
                    ProbeFixture.audioStreamBuilder("aac").index(2).build()))
            .keyframes(probe.keyframes())
            .chosenVideoStream(probe.chosenVideoStream())
            .streamTitlesOmitted(probe.streamTitlesOmitted())
            .build());
    var file = seedMediaFile();
    var declaration =
        defaultDeclarationBuilder()
            .audio(List.of(aacEntryBuilder().build(), audioEntryBuilder(copied.declared()).build()))
            .build();

    var session = createSession(file.getId(), declaration);
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "token");

    assertThat(session.getTranscodeDecision().audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(encodeVideo ? TranscodeMode.VIDEO_TRANSCODE : TranscodeMode.REMUX);
    assertThat(playlist.lines().filter(line -> line.startsWith("#EXT-X-STREAM-INF:")))
        .isNotEmpty()
        .allSatisfy(line -> assertThat(line).contains("," + copied.codecString() + "\""));
  }

  // The codec name that the probe stores and the codec string that the copy's playlist carries.
  static Stream<Arguments> copiedAudioCodecs() {
    var codecs =
        List.of(
            copiedAudio(AudioCodec.AAC).probedCodec("aac").codecString("mp4a.40.2").build(),
            copiedAudio(AudioCodec.AAC).probedCodec("aac").codecString("mp4a.40.5").build(),
            copiedAudio(AudioCodec.AAC).probedCodec("aac").codecString("mp4a.40.29").build(),
            copiedAudio(AudioCodec.AC3).probedCodec("ac3").codecString("ac-3").build(),
            copiedAudio(AudioCodec.EAC3).probedCodec("eac3").codecString("ec-3").build(),
            copiedAudio(AudioCodec.FLAC).probedCodec("flac").codecString("fLaC").build(),
            copiedAudio(AudioCodec.OPUS).probedCodec("opus").codecString("Opus").build(),
            copiedAudio(AudioCodec.ALAC).probedCodec("alac").codecString("alac").build());
    return Stream.of(false, true)
        .flatMap(encodeVideo -> codecs.stream().map(codec -> Arguments.of(codec, encodeVideo)));
  }

  private static CopiedAudio.CopiedAudioBuilder copiedAudio(AudioCodec declared) {
    return CopiedAudio.builder().declared(declared);
  }

  @Builder
  record CopiedAudio(AudioCodec declared, String probedCodec, String codecString) {}

  @ParameterizedTest(name = "{0}, encodeVideo={1}")
  @CsvSource({
    "mp4a.40.1, false",
    "mp4a.40.3, false",
    "mp4a.40.4, false",
    "mp4a.40.99, false",
    "mp4a.40.1, true",
    "mp4a.40.3, true",
    "mp4a.40.4, true",
    "mp4a.40.99, true"
  })
  @DisplayName("Should encode audio when its AAC object type is outside the plain AAC declaration")
  void shouldEncodeAudioWhenItsAacObjectTypeIsOutsideThePlainAacDeclaration(
      String sourceCodecString, boolean encodeVideo) {
    var probe =
        ProbeFixture.completeProbe(
            defaultProbeBuilder().videoCodec(encodeVideo ? "hevc" : "h264").build());
    var audio =
        ProbeFixture.audioStreamBuilder("aac")
            .index(1)
            .codecString(Optional.of(sourceCodecString))
            .build();
    probeResults.setDefaultOutcome(
        ProbeOutcome.Success.builder()
            .container(probe.container())
            .streams(List.of(probe.streams().getFirst(), audio))
            .keyframes(probe.keyframes())
            .chosenVideoStream(probe.chosenVideoStream())
            .build());
    var file = seedMediaFile();
    var options = StreamingOptions.builder().build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "token");

    var decision = session.getTranscodeDecision().audioDecision();
    assertThat(decision.mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.codec()).isEqualTo("aac");
    assertThat(decision.hlsCodecString()).isEqualTo("mp4a.40.2");
    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(encodeVideo ? TranscodeMode.FULL_TRANSCODE : TranscodeMode.AUDIO_TRANSCODE);
    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .allSatisfy(
            request -> assertThat(request.transcodeDecision().audioDecision()).isEqualTo(decision));
    assertThat(playlist.lines().filter(line -> line.startsWith("#EXT-X-STREAM-INF:")))
        .isNotEmpty()
        .allSatisfy(line -> assertThat(line).contains(",mp4a.40.2\""));
  }

  @Test
  @DisplayName(
      "Should encode the first audio stream when its stored codec string is absent and a later"
          + " stream has one")
  void shouldEncodeTheFirstAudioStreamWhenItsStoredCodecStringIsAbsentAndALaterStreamHasOne() {
    var probe = ProbeFixture.completeProbe(defaultProbeBuilder().build());
    var firstAudio =
        ProbeFixture.audioStreamBuilder("ac3")
            .index(1)
            .channels(OptionalInt.of(6))
            .codecString(Optional.empty())
            .build();
    probeResults.setDefaultOutcome(
        ProbeOutcome.Success.builder()
            .container(probe.container())
            .streams(
                List.of(
                    probe.streams().getFirst(),
                    firstAudio,
                    ProbeFixture.audioStreamBuilder("ac3").index(2).build()))
            .keyframes(probe.keyframes())
            .chosenVideoStream(probe.chosenVideoStream())
            .streamTitlesOmitted(probe.streamTitlesOmitted())
            .build());
    var file = seedMediaFile();
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(6).build()))
            .build();

    var session = createSession(file.getId(), declaration);
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "token");

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(session.getTranscodeDecision().audioDecision().codec()).isEqualTo("eac3");
    assertThat(playlist).contains("CODECS=\"avc1.640028,ec-3\"");
  }

  @ParameterizedTest
  @CsvSource({"h264, 60", "h264, 23.976", "av1, 60", "av1, 29.97"})
  @DisplayName(
      "Should advertise the codec string that the started encode forces when the session encodes a"
          + " ladder")
  void shouldAdvertiseTheCodecStringThatTheStartedEncodeForcesWhenTheSessionEncodesALadder(
      String videoCodecFamily, double frameRate) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").framerate(OptionalDouble.of(frameRate)).build());
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of(videoCodecFamily));
    var file = seedMediaFile();
    var declaration =
        declarationBuilderFor(ProbedCodecFamily.fromProbedName(videoCodecFamily)).build();

    var session = createSession(file.getId(), declaration);
    var playlist =
        new HlsPlaylistService(StreamingProperties.builder().build())
            .generateMultivariantPlaylist(session, "token")
            .lines()
            .toList();
    var started = transcodeExecutor.getStartedRequests().getFirst();
    var codecString = started.videoEncodeDecision().orElseThrow().codecString();

    assertThat(session.getVariants()).hasSizeGreaterThan(1);
    assertThat(playlist.get(playlist.indexOf(started.variantLabel() + "/stream.m3u8?t=token") - 1))
        .contains("CODECS=\"" + codecString + ",");
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
      "Should advertise the media playlist's segment count to the job attempt when creating"
          + " session")
  void shouldAdvertiseTheMediaPlaylistSegmentCountToTheJobAttemptWhenCreatingSession(
      Duration mediaDuration, int expectedCount) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).duration(mediaDuration).build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    var playlist =
        new HlsPlaylistService(streamingProperties())
            .generateMediaPlaylist(session, StreamSession.defaultVariant(), "token");
    assertThat(playlist.lines().filter(line -> line.startsWith("#EXTINF:"))).hasSize(expectedCount);
    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .extracting(TranscodeRequest::mediaSegmentCount)
        .containsOnly(expectedCount);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("probesWithoutAMediaSegment")
  @DisplayName(
      "Should reject session creation without starting a job attempt when the probe gives no media"
          + " segment")
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
            complete.streams(),
            new KeyframeKnowledge.NotReported());
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
            .delivery(StreamDelivery.HLS)
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
  @DisplayName(
      "Should refuse an HLS request without touching last access when the session direct-plays")
  void shouldRefuseHlsRequestWithoutTouchingLastAccessWhenSessionDirectPlays() {
    var session = directPlaySessionBuilder().build();
    var lastAccessedAt = Instant.now().minus(Duration.ofHours(1));
    session.setLastAccessedAt(lastAccessedAt);
    runtimeRegistry.save(session);

    var result = service.accessSession(requestFor(session, StreamDelivery.HLS));

    assertThat(result).isEmpty();
    assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessedAt);
  }

  @Test
  @DisplayName("Should refuse a direct-play request when the session delivers HLS")
  void shouldRefuseDirectPlayRequestWhenSessionDeliversHls() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    var result = service.accessSession(requestFor(session, StreamDelivery.DIRECT_PLAY));

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should touch last access when a direct-play request reads a direct-play session")
  void shouldTouchLastAccessWhenDirectPlayRequestReadsDirectPlaySession() {
    var session = directPlaySessionBuilder().build();
    var lastAccessedAt = Instant.now().minus(Duration.ofHours(1));
    session.setLastAccessedAt(lastAccessedAt);
    runtimeRegistry.save(session);

    var result = service.accessSession(requestFor(session, StreamDelivery.DIRECT_PLAY));

    assertThat(result).contains(session);
    assertThat(session.getLastAccessedAt()).isAfter(lastAccessedAt);
  }

  @Test
  @DisplayName("Should end a direct-play session when it has no producer")
  void shouldEndDirectPlaySessionWhenItHasNoProducer() {
    var session = directPlaySessionBuilder().build();
    runtimeRegistry.save(session);

    service.destroySession(session.getSessionId());

    assertThat(runtimeRegistry.findById(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should start direct play without a connected worker when Original covers the file")
  void shouldStartDirectPlayWithoutAConnectedWorkerWhenOriginalCoversTheFile() {
    probeResults.setDefaultProbe(directPlayableProbeBuilder().build());
    transcodeExecutor.setHealthy(false);
    var file = seedMediaFile();

    var session = accepted(service.createSession(directPlayCommand(file.getId()).build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.DIRECT_PLAY);
    assertThat(session.getSourceContainer()).contains(SourceContainer.MATROSKA);
    assertThat(session.getSourcePath()).isEqualTo(Path.of("/media/movies/test.mkv"));
    assertThat(session.getVariants()).isEmpty();
    assertThat(runtimeRegistry.findById(session.getSessionId())).contains(session);
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  @Test
  @DisplayName("Should start HLS when the format attempt may not direct-play")
  void shouldStartHlsWhenTheFormatAttemptMayNotDirectPlay() {
    probeResults.setDefaultProbe(directPlayableProbeBuilder().build());
    var file = seedMediaFile();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId())
                    .videoFormatConstraint(VideoFormatConstraint.COPY_OR_ENCODE)
                    .build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.HLS);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(1);
  }

  @Test
  @DisplayName("Should name why Original plays over HLS when the declaration omits the container")
  void shouldNameWhyOriginalPlaysOverHlsWhenTheDeclarationOmitsTheContainer() {
    probeResults.setDefaultProbe(
        directPlayableProbeBuilder().containerFormat(Optional.of("mpegts")).build());
    var file = seedMediaFile();

    var session = accepted(service.createSession(directPlayCommand(file.getId()).build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.HLS);
    assertThat(session.getTranscodeDecision().reasons())
        .first()
        .isEqualTo(DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED);
  }

  @Test
  @DisplayName("Should name no reason when the format attempt may not direct-play")
  void shouldNameNoReasonWhenTheFormatAttemptMayNotDirectPlay() {
    probeResults.setDefaultProbe(
        directPlayableProbeBuilder().containerFormat(Optional.of("avi")).build());
    var file = seedMediaFile();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId())
                    .videoFormatConstraint(VideoFormatConstraint.COPY_OR_ENCODE)
                    .build()));

    assertThat(session.getTranscodeDecision().reasons()).isEmpty();
  }

  @Test
  @DisplayName("Should take no transcode place for an open direct-play session")
  void shouldTakeNoTranscodePlaceForAnOpenDirectPlaySession() {
    probeResults.setDefaultProbe(directPlayableProbeBuilder().build());
    accepted(service.createSession(directPlayCommand(seedMediaFile().getId()).build()));

    for (int i = 0; i < streamingProperties().maxConcurrentTranscodes(); i++) {
      var command =
          createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
              .capabilityDeclaration(directPlayDeclaration())
              .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
              .build();
      assertThat(service.createSession(command)).isInstanceOf(Outcome.Accepted.class);
    }

    assertThat(transcodeExecutor.getStartedRequests()).hasSize(3);
  }

  // ADR 0045: a player that cannot select an audio track in a directly played file plays the
  // file's first audio track, so it direct-plays a file whose chosen audio track is the first.
  @Test
  @DisplayName(
      "Should direct-play several audio tracks when the player cannot select one and the first is"
          + " chosen")
  void shouldDirectPlaySeveralAudioTracksWhenThePlayerCannotSelectOneAndTheFirstIsChosen() {
    probeResults.setDefaultProbe(directPlayableProbeWithAudioIn("eng", "fra"));
    var file = seedMediaFile();
    var declaration = directPlayDeclarationBuilder().selectsAudioTrackInDirectPlay(false).build();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId()).capabilityDeclaration(declaration).build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.DIRECT_PLAY);
    assertThat(session.getDirectPlayAudioTrackPosition()).hasValue(0);
  }

  // ADR 0045: such a player would play the file's first audio track instead of the chosen one, so
  // it plays over HLS from the start.
  @Test
  @DisplayName(
      "Should start HLS with the chosen audio track when the player cannot select one and a later"
          + " track is chosen")
  void shouldStartHlsWithTheChosenAudioTrackWhenThePlayerCannotSelectOneAndALaterTrackIsChosen() {
    probeResults.setDefaultProbe(directPlayableProbeWithAudioIn("eng", "fra"));
    var file = seedMediaFile();
    var declaration = directPlayDeclarationBuilder().selectsAudioTrackInDirectPlay(false).build();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId())
                    .capabilityDeclaration(declaration)
                    .audioTrackPosition(OptionalInt.of(1))
                    .build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.HLS);
    assertThat(session.getMediaProbe().sessionAudioTrackPosition()).hasValue(1);
  }

  // Chromium and Firefox start on a Matroska file's first audio track whatever its default flags
  // say, so the chosen first track direct-plays although a later track has the default flag.
  @Test
  @DisplayName(
      "Should direct-play a Matroska file's first audio track when the player cannot select one and"
          + " a later track has the default flag")
  void
      shouldDirectPlayAMatroskaFilesFirstAudioTrackWhenThePlayerCannotSelectOneAndALaterTrackHasTheDefaultFlag() {
    probeResults.setDefaultProbe(
        directPlayableProbeWhoseSecondAudioTrackIsTheDefault(directPlayableProbeBuilder()));
    var file = seedMediaFile();
    var declaration = directPlayDeclarationBuilder().selectsAudioTrackInDirectPlay(false).build();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId())
                    .options(englishInOriginal())
                    .capabilityDeclaration(declaration)
                    .build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.DIRECT_PLAY);
    assertThat(session.getDirectPlayAudioTrackPosition()).hasValue(0);
  }

  // In an MP4 file, Chromium starts on the first audio track whose enabled flag is set, which the
  // probe reports as the default flag, and Firefox on the first audio track. When only a later
  // track is enabled, they start on different tracks, so the chosen first track plays over HLS.
  @Test
  @DisplayName(
      "Should start HLS when the player cannot select an audio track and an MP4 file enables only a"
          + " later one")
  void shouldStartHlsWhenThePlayerCannotSelectAnAudioTrackAndAnMp4FileEnablesOnlyALaterOne() {
    probeResults.setDefaultProbe(
        directPlayableProbeWhoseSecondAudioTrackIsTheDefault(directPlayableMp4ProbeBuilder()));
    var file = seedMediaFile();
    var declaration =
        directPlayMp4DeclarationBuilder().selectsAudioTrackInDirectPlay(false).build();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId())
                    .options(englishInOriginal())
                    .capabilityDeclaration(declaration)
                    .build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.HLS);
    assertThat(session.getMediaProbe().sessionAudioTrackPosition()).hasValue(0);
    assertThat(session.getTranscodeDecision().reasons())
        .containsExactly(DecisionReason.DIRECT_PLAY_AUDIO_TRACK_NOT_SELECTABLE);
  }

  // Chromium plays no audio from an MP4 file whose only enabled track is the video, so the file
  // plays over HLS.
  @Test
  @DisplayName(
      "Should start HLS when the player cannot select an audio track and an MP4 file enables only"
          + " its video track")
  void shouldStartHlsWhenThePlayerCannotSelectAnAudioTrackAndAnMp4FileEnablesOnlyItsVideoTrack() {
    var file = directPlayableMp4ProbeBuilder();
    var video = ProbeFixture.videoStreamBuilder(file.build()).isDefault(true).build();
    var stereo = ProbeFixture.audioStreamBuilder("aac").channels(OptionalInt.of(2));
    var english = stereo.index(1).language(Optional.of("eng")).build();
    var french = stereo.index(2).language(Optional.of("fra")).build();
    probeResults.setDefaultProbe(file.streams(List.of(video, english, french)).build());
    var declaration =
        directPlayMp4DeclarationBuilder().selectsAudioTrackInDirectPlay(false).build();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(seedMediaFile().getId())
                    .options(englishInOriginal())
                    .capabilityDeclaration(declaration)
                    .build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.HLS);
    assertThat(session.getTranscodeDecision().reasons())
        .containsExactly(DecisionReason.DIRECT_PLAY_AUDIO_TRACK_NOT_SELECTABLE);
  }

  // The HLS format attempt names why it does not direct-play first among its stream decision's
  // reasons, so a playback-data overlay shows that the player cannot select the chosen track.
  @ParameterizedTest(name = "{0}")
  @MethodSource("audioTracksThatANonSelectingPlayerWouldNotStartOn")
  @DisplayName(
      "Should name the unselectable audio track when a player that cannot select one starts HLS")
  void shouldNameTheUnselectableAudioTrackWhenAPlayerThatCannotSelectOneStartsHls(
      String scenario, MediaProbe probe, OptionalInt chosenAudioTrack) {
    probeResults.setDefaultProbe(probe);
    var file = seedMediaFile();
    var declaration = directPlayDeclarationBuilder().selectsAudioTrackInDirectPlay(false).build();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId())
                    .capabilityDeclaration(declaration)
                    .audioTrackPosition(chosenAudioTrack)
                    .build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.HLS);
    assertThat(session.getTranscodeDecision().reasons())
        .first()
        .isEqualTo(DecisionReason.DIRECT_PLAY_AUDIO_TRACK_NOT_SELECTABLE);
  }

  static Stream<Arguments> audioTracksThatANonSelectingPlayerWouldNotStartOn() {
    return Stream.of(
        Arguments.of(
            "a later track is chosen",
            directPlayableProbeWithAudioIn("eng", "fra"),
            OptionalInt.of(1)),
        Arguments.of(
            "no language is preferred and the second track is the default",
            directPlayableProbeWhoseSecondAudioTrackIsTheDefault(directPlayableProbeBuilder()),
            OptionalInt.empty()));
  }

  @ParameterizedTest(name = "selects audio track: {0}")
  @CsvSource({"true, DIRECT_PLAY", "false, HLS"})
  @DisplayName(
      "Should decide direct play on the audio track that the session's language chooses when it is"
          + " a later track")
  void shouldDecideDirectPlayOnTheAudioTrackThatTheSessionsLanguageChoosesWhenItIsALaterTrack(
      boolean selectsAudioTrack, StreamDelivery delivery) {
    probeResults.setDefaultProbe(directPlayableProbeWithAudioIn("eng", "fra"));
    var file = seedMediaFile();
    var declaration =
        directPlayDeclarationBuilder().selectsAudioTrackInDirectPlay(selectsAudioTrack).build();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId())
                    .options(
                        StreamingOptions.builder()
                            .quality(VideoQuality.ORIGINAL)
                            .audioLanguage(Language.FR)
                            .build())
                    .capabilityDeclaration(declaration)
                    .build()));

    assertThat(session.getDelivery()).isEqualTo(delivery);
    assertThat(session.getMediaProbe().sessionAudioTrackPosition()).hasValue(1);
  }

  @Test
  @DisplayName(
      "Should play the audio track the viewer switched to when the session prefers another"
          + " language")
  void shouldPlayTheAudioTrackTheViewerSwitchedToWhenTheSessionPrefersAnotherLanguage() {
    probeResults.setDefaultProbe(directPlayableProbeWithAudioIn("eng", "fra"));
    var file = seedMediaFile();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId())
                    .options(
                        StreamingOptions.builder()
                            .quality(VideoQuality.ORIGINAL)
                            .audioLanguage(Language.EN)
                            .build())
                    .audioTrackPosition(OptionalInt.of(1))
                    .build()));

    assertThat(session.getMediaProbe().sessionAudioTrackPosition()).hasValue(1);
  }

  // ADR 0045: a player that selects audio tracks direct-plays a file with several, and selects the
  // audio track that the stream decision names: the track that HLS plays for the same file.
  @Test
  @DisplayName(
      "Should direct-play and name the first audio track when the player selects an audio track")
  void shouldDirectPlayAndNameTheFirstAudioTrackWhenThePlayerSelectsAnAudioTrack() {
    probeResults.setDefaultProbe(directPlayableProbeWithAudioIn("eng", "fra"));
    var file = seedMediaFile();
    var declaration = directPlayDeclarationBuilder().selectsAudioTrackInDirectPlay(true).build();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId()).capabilityDeclaration(declaration).build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.DIRECT_PLAY);
    assertThat(session.getDirectPlayAudioTrackPosition()).hasValue(0);
  }

  @Test
  @DisplayName("Should play the audio track that the request names when the session plays over HLS")
  void shouldPlayTheAudioTrackThatTheRequestNamesWhenTheSessionPlaysOverHls() {
    probeResults.setDefaultProbe(directPlayableProbeWithAudioIn("eng", "fra", "deu"));
    var file = seedMediaFile();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId())
                    .videoFormatConstraint(VideoFormatConstraint.COPY_OR_ENCODE)
                    .audioTrackPosition(OptionalInt.of(1))
                    .build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.HLS);
    assertThat(session.getMediaProbe().audioStreams())
        .first()
        .extracting(StreamInfo::language)
        .isEqualTo(Optional.of("fra"));
    assertThat(session.getMediaProbe().sessionAudioTrackPosition()).hasValue(1);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(request -> request.transcodeDecision().audioStreamIndex())
        .isEqualTo(OptionalInt.of(2));
  }

  @Test
  @DisplayName(
      "Should direct-play and name the audio track that the request names when the player selects"
          + " an audio track")
  void shouldDirectPlayAndNameTheAudioTrackThatTheRequestNamesWhenThePlayerSelectsAnAudioTrack() {
    probeResults.setDefaultProbe(directPlayableProbeWithAudioIn("eng", "fra", "deu"));
    var file = seedMediaFile();
    var declaration = directPlayDeclarationBuilder().selectsAudioTrackInDirectPlay(true).build();

    var session =
        accepted(
            service.createSession(
                directPlayCommand(file.getId())
                    .capabilityDeclaration(declaration)
                    .audioTrackPosition(OptionalInt.of(2))
                    .build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.DIRECT_PLAY);
    assertThat(session.getDirectPlayAudioTrackPosition()).hasValue(2);
  }

  // One stereo AAC track for each language, in stream order after the video stream.
  private static MediaProbe directPlayableProbeWithAudioIn(String... languages) {
    var streams = new ArrayList<StreamInfo>();
    streams.add(ProbeFixture.videoStreamBuilder(directPlayableProbe()).build());
    for (var language : languages) {
      streams.add(
          ProbeFixture.audioStreamBuilder("aac")
              .index(streams.size())
              .channels(OptionalInt.of(2))
              .language(Optional.of(language))
              .build());
    }

    return directPlayableProbeBuilder().streams(streams).build();
  }

  private static MediaProbe directPlayableProbeWhoseSecondAudioTrackIsTheDefault(
      MediaProbe.MediaProbeBuilder file) {
    var video = ProbeFixture.videoStreamBuilder(file.build()).build();
    var first =
        ProbeFixture.audioStreamBuilder("aac")
            .index(1)
            .channels(OptionalInt.of(2))
            .language(Optional.of("eng"))
            .build();
    var second =
        ProbeFixture.audioStreamBuilder("aac")
            .index(2)
            .channels(OptionalInt.of(2))
            .language(Optional.of("fra"))
            .isDefault(true)
            .build();
    return file.streams(List.of(video, first, second)).build();
  }

  private static StreamingOptions englishInOriginal() {
    return StreamingOptions.builder()
        .quality(VideoQuality.ORIGINAL)
        .audioLanguage(Language.EN)
        .build();
  }

  private static MediaProbe directPlayableProbe() {
    return directPlayableProbeBuilder().build();
  }

  private static MediaProbe.MediaProbeBuilder directPlayableProbeBuilder() {
    var facts =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .streamIndex(0)
            .videoProperties(
                Optional.of(VideoPlaybackFactsFixture.h264HighPropertiesBuilder().build()))
            .build();
    return defaultProbeBuilder()
        .containerFormat(Optional.of("matroska,webm"))
        .audioChannels(OptionalInt.of(2))
        .videoPlaybackFacts(Optional.of(facts));
  }

  // An H.264 MP4 file stored under the avc1 sample entry, as probe version 7 reports it.
  private static MediaProbe.MediaProbeBuilder directPlayableMp4ProbeBuilder() {
    var facts =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .streamIndex(0)
            .sampleEntry(Optional.of("avc1"))
            .videoProperties(
                Optional.of(VideoPlaybackFactsFixture.h264HighPropertiesBuilder().build()))
            .build();
    return defaultProbeBuilder()
        .containerFormat(Optional.of("mov,mp4,m4a,3gp,3g2,mj2"))
        .majorBrand(Optional.of("isom"))
        .audioChannels(OptionalInt.of(2))
        .videoPlaybackFacts(Optional.of(facts));
  }

  private static ClientCapabilityDeclaration directPlayDeclaration() {
    return directPlayDeclarationBuilder().build();
  }

  private static ClientCapabilityDeclaration.ClientCapabilityDeclarationBuilder
      directPlayDeclarationBuilder() {
    return ClientCapabilityDeclaration.builder()
        .video(
            List.of(
                h264EntryBuilder().directPlayContainers(List.of(SourceContainer.MATROSKA)).build()))
        .audio(
            List.of(
                aacEntryBuilder().directPlayContainers(List.of(SourceContainer.MATROSKA)).build()));
  }

  private static ClientCapabilityDeclaration.ClientCapabilityDeclarationBuilder
      directPlayMp4DeclarationBuilder() {
    var mp4 = List.of(SourceContainer.MP4);
    return ClientCapabilityDeclaration.builder()
        .video(
            List.of(
                h264EntryBuilder()
                    .directPlayContainers(mp4)
                    .directPlaySampleEntries(List.of(VideoSampleEntry.AVC1))
                    .build()))
        .audio(List.of(aacEntryBuilder().directPlayContainers(mp4).build()));
  }

  private static CreateStreamSessionCommand.CreateStreamSessionCommandBuilder directPlayCommand(
      UUID mediaFileId) {
    return createStreamSessionCommandBuilder(mediaFileId, UUID.randomUUID())
        .options(StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build())
        .capabilityDeclaration(directPlayDeclaration())
        .videoFormatConstraint(VideoFormatConstraint.DIRECT_PLAY_OR_COPY_OR_ENCODE);
  }

  // A copy's segment opens at its keyframe, so a position before the keyframe that opens segment 1
  // still lies in segment 0.
  @ParameterizedTest
  @CsvSource({"0, 0", "6499, 0", "6500, 1", "13000, 2", "23999, 3"})
  @DisplayName("Should start the copy at the segment that holds the start position")
  void shouldStartTheCopyAtTheSegmentThatHoldsTheStartPosition(
      long startMillis, int expectedSegment) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(24))
            .keyframes(keyframesAtMillis(0, 6_500, 12_000, 18_000))
            .build());
    var command =
        createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
            .startPosition(Optional.of(Duration.ofMillis(startMillis)))
            .build();

    var session = accepted(service.createSession(command));

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getStartPosition()).contains(Duration.ofMillis(startMillis));
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(TranscodeRequest::startSequenceNumber)
        .isEqualTo(expectedSegment);
  }

  @Test
  @DisplayName(
      "Should start only the first-listed variant at the segment that holds the start position when"
          + " the session encodes")
  void
      shouldStartOnlyTheFirstListedVariantAtTheSegmentThatHoldsTheStartPositionWhenTheSessionEncodes() {
    var command =
        createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
            .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
            .startPosition(Optional.of(Duration.ofMillis(5_381_500)))
            .build();

    var session = accepted(service.createSession(command));

    assertThat(session.getVariants()).hasSizeGreaterThan(1);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.variantLabel())
                  .isEqualTo(session.getVariants().getFirst().label());
              assertThat(request.startSequenceNumber()).isEqualTo(896);
              assertThat(request.seekPosition()).isEqualTo(5_376);
            });
    assertThat(
            new HlsPlaylistService(streamingProperties())
                .generateMultivariantPlaylist(session, "token"))
        .contains("#EXT-X-START:TIME-OFFSET=5381.5,PRECISE=YES\n");
  }

  @Test
  @DisplayName("Should start at segment 0 and mark no start when the command has no start position")
  void shouldStartAtSegment0AndMarkNoStartWhenTheCommandHasNoStartPosition() {
    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getStartPosition()).isEmpty();
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(TranscodeRequest::startSequenceNumber)
        .isEqualTo(0);
    assertThat(
            new HlsPlaylistService(streamingProperties())
                .generateMultivariantPlaylist(session, "token"))
        .doesNotContain("#EXT-X-START");
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 7_200_000, 7_200_001})
  @DisplayName("Should refuse a start position before zero or at or past the media's end")
  void shouldRefuseAStartPositionBeforeZeroOrAtOrPastTheMediasEnd(long startMillis) {
    var command =
        createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
            .startPosition(Optional.of(Duration.ofMillis(startMillis)))
            .build();

    var outcome = service.createSession(command);

    assertThat(rejectionOf(outcome))
        .isEqualTo(new CreateStreamSessionRejection.StartPositionOutOfRange());
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
    assertThat(runtimeRegistry.findById(command.streamSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should refuse a start position before zero when the media file does not exist")
  void shouldRefuseAStartPositionBeforeZeroWhenTheMediaFileDoesNotExist() {
    var command =
        createStreamSessionCommandBuilder(UUID.randomUUID(), UUID.randomUUID())
            .startPosition(Optional.of(Duration.ofNanos(-1)))
            .build();

    assertThat(rejectionOf(service.createSession(command)))
        .isEqualTo(new CreateStreamSessionRejection.StartPositionOutOfRange());
  }

  @Test
  @DisplayName("Should carry the start position in a direct-play session")
  void shouldCarryTheStartPositionInADirectPlaySession() {
    probeResults.setDefaultProbe(directPlayableProbeBuilder().build());
    var command =
        directPlayCommand(seedMediaFile().getId())
            .startPosition(Optional.of(Duration.ofMillis(5_381_500)))
            .build();

    var session = accepted(service.createSession(command));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.DIRECT_PLAY);
    assertThat(session.getStartPosition()).contains(Duration.ofMillis(5_381_500));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
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
    transcodeExecutor.failTerminally(session.getSessionId(), TranscodeFailure.OUTPUT_MISMATCH);
    transcodeExecutor.failOnStop(session.getSessionId());
    var sessionId = session.getSessionId();

    assertThatThrownBy(() -> service.destroySession(sessionId))
        .isInstanceOf(TranscodeException.class);

    assertThat(accessSession(session)).isEmpty();
    assertThat(segmentStore.segmentExists(session.getSessionId(), "segment0.m4s")).isFalse();
    assertThat(transcodeExecutor.terminalFailure(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should reject full transcode when at concurrency limit")
  void shouldRejectFullTranscodeWhenAtConcurrencyLimit() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());

    var options = StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build();

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
    assertThat(capacityRefusals("no_free_configured_transcode_slot")).isOne();
    assertThat(capacityRefusals("no_free_worker_slot")).isZero();
  }

  @Test
  @DisplayName("Should not count a session against the transcode limit when suspended")
  void shouldNotCountSessionAgainstTranscodeLimitWhenSuspended() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());

    var options = StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build();

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

    var transcodeOptions = StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build();

    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      createSession(file.getId(), UUID.randomUUID(), transcodeOptions);
    }

    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).build());

    var remuxOptions = StreamingOptions.builder().build();
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), remuxOptions);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName("Should start a copy that encodes the audio when the transcode limit is reached")
  void shouldStartACopyThatEncodesTheAudioWhenTheTranscodeLimitIsReached() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());
    var transcodeOptions = StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build();
    for (int i = 0; i < 3; i++) {
      createSession(seedMediaFile().getId(), UUID.randomUUID(), transcodeOptions);
    }

    var copy =
        createDtsSession(
            defaultProbeBuilder(),
            8,
            StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build());

    assertThat(copy.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(transcodeExecutor.isRunning(copy.getSessionId(), StreamSession.defaultVariant()))
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should start an encode when copies that encode the audio run up to the transcode limit")
  void shouldStartAnEncodeWhenCopiesThatEncodeTheAudioRunUpToTheTranscodeLimit() {
    var copyOptions = StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build();
    for (int i = 0; i < 3; i++) {
      createDtsSession(defaultProbeBuilder(), 8, copyOptions);
    }

    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());
    var encode =
        service.createSession(
            createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                .options(StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build())
                .build());

    assertThat(accepted(encode).getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
  }

  @Test
  @DisplayName("Should reject remux session creation when no worker slot is available")
  void shouldRejectRemuxSessionCreationWhenNoWorkerSlotIsAvailable() {
    var file = seedMediaFile();
    transcodeExecutor.setAvailableSlots(0);
    var command =
        CreateStreamSessionCommand.builder()
            .streamSessionId(UUID.randomUUID())
            .mediaFileId(file.getId())
            .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
            .options(StreamingOptions.builder().build())
            .capabilityDeclaration(defaultDeclarationBuilder().build())
            .build();

    assertThat(service.createSession(command))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getRunningCount()).isZero();
    assertThat(capacityRefusals("no_free_worker_slot")).isOne();
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
    var options = StreamingOptions.builder().build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName(
      "Should advertise the rotated size and its level when a session encodes an upright phone"
          + " recording")
  void shouldAdvertiseTheRotatedSizeAndItsLevelWhenASessionEncodesAnUprightPhoneRecording() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .framerate(OptionalDouble.of(60))
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .streamIndex(0)
                        .codecString(Optional.of("hvc1.1.6.L120.90"))
                        .rotationDegrees(OptionalInt.of(-90))
                        .build()))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var playlist =
        new HlsPlaylistService(streamingProperties())
            .generateMultivariantPlaylist(session, "t")
            .lines()
            .toList();

    // The 1080p tier's box turns with the picture. The 360p tier's 360x640 at 60 fps needs 55,200
    // macroblocks per second: H.264 level 3.1.
    assertThat(playlist.get(playlist.indexOf("1080p/stream.m3u8?t=t") - 1))
        .contains("RESOLUTION=1080x1920,")
        .contains("CODECS=\"avc1.64002A,")
        .contains("FRAME-RATE=60.000");
    assertThat(playlist.get(playlist.indexOf("360p/stream.m3u8?t=t") - 1))
        .contains("RESOLUTION=360x640,")
        .contains("CODECS=\"avc1.64001F,");
  }

  @Test
  @DisplayName(
      "Should offer the 1080p tier at the source's size when a wide film is shorter than 1080"
          + " lines")
  void shouldOfferThe1080pTierAtTheSourcesSizeWhenAWideFilmIsShorterThan1080Lines() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(1920).height(800).build());

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getVariants())
        .extracting(QualityVariant::label, QualityVariant::width, QualityVariant::height)
        .contains(tuple("1080p", 1920, 800));
  }

  @Test
  @DisplayName(
      "Should label a variant by its shorter side when a capability entry shrinks it short of its"
          + " tier's box")
  void shouldLabelAVariantByItsShorterSideWhenACapabilityEntryShrinksItShortOfItsTiersBox() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(1600).maxHeight(900).build()))
            .build();

    var session = accepted(service.createSession(createCommandDeclaring(declaration)));
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getVariants())
        .extracting(QualityVariant::label, QualityVariant::width, QualityVariant::height)
        .containsExactlyInAnyOrder(
            tuple("900p", 1600, 900),
            tuple("720p", 1280, 720),
            tuple("480p", 854, 480),
            tuple("360p", 640, 360));
    assertThat(playlist).contains("900p/stream.m3u8?t=t").doesNotContain("1080p/");
  }

  @Test
  @DisplayName(
      "Should label the only variant by its shorter side when the source is smaller than the 360p"
          + " box")
  void shouldLabelTheOnlyVariantByItsShorterSideWhenTheSourceIsSmallerThanThe360pBox() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(320).height(180).build());

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getVariants())
        .extracting(QualityVariant::label, QualityVariant::width, QualityVariant::height)
        .containsExactly(tuple("180p", 320, 180));
  }

  // A 1.85:1 source in a 2048×1080 entry: the 1440p tier shrinks to 1998×1080, whose shorter side
  // names the 1080p tier's label.
  @Test
  @DisplayName(
      "Should drop a variant labelled by its shorter side when another variant's tier has the same"
          + " label")
  void shouldDropAVariantLabelledByItsShorterSideWhenAnotherVariantsTierHasTheSameLabel() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(3996).height(2160).build());
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(2048).maxHeight(1080).build()))
            .build();

    var session = accepted(service.createSession(createCommandDeclaring(declaration)));

    assertThat(session.getVariants())
        .extracting(QualityVariant::label, QualityVariant::width, QualityVariant::height)
        .containsExactlyInAnyOrder(
            tuple("1080p", 1920, 1038),
            tuple("720p", 1280, 692),
            tuple("480p", 854, 462),
            tuple("360p", 640, 346));
  }

  @Test
  @DisplayName(
      "Should offer the 2160p and 1440p tiers when the source and the capability entry allow 4K")
  void shouldOfferThe2160pAnd1440pTiersWhenTheSourceAndTheCapabilityEntryAllow4K() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(3840).height(2160).build());

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getVariants())
        .extracting(QualityVariant::label, QualityVariant::width, QualityVariant::height)
        .containsExactlyInAnyOrder(
            tuple("2160p", 3840, 2160),
            tuple("1440p", 2560, 1440),
            tuple("1080p", 1920, 1080),
            tuple("720p", 1280, 720),
            tuple("480p", 854, 480),
            tuple("360p", 640, 360));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("codecFamiliesAndTheirLargestTiersBitrates")
  @DisplayName(
      "Should give the 2160p and 1440p tiers a codec family's nominal bitrates when the encode uses"
          + " that family")
  void shouldGiveThe2160pAnd1440pTiersACodecFamilysNominalBitratesWhenTheEncodeUsesThatFamily(
      FamilyBitrates family) {
    transcodeExecutor.setEncodeSlots(Map.of(family.codecFamily(), 3));
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .width(3840)
            .height(2160)
            .bitrate(40_000_000L)
            .build());

    var session = createSession(seedMediaFile().getId(), av1AndH264Declaration());

    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo(family.codecFamily());
    assertThat(session.getVariants())
        .extracting(QualityVariant::label, QualityVariant::videoBitrate)
        .containsExactlyInAnyOrder(
            tuple("2160p", family.bitrate2160p()),
            tuple("1440p", family.bitrate1440p()),
            tuple("1080p", 5_000_000L),
            tuple("720p", 3_000_000L),
            tuple("480p", 1_500_000L),
            tuple("360p", 800_000L));
  }

  // The AV1 rates follow the HEVC ladder of Apple's HLS Authoring Specification, and H.264 needs
  // more for the same picture (ADR 0050). Smaller tiers keep one rate for both families.
  static Stream<FamilyBitrates> codecFamiliesAndTheirLargestTiersBitrates() {
    return Stream.of(
        FamilyBitrates.builder()
            .codecFamily("av1")
            .bitrate2160p(11_600_000L)
            .bitrate1440p(8_100_000L)
            .build(),
        FamilyBitrates.builder()
            .codecFamily("h264")
            .bitrate2160p(20_000_000L)
            .bitrate1440p(12_000_000L)
            .build());
  }

  @Builder
  record FamilyBitrates(String codecFamily, long bitrate2160p, long bitrate1440p) {}

  @Test
  @DisplayName(
      "Should start only the 480p variant when an Auto stream session uses the default ladder")
  void shouldStartOnlyThe480pVariantWhenAnAutoStreamSessionUsesTheDefaultLadder() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getVariants())
        .extracting(QualityVariant::label)
        .containsExactly("480p", "1080p", "720p", "360p");
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.variantLabel()).isEqualTo("480p");
              assertThat(request.startSequenceNumber()).isZero();
            });
    assertThat(session.getVariantHandles()).containsOnlyKeys("480p");
  }

  @Test
  @DisplayName(
      "Should list the variant nearest 2000 kb/s first and the others from highest to lowest when"
          + " an Auto stream session encodes")
  void
      shouldListTheVariantNearest2000KbpsFirstAndTheOthersFromHighestToLowestWhenAnAutoStreamSessionEncodes() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .width(1280)
            .height(720)
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(playlist.lines().filter(line -> line.endsWith("/stream.m3u8?t=t")))
        .containsExactly("480p/stream.m3u8?t=t", "720p/stream.m3u8?t=t", "360p/stream.m3u8?t=t");
  }

  @Test
  @DisplayName("Should use the reserved session identifier when dispatching its first job")
  void shouldUseTheReservedSessionIdentifierWhenDispatchingItsFirstJob() {
    var command =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    var session = accepted(service.createSession(command));

    assertThat(session.getSessionId()).isEqualTo(command.streamSessionId());
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(TranscodeRequest::sessionId)
        .isEqualTo(command.streamSessionId());
  }

  @Test
  @DisplayName("Should not restore the session when it is destroyed during its first dispatch")
  void shouldNotRestoreTheSessionWhenItIsDestroyedDuringItsFirstDispatch() {
    var closingExecutor =
        new FakeTranscodeExecutor() {
          @Override
          public TranscodeHandle start(TranscodeRequest request) {
            var handle = super.start(request);
            service.destroySession(request.sessionId());
            return handle;
          }
        };
    service = serviceWith(closingExecutor, runtimeRegistry);
    var command =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThatThrownBy(() -> service.createSession(command))
        .isInstanceOf(SessionNotFoundException.class);

    assertThat(service.getAllSessions()).isEmpty();
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(closingExecutor.getRunningCount()).isZero();
  }

  @Test
  @DisplayName(
      "Should not start a producer when the session is destroyed before its first dispatch")
  void shouldNotStartAProducerWhenTheSessionIsDestroyedBeforeItsFirstDispatch() {
    var closingRegistry =
        new FakeRuntimeStreamSessionRegistry() {
          @Override
          public void save(StreamSession session) {
            super.save(session);
            service.destroySession(session.getSessionId());
          }
        };
    service = serviceWith(transcodeExecutor, closingRegistry);
    var command =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThatThrownBy(() -> service.createSession(command))
        .isInstanceOf(SessionNotFoundException.class);

    assertThat(service.getAllSessions()).isEmpty();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
    assertThat(transcodeExecutor.getRunningCount()).isZero();
  }

  @Test
  @DisplayName("Should retain the started producer when the registry returns detached sessions")
  void shouldRetainTheStartedProducerWhenTheRegistryReturnsDetachedSessions() {
    var detachedRegistry =
        new FakeRuntimeStreamSessionRegistry() {
          @Override
          public Optional<StreamSession> findById(UUID sessionId) {
            return super.findById(sessionId).map(HlsStreamingServiceTest::detachedSession);
          }
        };
    service = serviceWith(transcodeExecutor, detachedRegistry);
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    transcodeExecutor.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(libx264()));
    var options = StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build();
    var command = createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), options);

    var created = accepted(service.createSession(command));
    var stored = service.accessSession(playbackRequest(created)).orElseThrow();

    assertThat(stored.getHandle()).isPresent();
    assertThat(created.getHandle()).isEqualTo(stored.getHandle());
    assertThat(stored.getCurrentSegment()).isEqualTo(created.getCurrentSegment());
    assertThat(stored.getPinnedEncoder(StreamSession.defaultVariant())).contains(libx264());
  }

  private static StreamSession detachedSession(StreamSession source) {
    var copy =
        StreamSession.builder()
            .sessionId(source.getSessionId())
            .mediaFileId(source.getMediaFileId())
            .authority(source.getAuthority())
            .sourcePath(source.getSourcePath())
            .mediaProbe(source.getMediaProbe())
            .transcodeDecision(source.getTranscodeDecision())
            .options(source.getOptions())
            .createdAt(source.getCreatedAt())
            .variants(source.getVariants())
            .build();
    copy.setCurrentSegment(source.getCurrentSegment());
    source
        .getVariantHandles()
        .forEach(
            (label, handle) -> {
              copy.setVariantHandle(label, handle);
              source.getPinnedEncoder(label).ifPresent(encoder -> copy.pinEncoder(label, encoder));
            });
    return copy;
  }

  @Test
  @DisplayName("Should roll back the session when the first transcode startup fails")
  void shouldRollbackSessionWhenFirstTranscodeStartupFails() {
    var failingExecutor = new FailingStartupTranscodeExecutor(segmentStore);
    service = serviceWith(failingExecutor, runtimeRegistry);
    var file = seedMediaFile();

    var command = createStreamSessionCommand(file.getId(), UUID.randomUUID(), defaultOptions());
    assertThatThrownBy(() -> service.createSession(command))
        .isInstanceOf(TranscodeException.class)
        .hasMessage("Simulated transcode startup failure");

    assertStartupRolledBack(failingExecutor);
  }

  @Test
  @DisplayName("Should preserve the startup failure when rollback cleanup fails")
  void shouldPreserveStartupFailureWhenRollbackCleanupFails() {
    var failingExecutor = new FailingStartupTranscodeExecutor(segmentStore);
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
    assertThat(executor.terminalFailure(sessionId)).isEmpty();
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
    var options = StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants())
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(tuple(1280, 720, StreamSession.defaultVariant()));
    assertThat(session.getHandle()).isPresent();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixedQualitiesAndTheirEncodedPictures")
  @DisplayName(
      "Should encode a fixed quality within its picture box when the source is larger, never"
          + " enlarging it")
  void shouldEncodeAFixedQualityWithinItsPictureBoxWhenTheSourceIsLargerNeverEnlargingIt(
      FixedQualityPicture fixedQuality) {
    var source = fixedQuality.source();
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .width(source.width())
            .height(source.height())
            .build());
    var options = StreamingOptions.builder().quality(fixedQuality.quality()).build();

    createSession(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request ->
                assertThat(new PictureSize(request.width(), request.height()))
                    .isEqualTo(fixedQuality.picture()));
  }

  static Stream<FixedQualityPicture> fixedQualitiesAndTheirEncodedPictures() {
    var uhd = new PictureSize(3840, 2160);
    var fullHd = new PictureSize(1920, 1080);
    return Stream.of(
        FixedQualityPicture.builder()
            .source(uhd)
            .quality(VideoQuality.FULL_HD_1080P)
            .picture(fullHd)
            .build(),
        FixedQualityPicture.builder().source(uhd).quality(VideoQuality.UHD_4K).picture(uhd).build(),
        FixedQualityPicture.builder()
            .source(fullHd)
            .quality(VideoQuality.UHD_4K)
            .picture(fullHd)
            .build(),
        FixedQualityPicture.builder()
            .source(uhd)
            .quality(VideoQuality.LOW_360P)
            .picture(new PictureSize(640, 360))
            .build());
  }

  @Builder
  record FixedQualityPicture(PictureSize source, VideoQuality quality, PictureSize picture) {}

  @Test
  @DisplayName(
      "Should encode a fixed quality within the viewer's maximum height when the source is taller")
  void shouldEncodeAFixedQualityWithinTheViewersMaximumHeightWhenTheSourceIsTaller() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    var options =
        StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).maxHeight(300).build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(534);
              assertThat(request.height()).isEqualTo(300);
            });
    assertThat(playlist).contains(",RESOLUTION=534x300,");
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
  @DisplayName(
      "Should offer every variant of the ladder when fewer job slots are free than the ladder has"
          + " variants")
  void shouldOfferEveryVariantOfTheLadderWhenFewerJobSlotsAreFreeThanTheLadderHasVariants() {
    transcodeExecutor.setAvailableSlots(1);
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getVariants())
        .extracting(QualityVariant::label)
        .containsExactly("480p", "1080p", "720p", "360p");
    assertThat(transcodeExecutor.getStartedVariants()).containsExactly("480p");
  }

  @Test
  @DisplayName(
      "Should count only running job attempts against the transcode limit when stream sessions"
          + " offer a ladder")
  void shouldCountOnlyRunningJobAttemptsAgainstTheTranscodeLimitWhenStreamSessionsOfferALadder() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());

    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      assertThat(createSession(file.getId(), UUID.randomUUID(), defaultOptions()).getVariants())
          .hasSize(4);
    }

    var oneMore =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    assertThat(service.createSession(oneMore))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
  }

  @Test
  @DisplayName(
      "Should count a variant that a request started against the transcode limit when another"
          + " stream session is created")
  void
      shouldCountAVariantThatARequestStartedAgainstTheTranscodeLimitWhenAnotherStreamSessionIsCreated() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var first = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    segmentStore.addSegment(first.getSessionId(), "1080p/init.mp4", new byte[] {1});

    assertThat(coordinator.deliver(first.getSessionId(), "1080p", "1080p/init.mp4"))
        .isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(first.getVariantHandles()).containsOnlyKeys("480p", "1080p");
    var oneMore =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    assertThat(service.createSession(oneMore))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)));
  }

  @Test
  @DisplayName(
      "Should start no job attempt past the transcode limit when an initialization segment request"
          + " needs a variant")
  void
      shouldStartNoJobAttemptPastTheTranscodeLimitWhenAnInitializationSegmentRequestNeedsAVariant() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var first = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = coordinator.deliver(first.getSessionId(), "1080p", "1080p/init.mp4");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startedBefore);
    assertThat(first.getVariantHandles()).containsOnlyKeys("480p");
  }

  @Test
  @DisplayName(
      "Should start the requested variant at the transcode limit when its media segment request"
          + " stops the stream session's other variant")
  void
      shouldStartTheRequestedVariantAtTheTranscodeLimitWhenItsMediaSegmentRequestStopsTheStreamSessionsOtherVariant()
          throws Exception {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var first = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    var startedBefore = transcodeExecutor.getStartedRequests().size();
    var looks = transcodeExecutor.livenessChecks();

    var delivery =
        CompletableFuture.supplyAsync(
            () -> coordinator.deliver(first.getSessionId(), "1080p", "1080p/segment1.m4s"));
    transcodeExecutor.awaitStartedRequestCount(startedBefore + 1);
    transcodeExecutor.awaitLivenessCheckCount(looks + 1);
    upload(first.getSessionId(), "1080p", "1080p/segment1.m4s");

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(first.getVariantHandles()).containsOnlyKeys("1080p");
  }

  @Test
  @DisplayName(
      "Should stop another stream session's left variant to admit a stream session when the"
          + " transcode limit is reached")
  void
      shouldStopAnotherStreamSessionsLeftVariantToAdmitAStreamSessionWhenTheTranscodeLimitIsReached()
          throws Exception {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var switching = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    var switchingId = switching.getSessionId();
    var switched = switchFrom480pTo1080pKeeping480pAsTheLeftVariant(switchingId);
    var other = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    assertThat(switching.getVariantHandles()).containsOnlyKeys("480p", "1080p");

    var admitted = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());

    assertThat(admitted.getVariantHandles()).containsOnlyKeys("480p");
    assertThat(switching.getVariantHandles()).containsOnlyKeys("1080p");
    assertThat(transcodeExecutor.isRunning(other.getSessionId(), "480p")).isTrue();
    assertThat(capacityRefusals("no_free_configured_transcode_slot")).isZero();
    upload(switchingId, "1080p", "1080p/segment1.m4s");
    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  // ADR 0009 prefers a family that a worker encodes in hardware to H.264.
  @Test
  @DisplayName(
      "Should encode AV1 when stopping another stream session's left variant frees a worker that"
          + " encodes AV1 in hardware and H.264 in software")
  void
      shouldEncodeAv1WhenStoppingAnotherStreamSessionsLeftVariantFreesAWorkerThatEncodesAv1InHardwareAndH264InSoftware()
          throws Exception {
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of("h264", "av1"));
    transcodeExecutor.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(av1Nvenc(), libx264()));
    transcodeExecutor.limitJobSlots(ExecutionTargetId.LOCAL, 2);
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var switching = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    var switchingId = switching.getSessionId();
    var switched = switchFrom480pTo1080pKeeping480pAsTheLeftVariant(switchingId);
    assertThat(switching.getVariantHandles()).containsOnlyKeys("480p", "1080p");
    assertThat(transcodeExecutor.encodableVideoCodecFamilies()).isEmpty();

    var admitted = createSession(seedMediaFile().getId(), av1AndH264Declaration());

    assertThat(admitted.getTranscodeDecision().videoCodecFamily()).isEqualTo("av1");
    assertThat(switching.getVariantHandles()).containsOnlyKeys("1080p");
    upload(switchingId, "1080p", "1080p/segment1.m4s");
    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName(
      "Should encode AV1 after stopping a left variant on the only worker that encodes AV1 in"
          + " hardware when the only H.264 worker is busy")
  void
      shouldEncodeAv1AfterStoppingALeftVariantOnTheOnlyWorkerThatEncodesAv1InHardwareWhenTheOnlyH264WorkerIsBusy()
          throws Exception {
    useAnAv1WorkerWithTwoSlotsAndAnH264WorkerWithOne(av1Nvenc());
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var switching = createSession(seedMediaFile().getId(), av1AndH264Declaration());
    assertThat(switching.getTranscodeDecision().videoCodecFamily()).isEqualTo("av1");
    var switchingId = switching.getSessionId();
    var switched = switchFrom480pTo1080pKeeping480pAsTheLeftVariant(switchingId);
    assertThat(switching.getVariantHandles()).containsOnlyKeys("480p", "1080p");
    var busy =
        createSession(
            seedMediaFile().getId(),
            UUID.randomUUID(),
            StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build());
    assertThat(busy.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
    assertThat(transcodeExecutor.encodableVideoCodecFamilies()).isEmpty();

    var admitted = accepted(service.createSession(createCommandDeclaring(av1AndH264Declaration())));

    assertThat(admitted.getTranscodeDecision().videoCodecFamily()).isEqualTo("av1");
    assertThat(switching.getVariantHandles()).containsOnlyKeys("1080p");
    upload(switchingId, "1080p", "1080p/segment1.m4s");
    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName(
      "Should encode AV1 after stopping a left variant on the only AV1 worker when the only H.264"
          + " worker is busy")
  void shouldEncodeAv1AfterStoppingALeftVariantOnTheOnlyAv1WorkerWhenTheOnlyH264WorkerIsBusy()
      throws Exception {
    useAnAv1WorkerWithTwoSlotsAndAnH264WorkerWithOne(libsvtav1());
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var switching = createSession(seedMediaFile().getId(), declarationBuilderFor(AV1).build());
    var switchingId = switching.getSessionId();
    var switched = switchFrom480pTo1080pKeeping480pAsTheLeftVariant(switchingId);
    createSession(
        seedMediaFile().getId(),
        UUID.randomUUID(),
        StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build());
    assertThat(transcodeExecutor.encodableVideoCodecFamilies()).isEmpty();

    var admitted = accepted(service.createSession(createCommandDeclaring(av1AndH264Declaration())));

    assertThat(admitted.getTranscodeDecision().videoCodecFamily()).isEqualTo("av1");
    assertThat(switching.getVariantHandles()).containsOnlyKeys("1080p");
    upload(switchingId, "1080p", "1080p/segment1.m4s");
    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  // Only the busy worker's AV1 encoder writes PQ, and only the other AV1 worker keeps a left
  // variant. Stopping it frees a worker that encodes AV1 in 8-bit SDR, so the stop tries that
  // format before it gives up on AV1 (#397).
  @Test
  @DisplayName(
      "Should encode 8-bit SDR AV1 after stopping a left variant on a worker without PQ when the"
          + " only worker that writes PQ is busy")
  void shouldEncode8BitSdrAv1AfterStoppingALeftVariantOnAWorkerWithoutPqWhenThePqWorkerIsBusy()
      throws Exception {
    var left = keepALeftVariantOnAnSdrAv1WorkerWhileTheOnlyPqWorkerIsBusy();
    var switching = left.session();
    var switchingId = switching.getSessionId();
    probeResults.setDefaultProbe(hdr10HevcProbe());

    var admitted =
        accepted(
            service.createSession(
                createCommandDeclaring(
                    av1Declaration(List.of(CompatibleBase.SDR, CompatibleBase.PQ)))));

    assertThat(admitted.getTranscodeDecision().videoCodecFamily()).isEqualTo("av1");
    assertThat(admitted.getTranscodeDecision().videoEncodeFormat())
        .isEqualTo(EncodeFormat.SDR_8_BIT);
    assertThat(switching.getVariantHandles()).containsOnlyKeys("1080p");
    upload(switchingId, "1080p", "1080p/segment1.m4s");
    assertThat(left.switched().get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  // #58: the client declares H.265 Main 10 in PQ and H.265 Main in SDR as separate entries, and
  // its Main 10 entry stops below the source's level, so the source is encoded. Only the busy
  // worker's libx265 writes PQ, so the stop tries 8-bit SDR in H.265 Main, not Main 10.
  @Test
  @DisplayName(
      "Should encode 8-bit SDR HEVC Main after stopping a left variant on a worker without PQ when"
          + " the only worker that writes PQ in Main 10 is busy")
  void shouldEncode8BitSdrHevcMainAfterStoppingALeftVariantOnAWorkerWithoutPqWhenThePqWorkerIsBusy()
      throws Exception {
    var pqWorker = new ExecutionTargetId("pq-worker");
    var sdrWorker = new ExecutionTargetId("sdr-worker");
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of("hevc"));
    transcodeExecutor.setExecutionTargets(List.of(sdrWorker, pqWorker));
    transcodeExecutor.setTargetEncoders(sdrWorker, List.of(libx265()));
    transcodeExecutor.setTargetEncoders(
        pqWorker,
        List.of(
            WorkerVerifiedEncoder.builder()
                .backend(libx265().backend())
                .keyframeVerified(true)
                .encodeFeatures(Set.of(VideoEncodeFeature.TEN_BIT, VideoEncodeFeature.PQ))
                .build()));
    transcodeExecutor.limitJobSlots(sdrWorker, 2);
    transcodeExecutor.limitJobSlots(pqWorker, 1);
    var switching = createSession(seedMediaFile().getId(), declarationBuilderFor(HEVC).build());
    var switchingId = switching.getSessionId();
    var switched = switchFrom480pTo1080pKeeping480pAsTheLeftVariant(switchingId);
    createSession(seedMediaFile().getId(), declarationBuilderFor(HEVC).build());
    assertThat(transcodeExecutor.encodableVideoCodecFamilies()).isEmpty();
    probeResults.setDefaultProbe(hdr10HevcProbe());

    var admitted =
        accepted(
            service.createSession(
                createCommandDeclaring(
                    declarationBuilderFor(HEVC)
                        .video(
                            List.of(
                                hevcEntryBuilder()
                                    .compatibleBases(List.of(CompatibleBase.PQ))
                                    .codecProfiles(
                                        List.of(
                                            VideoCodecProfileCapability.builder()
                                                .profile(VideoCodecProfile.HEVC_MAIN_10)
                                                .maxLevel(41)
                                                .maxTier(Optional.of(VideoCodecTier.MAIN))
                                                .build()))
                                    .build(),
                                hevcEntryBuilder()
                                    .maxBitDepth(8)
                                    .codecProfiles(
                                        List.of(
                                            VideoCodecProfileCapability.builder()
                                                .profile(VideoCodecProfile.HEVC_MAIN)
                                                .maxLevel(51)
                                                .maxTier(Optional.of(VideoCodecTier.MAIN))
                                                .build()))
                                    .build()))
                        .build())));

    assertThat(admitted.getTranscodeDecision().encodeCodecProfile())
        .contains(EncodeCodecProfile.HEVC_MAIN);
    assertThat(admitted.getTranscodeDecision().videoEncodeFormat())
        .isEqualTo(EncodeFormat.SDR_8_BIT);
    assertThat(switching.getVariantHandles()).containsOnlyKeys("1080p");
    upload(switchingId, "1080p", "1080p/segment1.m4s");
    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  // The client takes AV1 only in PQ, so an 8-bit SDR encode on the freed worker would not play
  // and stopping the left variant would only cost its player the switch back.
  @Test
  @DisplayName(
      "Should keep a left variant on a worker without PQ when the client accepts AV1 only in PQ"
          + " and the only worker that writes PQ is busy")
  void shouldKeepALeftVariantOnAWorkerWithoutPqWhenTheClientAcceptsAv1OnlyInPq() throws Exception {
    var left = keepALeftVariantOnAnSdrAv1WorkerWhileTheOnlyPqWorkerIsBusy();
    var switching = left.session();
    var switchingId = switching.getSessionId();
    probeResults.setDefaultProbe(hdr10HevcProbe());

    var outcome =
        service.createSession(createCommandDeclaring(av1Declaration(List.of(CompatibleBase.PQ))));

    assertThat(rejectionOf(outcome))
        .isInstanceOf(CreateStreamSessionRejection.TranscodeCapacityUnavailable.class);
    assertThat(transcodeExecutor.isRunning(switchingId, "480p")).isTrue();
    assertThat(switching.getVariantHandles()).containsOnlyKeys("480p", "1080p");
    upload(switchingId, "1080p", "1080p/segment1.m4s");
    assertThat(left.switched().get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  // An SDR AV1 session keeps 480p as its left variant on the AV1 worker without PQ while it
  // switches to 1080p there, and another session fills the only worker whose AV1 encoder writes PQ.
  private LeftVariantOnAWorker keepALeftVariantOnAnSdrAv1WorkerWhileTheOnlyPqWorkerIsBusy() {
    var pqWorker = new ExecutionTargetId("pq-worker");
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of("av1"));
    transcodeExecutor.setExecutionTargets(List.of(AV1_WORKER, pqWorker));
    transcodeExecutor.setTargetEncoders(AV1_WORKER, List.of(libsvtav1()));
    transcodeExecutor.setTargetEncoders(
        pqWorker,
        List.of(
            WorkerVerifiedEncoder.builder()
                .backend(libsvtav1().backend())
                .keyframeVerified(true)
                .encodeFeatures(Set.of(VideoEncodeFeature.TEN_BIT, VideoEncodeFeature.PQ))
                .build()));
    transcodeExecutor.limitJobSlots(AV1_WORKER, 2);
    transcodeExecutor.limitJobSlots(pqWorker, 1);
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var switching = createSession(seedMediaFile().getId(), declarationBuilderFor(AV1).build());
    var switched = switchFrom480pTo1080pKeeping480pAsTheLeftVariant(switching.getSessionId());
    createSession(seedMediaFile().getId(), declarationBuilderFor(AV1).build());
    assertThat(transcodeExecutor.encodableVideoCodecFamilies()).isEmpty();
    return new LeftVariantOnAWorker(switching, switched);
  }

  private record LeftVariantOnAWorker(
      StreamSession session, CompletableFuture<SegmentDelivery> switched) {}

  private static ClientCapabilityDeclaration av1Declaration(List<CompatibleBase> compatibleBases) {
    return declarationBuilderFor(AV1)
        .video(List.of(av1EntryBuilder().compatibleBases(compatibleBases).build()))
        .build();
  }

  // The H.264 worker keeps a free slot, so H.264 is decided before the stop. The stop frees a
  // place under the transcode limit and a slot on the worker that encodes AV1 in hardware.
  @Test
  @DisplayName(
      "Should encode AV1 when stopping a left variant to free a place under the transcode limit"
          + " frees the only worker that encodes AV1 in hardware")
  void
      shouldEncodeAv1WhenStoppingALeftVariantToFreeAPlaceUnderTheTranscodeLimitFreesTheOnlyWorkerThatEncodesAv1InHardware()
          throws Exception {
    useAnAv1WorkerWithTwoSlotsAndAnH264WorkerWithOne(av1Nvenc());
    transcodeExecutor.limitJobSlots(H264_WORKER, 2);
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .framerate(OptionalDouble.of(23.976))
            .videoCodec("hevc")
            .bitrate(8_000_000L)
            .build());
    var switching = createSession(seedMediaFile().getId(), av1AndH264Declaration());
    var switchingId = switching.getSessionId();
    var switched = switchFrom480pTo1080pKeeping480pAsTheLeftVariant(switchingId);
    createSession(
        seedMediaFile().getId(),
        UUID.randomUUID(),
        StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build());
    assertThat(transcodeExecutor.encodableVideoCodecFamilies()).containsExactly("h264");

    var admitted = accepted(service.createSession(createCommandDeclaring(av1AndH264Declaration())));

    assertThat(admitted.getTranscodeDecision().videoCodecFamily()).isEqualTo("av1");
    assertThat(admitted.getVariantHandles().keySet())
        .singleElement()
        .satisfies(
            started ->
                assertThat(
                        transcodeExecutor.runningExecutionTarget(admitted.getSessionId(), started))
                    .contains(AV1_WORKER));
    assertThat(switching.getVariantHandles()).containsOnlyKeys("1080p");
    upload(switchingId, "1080p", "1080p/segment1.m4s");
    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName(
      "Should keep another stream session's left variant when a stream session whose encode has no"
          + " frame rate is refused while no encoder has a free slot")
  void
      shouldKeepAnotherStreamSessionsLeftVariantWhenAStreamSessionWhoseEncodeHasNoFrameRateIsRefusedWhileNoEncoderHasAFreeSlot()
          throws Exception {
    var probe = defaultProbeBuilder().videoCodec("hevc").bitrate(8_000_000L);
    probeResults.setDefaultProbe(probe.framerate(OptionalDouble.of(23.976)).build());
    transcodeExecutor.setEncodeSlots(Map.of("h264", 2));
    var switching = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    var switchingId = switching.getSessionId();
    var switched = switchFrom480pTo1080pKeeping480pAsTheLeftVariant(switchingId);
    assertThat(transcodeExecutor.encodableVideoCodecFamilies()).isEmpty();
    probeResults.setDefaultProbe(probe.framerate(OptionalDouble.empty()).build());

    var outcome =
        service.createSession(
            createStreamSessionCommand(
                seedMediaFile().getId(), UUID.randomUUID(), defaultOptions()));

    assertThat(rejectionOf(outcome))
        .isInstanceOf(CreateStreamSessionRejection.TranscodeCapacityUnavailable.class);
    assertThat(transcodeExecutor.isRunning(switchingId, "480p")).isTrue();
    assertThat(switching.getVariantHandles()).containsOnlyKeys("480p", "1080p");
    upload(switchingId, "1080p", "1080p/segment1.m4s");
    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName("Should reject ABR session when all transcode slots are full")
  void shouldRejectAbrSessionWhenAllTranscodeSlotsAreFull() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().framerate(OptionalDouble.of(23.976)).videoCodec("hevc").build());

    var singleVariantOptions =
        StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build();

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

  // One worker encodes only AV1 with the given encoder and has two job slots; another encodes only
  // H.264 in software and has one.
  private void useAnAv1WorkerWithTwoSlotsAndAnH264WorkerWithOne(WorkerVerifiedEncoder av1Encoder) {
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of("h264", "av1"));
    transcodeExecutor.setExecutionTargets(List.of(AV1_WORKER, H264_WORKER));
    transcodeExecutor.setTargetEncoders(AV1_WORKER, List.of(av1Encoder));
    transcodeExecutor.setTargetEncoders(H264_WORKER, List.of(libx264()));
    transcodeExecutor.limitJobSlots(AV1_WORKER, 2);
    transcodeExecutor.limitJobSlots(H264_WORKER, 1);
  }

  // The player plays 480p, then asks for 1080p, whose start the session awaits while it keeps 480p
  // as its left variant. The returned delivery completes once 1080p stores its segment.
  private CompletableFuture<SegmentDelivery> switchFrom480pTo1080pKeeping480pAsTheLeftVariant(
      UUID sessionId) {
    upload(sessionId, "480p", "480p/segment0.m4s");
    assertThat(coordinator.deliver(sessionId, "480p", "480p/segment0.m4s"))
        .isInstanceOf(SegmentDelivery.Ready.class);
    var looks = transcodeExecutor.livenessChecks(sessionId, "1080p");
    var switched =
        CompletableFuture.supplyAsync(
            () -> coordinator.deliver(sessionId, "1080p", "1080p/segment1.m4s"));
    transcodeExecutor.awaitLivenessCheckCount(sessionId, "1080p", looks + 1);
    var sessionMutexLock = sessionMutex.getMutex(sessionId);
    await()
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(1))
        .atMost(Duration.ofSeconds(5))
        .until(() -> !sessionMutexLock.isLocked());
    return switched;
  }

  // Stores the segment, then wakes the requests held on its variant, as a worker's upload does.
  private void upload(UUID sessionId, String variantLabel, String segmentName) {
    segmentStore.addSegment(sessionId, segmentName, new byte[] {1});
    transcodeExecutor.heldSegmentRequests().wakeVariant(sessionId, variantLabel);
  }

  private static final class FailingStartupTranscodeExecutor extends FakeTranscodeExecutor {

    private final FakeSegmentStore segmentStore;
    private boolean failOnStop;
    private final List<TranscodeRequest> attemptedRequests = new ArrayList<>();

    private FailingStartupTranscodeExecutor(FakeSegmentStore segmentStore) {
      this.segmentStore = segmentStore;
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      attemptedRequests.add(request);
      failTerminally(request.sessionId(), TranscodeFailure.OUTPUT_MISMATCH);
      segmentStore.addSegment(request.sessionId(), "startup.m4s", new byte[] {1});
      throw new TranscodeException("Simulated transcode startup failure");
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
