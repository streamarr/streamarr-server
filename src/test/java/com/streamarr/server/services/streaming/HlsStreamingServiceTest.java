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
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.fixtures.StreamSessionFixture.mintHandle;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.av1Nvenc;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.libsvtav1;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.libx264;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.OutcomeTestSupport.rejectionOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContainerCropping;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.HardwareEncodedFamiliesAndExpectedEncode;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

  @ParameterizedTest
  @CsvSource({"COPY_OR_ENCODE, true", "COPY_ONLY, false"})
  @DisplayName("Should count one copy refusal when every video encoder is busy")
  void shouldCountOneCopyRefusalWhenEveryVideoEncoderIsBusy(
      VideoFormatConstraint constraint, boolean capacityUnavailable) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().keyframes(new KeyframeKnowledge.NotReported()).build());
    transcodeExecutor.setEncodeSlots(Map.of("h264", 0));
    var command =
        CreateStreamSessionCommand.builder()
            .streamSessionId(UUID.randomUUID())
            .mediaFileId(seedMediaFile().getId())
            .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
            .options(defaultOptions())
            .capabilityDeclaration(defaultDeclarationBuilder().build())
            .videoFormatConstraint(constraint)
            .build();

    var expectedRejection =
        capacityUnavailable
            ? new CreateStreamSessionRejection.TranscodeCapacityUnavailable(3)
            : new CreateStreamSessionRejection.NoEligibleVideoFormat();
    assertThat(service.createSession(command)).isEqualTo(Outcome.rejected(expectedRejection));
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
            .capabilityDeclaration(declarationBuilderFor(HEVC).build())
            .build();

    var outcome = service.createSession(command);

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"aac, AUTO, VIDEO_TRANSCODE", "flac, HIGH_720P, FULL_TRANSCODE"})
  @DisplayName(
      "Should reject the stream session when the video must be encoded and the probe has no frame rate")
  void shouldRejectTheStreamSessionWhenTheVideoMustBeEncodedAndTheProbeHasNoFrameRate(
      String audioCodec, VideoQuality quality, TranscodeMode encodingMode) {
    var probe = defaultProbeBuilder().videoCodec("hevc").audioCodec(audioCodec);
    var options = StreamingOptions.builder().quality(quality).build();
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

  @ParameterizedTest
  @CsvSource({
    "0, 0, AUTO",
    "0, 1080, AUTO",
    "1920, 0, AUTO",
    "0, 0, HIGH_720P",
    "0, 1080, HIGH_720P",
    "1920, 0, HIGH_720P"
  })
  @DisplayName("Should reject session creation when the displayed picture has no area")
  void shouldRejectSessionCreationWhenTheDisplayedPictureHasNoArea(
      int width, int height, VideoQuality quality) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(width).height(height).build());
    var options = StreamingOptions.builder().quality(quality).build();
    var command = createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(service.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.InvalidPictureSize()));
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

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
  @CsvSource({"AUTO, 1, 0", "AUTO, 0, 1", "FULL_HD_1080P, 1, 0", "FULL_HD_1080P, 0, 1"})
  @DisplayName(
      "Should reject an encode as an invalid picture size when the viewer's maximums leave no"
          + " picture")
  void shouldRejectAnEncodeAsAnInvalidPictureSizeWhenTheViewersMaximumsLeaveNoPicture(
      VideoQuality quality, int maxHeight, int maxWidth) {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    var options =
        StreamingOptions.builder().quality(quality).maxHeight(maxHeight).maxWidth(maxWidth).build();
    var command = createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(service.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.InvalidPictureSize()));
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
    assertThat(service.getActiveSessionCount()).isZero();
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

  @ParameterizedTest
  @CsvSource({
    "FULL_HD_1080P, 0, 0, 5000000",
    "FULL_HD_1080P, 0, 2000000, 1872000",
    "FULL_HD_1080P, 0, 700000, 700000",
    "FULL_HD_1080P, 0, 100000, 100000",
    "AUTO, 300, 0, 800000",
    "AUTO, 300, 1000000, 800000",
    "FULL_HD_1080P, 0, 20000000, 5000000",
    "AUTO, 300, 20000000, 800000"
  })
  @DisplayName(
      "Should dispatch a positive bitrate and advertise it in the playlist when the probe names no"
          + " source bitrate")
  void shouldDispatchAPositiveBitrateAndAdvertiseItInThePlaylistWhenTheProbeNamesNoSourceBitrate(
      VideoQuality quality, int maxHeight, int maxBitrate, long expectedBitrate) {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").bitrate(0).build());
    var options =
        StreamingOptions.builder()
            .quality(quality)
            .maxHeight(maxHeight)
            .maxBitrate(maxBitrate)
            .build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    var audioBitrate = session.getTranscodeDecision().audioDecision().bitrate();
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(request -> assertThat(request.bitrate()).isEqualTo(expectedBitrate));
    assertThat(playlist).contains(",AVERAGE-BANDWIDTH=" + (expectedBitrate + audioBitrate) + ",");
  }

  // The 2160p tier's H.264 nominal bitrate is 20 Mb/s, and the 1080p tier's is 5 Mb/s.
  @ParameterizedTest
  @CsvSource({
    "LOW_360P, 1920, 1080, 8000000, 800000",
    "HIGH_720P, 1920, 1080, 8000000, 3000000",
    "FULL_HD_1080P, 1920, 1080, 2000000, 2000000",
    "UHD_4K, 1920, 1080, 8000000, 5000000",
    "UHD_4K, 1920, 1080, 0, 5000000",
    "UHD_4K, 3840, 2160, 40000000, 20000000",
    "UHD_4K, 3840, 2160, 8000000, 8000000"
  })
  @DisplayName(
      "Should encode a fixed quality at the nominal bitrate of the tier that holds the encoded"
          + " picture, capped by the source's known bitrate, when the viewer sets no maximum"
          + " bitrate")
  void
      shouldEncodeAFixedQualityAtTheNominalBitrateOfTheTierThatHoldsTheEncodedPictureCappedByTheSourcesKnownBitrateWhenTheViewerSetsNoMaximumBitrate(
          VideoQuality quality, int width, int height, long sourceBitrate, long expectedBitrate) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .width(width)
            .height(height)
            .bitrate(sourceBitrate)
            .build());
    var options = StreamingOptions.builder().quality(quality).build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(request -> assertThat(request.bitrate()).isEqualTo(expectedBitrate));
  }

  // ADR 0050: a variant costs its video bitrate plus the stream session's audio bitrate, here a
  // 128 kb/s stereo AAC encode.
  @ParameterizedTest
  @CsvSource({
    "FULL_HD_1080P, , 2000000, 1920, 1080, 1872000",
    "FULL_HD_1080P, , 1000000, 1920, 1080, 872000",
    "FULL_HD_1080P, , 700000, 640, 360, 700000",
    "FULL_HD_1080P, , 100000, 640, 360, 100000",
    "AUTO, 500, 1000000, 498, 280, 872000",
    "AUTO, , 500000, 640, 360, 500000"
  })
  @DisplayName(
      "Should encode the single variant within the maximum bitrate less the audio, or in the 360p"
          + " box, when the viewer sets a maximum bitrate")
  void
      shouldEncodeTheSingleVariantWithinTheMaximumBitrateLessTheAudioOrInThe360pBoxWhenTheViewerSetsAMaximumBitrate(
          VideoQuality quality,
          Integer maxWidth,
          int maxBitrate,
          int expectedWidth,
          int expectedHeight,
          long expectedVideoBitrate) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").bitrate(8_000_000L).build());
    var options =
        StreamingOptions.builder()
            .quality(quality)
            .maxWidth(maxWidth)
            .maxBitrate(maxBitrate)
            .build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(128_000L);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(expectedWidth);
              assertThat(request.height()).isEqualTo(expectedHeight);
              assertThat(request.bitrate()).isEqualTo(expectedVideoBitrate);
            });
    var averageBandwidth = expectedVideoBitrate + 128_000L;
    assertThat(playlist)
        .contains(
            "BANDWIDTH="
                + (averageBandwidth + averageBandwidth / 5)
                + ",AVERAGE-BANDWIDTH="
                + averageBandwidth
                + ",");
  }

  // A stereo DTS source encodes 128 kb/s AAC, and a 7.1 one 768 kb/s E-AC-3 5.1.
  @ParameterizedTest
  @CsvSource({
    "2, 3100000, 128000, '480p, 360p'",
    "2, 1800000, 128000, '480p, 360p'",
    "8, 1800000, 768000, '360p'",
    "8, 2000000, 768000, '360p'"
  })
  @DisplayName(
      "Should offer a tier only when its video bitrate plus the session's audio bitrate is within"
          + " the viewer's maximum bitrate")
  void shouldOfferATierOnlyWhenItsVideoPlusAudioBitrateIsWithinTheViewersMaximumBitrate(
      int sourceChannels, int maxBitrate, long audioBitrate, String expectedLabels) {
    var options =
        StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(maxBitrate).build();

    var session = createDtsSession(sourceChannels, options);
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(audioBitrate);
    assertThat(session.getVariants())
        .extracting(QualityVariant::label)
        .containsExactlyInAnyOrder(expectedLabels.split(", "));
    assertThat(averageBandwidths(playlist))
        .isNotEmpty()
        .allSatisfy(bandwidth -> assertThat(bandwidth).isLessThanOrEqualTo(maxBitrate));
  }

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

  // ADR 0050: a 7.1 DTS source encodes E-AC-3 5.1 at 768 kb/s, which leaves less than the 360p
  // tier's 800 kb/s under each of these maximums. The session takes the first audio step that
  // leaves it, and the worker's job and the playlist carry that step.
  @ParameterizedTest
  @CsvSource({
    "1400000, eac3, 6, 448000, ec-3",
    "1248000, eac3, 6, 448000, ec-3",
    "1247999, eac3, 6, 384000, ec-3",
    "1000000, aac, 2, 128000, mp4a.40.2",
    "928000, aac, 2, 128000, mp4a.40.2",
    "927999, aac, 2, 96000, mp4a.40.2",
    "870000, aac, 2, 64000, mp4a.40.2"
  })
  @DisplayName(
      "Should take the first audio step that leaves the 360p tier's video bitrate when the"
          + " session's audio leaves less under the maximum bitrate")
  void
      shouldTakeTheFirstAudioStepThatLeavesThe360pTiersVideoBitrateWhenTheSessionsAudioLeavesLessUnderTheMaximumBitrate(
          int maxBitrate, String codec, int channels, long bitrate, String codecString) {
    var options =
        StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(maxBitrate).build();

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
        .containsExactly(AudioMode.TRANSCODE, codec, channels, bitrate);
    assertThat(session.getVariants()).extracting(QualityVariant::label).containsExactly("360p");
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> assertThat(request.transcodeDecision().audioDecision()).isEqualTo(audio));
    assertThat(playlist)
        .contains("CHANNELS=\"" + channels + "\"")
        .contains("," + codecString + "\"");
    assertThat(averageBandwidths(playlist))
        .isNotEmpty()
        .allSatisfy(bandwidth -> assertThat(bandwidth).isLessThanOrEqualTo(maxBitrate));
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
              assertThat(request.width()).isEqualTo(1280);
              assertThat(request.height()).isEqualTo(720);
              assertThat(request.bitrate()).isEqualTo(816_000L);
            });
  }

  // A copied 640 kb/s AC-3 5.1 track leaves 660 kb/s under 1,300 kb/s. The player declares AC-3 but
  // no E-AC-3, so the copy becomes a 448 kb/s AC-3 5.1 encode.
  @Test
  @DisplayName(
      "Should encode a copied audio track at a lower step when its bitrate leaves less than the"
          + " 360p tier's video bitrate")
  void
      shouldEncodeACopiedAudioTrackAtALowerStepWhenItsBitrateLeavesLessThanThe360pTiersVideoBitrate() {
    var probe = defaultProbeBuilder().videoCodec("hevc").build();
    var streams =
        List.of(
            ProbeFixture.videoStreamBuilder(probe).build(),
            ProbeFixture.audioStreamBuilder("ac3")
                .index(1)
                .channels(OptionalInt.of(6))
                .bitrate(OptionalLong.of(640_000L))
                .build());
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbe(
            defaultProbeBuilder().videoCodec("hevc").streams(streams).build()));
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build()))
            .build();
    var options =
        StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(1_300_000).build();

    var session =
        accepted(
            service.createSession(
                createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                    .options(options)
                    .capabilityDeclaration(declaration)
                    .build()));

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
  // video. A copy whose video bitrate the probe does not know counts all 5 Mb/s as video.
  @ParameterizedTest
  @CsvSource({
    "ORIGINAL, 4500000, 5268000",
    "AUTO, 4500000, 5268000",
    "ORIGINAL, , 5768000",
    "AUTO, , 5768000"
  })
  @DisplayName(
      "Should advertise the copied video's bitrate plus the encoded audio's bitrate when the"
          + " session copies the video and encodes the audio")
  void
      shouldAdvertiseTheCopiedVideosBitratePlusTheEncodedAudiosBitrateWhenTheSessionCopiesTheVideoAndEncodesTheAudio(
          VideoQuality quality, Long videoBitrate, long expectedAverageBandwidth) {
    var session =
        createCopySessionWithSurroundOpus(
            quality, videoBitrate == null ? OptionalLong.empty() : OptionalLong.of(videoBitrate));
    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(768_000L);
    assertThat(playlist)
        .contains(
            "BANDWIDTH="
                + (expectedAverageBandwidth + expectedAverageBandwidth / 5)
                + ",AVERAGE-BANDWIDTH="
                + expectedAverageBandwidth
                + ",");
  }

  // A copied track whose bitrate the probe does not know takes a nominal 128 kb/s, far below a
  // stereo FLAC track's rate. The 2.2 Mb/s source holds 1 Mb/s of video and that track.
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"ORIGINAL", "AUTO"})
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

  // The declaration lists stereo AAC and 7.1 E-AC-3 but no Opus, so the session encodes the audio.
  private StreamSession createCopySessionWithSurroundOpus(
      VideoQuality quality, OptionalLong videoBitrate) {
    var probe = defaultProbeBuilder().bitrate(5_000_000L).build();
    var streams =
        List.of(
            ProbeFixture.videoStreamBuilder(probe).bitrate(videoBitrate).build(),
            ProbeFixture.audioStreamBuilder("opus")
                .index(1)
                .channels(OptionalInt.of(8))
                .bitrate(OptionalLong.of(450_000L))
                .build());
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbe(
            defaultProbeBuilder().bitrate(5_000_000L).streams(streams).build()));
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
                .options(StreamingOptions.builder().quality(quality).build())
                .capabilityDeclaration(declaration)
                .build()));
  }

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
      "Should offer only the tiers the worker encodes when a narrow picture rounds the smaller tiers"
          + " to zero width")
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

  @Test
  @DisplayName("Should encode the video when a segment interval of the source holds no keyframe")
  void shouldEncodeTheVideoWhenASegmentIntervalOfTheSourceHoldsNoKeyframe() {
    // At the 6 s period, keyframes 10.01 s apart leave the interval [12 s, 18 s) without one.
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .keyframes(
                ProbeFixture.keyframesEvery(Duration.ofMillis(10_010), Duration.ofMinutes(120)))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .extracting(request -> request.transcodeDecision().transcodeMode())
        .containsOnly(TranscodeMode.VIDEO_TRANSCODE);
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

  @Test
  @DisplayName("Should encode the video when the source's keyframes stop long before the media end")
  void shouldEncodeTheVideoWhenTheSourcesKeyframesStopLongBeforeTheMediaEnd() {
    // A copy would advertise one final segment from 6 s to the end of the 2 h source.
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .duration(Duration.ofMinutes(120))
            .keyframes(keyframesAtMillis(0, 6_000))
            .build());
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
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
  @DisplayName("Should reject Original when only two capability entries together accept the copy")
  void shouldRejectOriginalWhenOnlyTwoCapabilityEntriesTogetherAcceptTheCopy() {
    probeResults.setDefaultProbe(probeVersion5UhdAt60().build());

    var result =
        logicalPlayback()
            .start(
                logicalStart()
                    .options(StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build())
                    .capabilityDeclaration(uhdAt30AndHdAt60Declaration())
                    .build());

    assertThat(result)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(service.getAllSessions()).isEmpty();
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
  @ParameterizedTest
  @CsvSource({"1080, 1920, REMUX", "1080, 2400, VIDEO_TRANSCODE"})
  @DisplayName(
      "Should copy a portrait source only when its picture fits the capability entry's maximum"
          + " turned on its side")
  void shouldCopyAPortraitSourceOnlyWhenItsPictureFitsTheCapabilityEntrysMaximumTurnedOnItsSide(
      int width, int height, TranscodeMode expectedMode) {
    probeResults.setDefaultProbe(h264Level51ProbeAt(width, height).build());
    var landscapeHd =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(1920).maxHeight(1080).build()))
            .build();

    var session = createSession(seedMediaFile().getId(), landscapeHd);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(expectedMode);
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

  @ParameterizedTest
  @CsvSource({
    "1919, , , 5000000, '720p, 480p, 360p'",
    ", 1079, , 5000000, '720p, 480p, 360p'",
    ", , 4000000, 40000000, '720p, 480p, 360p'"
  })
  @DisplayName(
      "Should encode the video in Auto when a copyable source exceeds one of the viewer's limits")
  void shouldEncodeTheVideoInAutoWhenACopyableSourceExceedsOneOfTheViewersLimits(
      Integer maxWidth,
      Integer maxHeight,
      Integer maxBitrate,
      long sourceBitrate,
      String expectedLabels) {
    probeResults.setDefaultProbe(defaultProbeBuilder().bitrate(sourceBitrate).build());
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .maxWidth(maxWidth)
            .maxHeight(maxHeight)
            .maxBitrate(maxBitrate)
            .build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(session.getVariants())
        .extracting(QualityVariant::label)
        .containsExactlyInAnyOrder(expectedLabels.split(", "));
  }

  @ParameterizedTest
  @CsvSource({
    "AUTO, 1920, 1080, 5000000, 5000000",
    "AUTO, , , 4000000, 0",
    "FULL_HD_1080P, , , , 5000000"
  })
  @DisplayName(
      "Should copy the video when the source fits every one of the viewer's limits or its"
          + " bitrate is unknown")
  void shouldCopyTheVideoWhenTheSourceFitsEveryOneOfTheViewersLimitsOrItsBitrateIsUnknown(
      VideoQuality quality,
      Integer maxWidth,
      Integer maxHeight,
      Integer maxBitrate,
      long sourceBitrate) {
    probeResults.setDefaultProbe(defaultProbeBuilder().bitrate(sourceBitrate).build());
    var options =
        StreamingOptions.builder()
            .quality(quality)
            .maxWidth(maxWidth)
            .maxHeight(maxHeight)
            .maxBitrate(maxBitrate)
            .build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(session.getVariants()).isEmpty();
  }

  // The fixed quality's box holds a picture in either orientation; the client's maximum width and
  // height apply as it gives them.
  @ParameterizedTest
  @CsvSource({
    "FULL_HD_1080P, , , true",
    "HIGH_720P, , , false",
    "AUTO, 1080, 1920, true",
    "AUTO, 1920, 1080, false"
  })
  @DisplayName(
      "Should copy a portrait source only when it fits the fixed quality's box in either"
          + " orientation and the viewer's maximum width and height as given")
  void shouldCopyAPortraitSourceOnlyWhenItFitsTheFixedQualitysBoxAndTheViewersMaximumsAsGiven(
      VideoQuality quality, Integer maxWidth, Integer maxHeight, boolean copiesVideo) {
    probeResults.setDefaultProbe(h264Level51ProbeAt(1080, 1920).build());
    var landscapeHd =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(1920).maxHeight(1080).build()))
            .build();
    var options =
        StreamingOptions.builder().quality(quality).maxWidth(maxWidth).maxHeight(maxHeight).build();

    var session =
        accepted(
            service.createSession(
                createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                    .options(options)
                    .capabilityDeclaration(landscapeHd)
                    .build()));

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isEqualTo(copiesVideo);
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

  @Test
  @DisplayName(
      "Should encode Original within the viewer's limits when no capability entry accepts a copy")
  void shouldEncodeOriginalWithinTheViewersLimitsWhenNoCapabilityEntryAcceptsACopy() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").bitrate(8_000_000L).build());
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.ORIGINAL)
            .maxWidth(1280)
            .maxBitrate(2_000_000)
            .build();

    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().audioDecision().bitrate()).isEqualTo(128_000L);
    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(1280);
              assertThat(request.height()).isEqualTo(720);
              assertThat(request.bitrate()).isEqualTo(1_872_000L);
            });
  }

  @ParameterizedTest
  @CsvSource({"HIGH_720P, , ", "AUTO, 720, ", "AUTO, , 1000000"})
  @DisplayName(
      "Should copy a source that has no usable frame rate when the viewer's limits refuse its copy")
  void shouldCopyASourceThatHasNoUsableFrameRateWhenTheViewersLimitsRefuseItsCopy(
      VideoQuality quality, Integer maxHeight, Integer maxBitrate) {
    probeResults.setDefaultProbe(defaultProbeBuilder().framerate(OptionalDouble.empty()).build());
    var options =
        StreamingOptions.builder()
            .quality(quality)
            .maxHeight(maxHeight)
            .maxBitrate(maxBitrate)
            .build();

    var outcome =
        service.createSession(
            createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), options));

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(accepted(outcome).getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
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

  @Test
  @DisplayName(
      "Should encode a portrait source at its full size when the capability entry's maximum fits"
          + " it turned on its side")
  void
      shouldEncodeAPortraitSourceAtItsFullSizeWhenTheCapabilityEntrysMaximumFitsItTurnedOnItsSide() {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(1080).height(1920).build());
    var landscapeHd =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(1920).maxHeight(1080).build()))
            .build();

    accepted(
        service.createSession(
            createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID())
                .options(StreamingOptions.builder().quality(VideoQuality.FULL_HD_1080P).build())
                .capabilityDeclaration(landscapeHd)
                .build()));

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(1080);
              assertThat(request.height()).isEqualTo(1920);
            });
  }

  // An 8-bit SDR H.264 High profile source at level 5.1 and 24 fps.
  private static MediaProbe.MediaProbeBuilder h264Level51ProbeAt(int width, int height) {
    return defaultProbeBuilder()
        .width(width)
        .height(height)
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

  @Test
  @DisplayName("Should reject Original when the source cannot be copied")
  void shouldRejectOriginalWhenTheSourceCannotBeCopied() {
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    var result =
        logicalPlayback()
            .start(
                logicalStart()
                    .options(StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build())
                    .build());

    assertThat(result)
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoEligibleVideoFormat()));
    assertThat(service.getAllSessions()).isEmpty();
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

  private PlaybackSessionService logicalPlayback() {
    return PlaybackSessionService.builder()
        .streamingService(service)
        .authorityGate(authorityGate)
        .executor(Runnable::run)
        .maximumFormatAttempts(2)
        .sessionRetention(Duration.ofHours(24))
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
  @DisplayName(
      "Should encode H.264 and start no AV1 job when the client declares AV1 and no worker-verified encoder encodes AV1")
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
      "Should encode AV1 with the whole ladder when one hardware AV1 encode slot is free and workers that encode only H.264 have more")
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

  @Test
  @DisplayName(
      "Should reject the session for lack of capacity when only workers that cannot encode H.264 have free slots")
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
    assertThat(audio.bitrate()).isEqualTo(64_000L);
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

  @ParameterizedTest(name = "{0} → {1}, encodeVideo={2}")
  @CsvSource({
    "aac, mp4a.40.2, false",
    "aac, mp4a.40.5, false",
    "aac, mp4a.40.29, false",
    "ac3, ac-3, false",
    "eac3, ec-3, false",
    "flac, fLaC, false",
    "opus, Opus, false",
    "alac, alac, false",
    "aac, mp4a.40.2, true",
    "aac, mp4a.40.5, true",
    "aac, mp4a.40.29, true",
    "ac3, ac-3, true",
    "eac3, ec-3, true",
    "flac, fLaC, true",
    "opus, Opus, true",
    "alac, alac, true"
  })
  @DisplayName(
      "Should advertise the copied audio codec in the playlist when the client supports the source audio codec")
  void shouldAdvertiseCopiedAudioCodecInPlaylistWhenClientSupportsSourceAudioCodec(
      String sourceAudioCodec, String codecsParameter, boolean encodeVideo) {
    var probe =
        ProbeFixture.completeProbe(
            defaultProbeBuilder()
                .videoCodec(encodeVideo ? "hevc" : "h264")
                .audioCodec(sourceAudioCodec)
                .build());
    var audio =
        StreamInfo.builder()
            .index(1)
            .codecType("audio")
            .codec(Optional.of(sourceAudioCodec))
            .codecString(Optional.of(codecsParameter))
            .channels(OptionalInt.of(2))
            .build();
    probeResults.setDefaultOutcome(
        ProbeOutcome.Success.builder()
            .container(probe.container())
            .streams(
                List.of(
                    probe.streams().getFirst(),
                    audio,
                    ProbeFixture.audioStreamBuilder("aac").index(2).isDefault(true).build()))
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
                    audioEntryBuilder(AudioCodec.valueOf(sourceAudioCodec.toUpperCase(Locale.ROOT)))
                        .build()))
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
        .allSatisfy(line -> assertThat(line).contains("," + codecsParameter + "\""));
  }

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
      "Should encode the first audio stream when its stored codec string is absent and a later stream has one")
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
                    ProbeFixture.audioStreamBuilder("ac3").index(2).isDefault(true).build()))
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
      "Should advertise the codec string that the started encode forces when the session encodes a ladder")
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
      "Should advertise the media playlist's segment count to the job attempt when creating session")
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
    transcodeExecutor.failTerminally(session.getSessionId(), TranscodeFailure.OUTPUT_MISMATCH);
    transcodeExecutor.failOnStop(session.getSessionId());
    var sessionId = session.getSessionId();

    assertThatThrownBy(() -> service.destroySession(sessionId))
        .isInstanceOf(TranscodeException.class);

    assertThat(accessSession(session)).isEmpty();
    assertThat(segmentStore.segmentExists(session.getSessionId(), "segment0.m4s")).isFalse();
    assertThat(transcodeExecutor.terminalFailure(sessionId)).isEmpty();
  }

  @ParameterizedTest(name = "terminal copy failure: {0}")
  @ValueSource(booleans = {true, false})
  @DisplayName("Should release the configured transcode slot when video copy recovery is exhausted")
  void shouldReleaseTheConfiguredTranscodeSlotWhenVideoCopyRecoveryIsExhausted(
      boolean terminalCopyFailure) {
    probeResults.setDefaultProbe(defaultProbeBuilder().audioCodec("flac").build());
    var failed = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    assertThat(failed.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    failVideoCopy(failed.getSessionId(), terminalCopyFailure);

    assertThat(
            coordinator.deliver(
                failed.getSessionId(), StreamSession.defaultVariant(), "segment0.m4s"))
        .isInstanceOf(SegmentDelivery.Unrecoverable.class);

    var next =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    assertThat(service.createSession(next)).isInstanceOf(Outcome.Accepted.class);
  }

  private void failVideoCopy(UUID sessionId, boolean terminalCopyFailure) {
    if (terminalCopyFailure) {
      transcodeExecutor.failTerminally(sessionId, TranscodeFailure.OUTPUT_MISMATCH);
      return;
    }

    transcodeExecutor.markDead(sessionId);
    transcodeExecutor.setExecutionTargets(List.of());
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
      "Should advertise the rotated size and its level when a session encodes an upright phone recording")
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
      "Should offer the 1080p tier at the source's size when a wide film is shorter than 1080 lines")
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
        .contains(tuple("900p", 1600, 900), tuple("720p", 1280, 720));
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
        .contains(tuple("2160p", 3840, 2160), tuple("1440p", 2560, 1440));
  }

  @ParameterizedTest
  @CsvSource({"av1, 11600000, 8100000", "h264, 20000000, 12000000"})
  @DisplayName(
      "Should give the 2160p and 1440p tiers a codec family's nominal bitrates when the encode uses"
          + " that family")
  void shouldGiveThe2160pAnd1440pTiersACodecFamilysNominalBitratesWhenTheEncodeUsesThatFamily(
      String codecFamily, long bitrate2160p, long bitrate1440p) {
    transcodeExecutor.setEncodeSlots(Map.of(codecFamily, 3));
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .width(3840)
            .height(2160)
            .bitrate(40_000_000L)
            .build());

    var session = createSession(seedMediaFile().getId(), av1AndH264Declaration());

    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo(codecFamily);
    assertThat(session.getVariants())
        .extracting(QualityVariant::label, QualityVariant::videoBitrate)
        .contains(
            tuple("2160p", bitrate2160p), tuple("1440p", bitrate1440p), tuple("1080p", 5_000_000L));
  }

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
      "Should list the variant nearest 2000 kb/s first and the others from highest to lowest when an Auto stream session encodes")
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

  @ParameterizedTest
  @CsvSource({
    "3840, 2160, FULL_HD_1080P, 1920, 1080",
    "3840, 2160, UHD_4K, 3840, 2160",
    "1920, 1080, UHD_4K, 1920, 1080",
    "3840, 2160, LOW_360P, 640, 360"
  })
  @DisplayName(
      "Should encode a fixed quality within its picture box when the source is larger, never"
          + " enlarging it")
  void shouldEncodeAFixedQualityWithinItsPictureBoxWhenTheSourceIsLargerNeverEnlargingIt(
      int sourceWidth,
      int sourceHeight,
      VideoQuality quality,
      int expectedWidth,
      int expectedHeight) {
    probeResults.setDefaultProbe(
        defaultProbeBuilder().videoCodec("hevc").width(sourceWidth).height(sourceHeight).build());
    var options = StreamingOptions.builder().quality(quality).build();

    createSession(seedMediaFile().getId(), UUID.randomUUID(), options);

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.width()).isEqualTo(expectedWidth);
              assertThat(request.height()).isEqualTo(expectedHeight);
            });
  }

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
      "Should offer every variant of the ladder when fewer job slots are free than the ladder has variants")
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
      "Should count only running job attempts against the transcode limit when stream sessions offer a ladder")
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
      "Should count a variant that a request started against the transcode limit when another stream session is created")
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
      "Should start no job attempt past the transcode limit when an initialization segment request needs a variant")
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
      "Should start the requested variant at the transcode limit when its media segment request stops the stream session's other variant")
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
      "Should stop another stream session's left variant to admit a stream session when the transcode limit is reached")
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

  @Test
  @DisplayName(
      "Should keep another stream session's left variant when a stream session whose encode has no frame rate is refused while no encoder has a free slot")
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
    var av1Worker = new ExecutionTargetId("av1-worker");
    var h264Worker = new ExecutionTargetId("h264-worker");
    transcodeExecutor.setEncodableVideoCodecFamilies(Set.of("h264", "av1"));
    transcodeExecutor.setExecutionTargets(List.of(av1Worker, h264Worker));
    transcodeExecutor.setTargetEncoders(av1Worker, List.of(av1Encoder));
    transcodeExecutor.setTargetEncoders(h264Worker, List.of(libx264()));
    transcodeExecutor.limitJobSlots(av1Worker, 2);
    transcodeExecutor.limitJobSlots(h264Worker, 1);
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
