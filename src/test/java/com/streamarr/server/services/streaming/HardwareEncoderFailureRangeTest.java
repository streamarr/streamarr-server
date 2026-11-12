package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.OutcomeTestSupport.rejectionOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DynamicRangeChoice;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoEncodeFeature;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.streaming.events.RequestedVariantHardwareEncoderFailedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The range and bit depth of an encode after its hardware encoder fails during a stream (ADR 0044),
 * through the real transcode decision, stream session and playlist. The encode continues on a
 * software encoder of the codec family in the same range and bit depth when one on a worker with a
 * free slot writes them. Otherwise a 10-bit SDR encode continues there in 8 bits, which keeps its
 * range. A PQ or HLG encode never continues in SDR on the software encoder: Auto and a fixed
 * quality give the range up in a new format attempt, as Auto does after a format error during the
 * encode, and name HDR_NOT_KEPT (ADR 0013).
 */
@Tag("UnitTest")
@DisplayName("Hardware Encoder Failure Range Tests")
class HardwareEncoderFailureRangeTest {

  private static final StreamingProperties PROPERTIES =
      StreamingProperties.builder()
          .maxConcurrentTranscodes(3)
          .targetSegmentDuration(Duration.ofSeconds(6))
          .sessionTimeout(Duration.ofSeconds(60))
          .build();
  private static final Set<String> ENCODED_CODEC_FAMILIES = Set.of("h264", "av1");
  private static final Set<VideoEncodeFeature> EVERY_ENCODE_FEATURE =
      Set.of(VideoEncodeFeature.values());
  private static final Set<VideoEncodeFeature> NO_ENCODE_FEATURE = Set.of();
  private static final Set<VideoEncodeFeature> TEN_BIT_AND_PQ =
      Set.of(VideoEncodeFeature.TEN_BIT, VideoEncodeFeature.PQ);
  private static final ExecutionTargetId FAILING_WORKER = new ExecutionTargetId("failing");
  private static final ExecutionTargetId OTHER_WORKER = new ExecutionTargetId("other");

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeTranscodeExecutor workers = new FakeTranscodeExecutor();
  private final FakeMediaFileContainerInfoRepository probes =
      new FakeMediaFileContainerInfoRepository();
  private final CapturingEventPublisher deliveryEvents = new CapturingEventPublisher();
  private HlsStreamingService streaming;
  private SegmentDeliveryCoordinator coordinator;
  private PlaybackSessionService playback;
  private StartPlaybackSessionCommand command;
  private List<WorkerVerifiedEncoder> softwareEncodersOfTheFailingWorker;

  @BeforeEach
  void setUp() {
    workers.setEncodableVideoCodecFamilies(ENCODED_CODEC_FAMILIES);
    workers.setVerifiedVideoCodecFamiliesByWorker(
        List.of(
            WorkerVerifiedCodecFamilies.builder()
                .workerId(UUID.randomUUID())
                .codecFamilies(ENCODED_CODEC_FAMILIES)
                .build()));
    workers.setExecutionTargets(List.of(FAILING_WORKER));
    streaming = streaming();
    playback = playbackWithFormatAttempts(4);
  }

  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("sourcesInAutoAndInAFixedQuality")
  @DisplayName(
      "Should continue in the failed encode's range and bit depth on the software encoder when the"
          + " hardware encoder fails and the software encoder writes them")
  void shouldContinueInTheFailedEncodesRangeAndBitDepthWhenTheSoftwareEncoderWritesThem(
      MediaProbe source, StreamingOptions options) {
    connectTheFailingWorker(EVERY_ENCODE_FEATURE);
    probes.setDefaultProbe(source);
    var hardware = accepted(playback.start(start(options)));
    var hardwarePlan = plannedEncode();
    assertThat(hardwarePlan.bitDepth()).isEqualTo(10);

    var software = failTheHardwareEncoderOf(hardware);

    assertThat(lastStartedDecision().softwareEncoder()).isTrue();
    assertThat(plannedEncode()).isEqualTo(hardwarePlan);
    assertThat(sessionOf(software).getVideoRange()).isEqualTo(sessionOf(hardware).getVideoRange());
    assertThat(software.formatAttemptCount()).isEqualTo(1);
    assertThat(software.playbackChoice()).isEqualTo(options.quality());
    assertThat(software.formatChange()).isEmpty();
    assertThat(software.decisions().getLast().reasons())
        .startsWith(DecisionReason.HARDWARE_ENCODER_FAILED);
  }

  static Stream<Arguments> sourcesInAutoAndInAFixedQuality() {
    return Stream.of(
            Named.of("an HDR10 source", sourceOf(CompatibleBase.PQ)),
            Named.of("an HLG source", sourceOf(CompatibleBase.HLG)),
            Named.of("a 10-bit SDR source", sourceOf(CompatibleBase.SDR)),
            Named.of("a Dolby Vision profile 5 source", profile5Source()))
        .flatMap(
            source ->
                Stream.of(
                    Arguments.of(source, Named.of("Auto", options(VideoQuality.AUTO, null))),
                    Arguments.of(
                        source, Named.of("1080p", options(VideoQuality.FULL_HD_1080P, null)))));
  }

  // A fixed quality is a manual choice, which the server never replaces silently, and SDR is better
  // than no video: the encode that cannot continue in its HDR range counts as failed, the next
  // format attempt plays 8-bit SDR in the same fixed quality, and the answer names the change (ADR
  // 0013).
  @ParameterizedTest(name = "{0}")
  @MethodSource("fixedQualitiesWhoseSoftwareEncoderCannotWriteTheHdrRange")
  @DisplayName(
      "Should play 8-bit SDR and name HDR_NOT_KEPT when the hardware encoder of a fixed quality"
          + " fails and no software encoder writes the encode's HDR range")
  void shouldPlay8BitSdrAndNameHdrNotKeptWhenAFixedQualitysSoftwareEncoderCannotWriteItsRange(
      MediaProbe source, Set<VideoEncodeFeature> softwareFeatures, StreamingOptions options) {
    connectTheFailingWorker(softwareFeatures);
    probes.setDefaultProbe(source);
    var hardware = accepted(playback.start(start(options)));

    var sdr = failTheHardwareEncoderOf(hardware);

    assertThat(plannedEncode().bitDepth()).isEqualTo(8);
    assertThat(plannedEncode().dynamicRange()).isEqualTo(VideoDynamicRange.SDR);
    assertThat(sessionOf(sdr).getVideoRange()).isEqualTo(VideoRange.SDR);
    assertThat(sdr.formatAttemptCount()).isEqualTo(2);
    assertThat(sdr.playbackChoice()).isEqualTo(options.quality());
    assertThat(sdr.formatChange()).contains(PlaybackFormatChange.HDR_NOT_KEPT);
    assertThat(sdr.decisions()).extracting(PlaybackDecision::formatAttempt).containsExactly(1, 2);
    assertThat(sdr.decisions().getLast().reasons()).startsWith(DecisionReason.ENCODE_FAILED);
  }

  static Stream<Arguments> fixedQualitiesWhoseSoftwareEncoderCannotWriteTheHdrRange() {
    var hd = VideoQuality.FULL_HD_1080P;
    return Stream.of(
        Arguments.of(
            Named.of(
                "an HDR10 source with the HDR choice, a software encoder that writes 8-bit SDR",
                sourceOf(CompatibleBase.PQ)),
            NO_ENCODE_FEATURE,
            options(hd, DynamicRangeChoice.HDR)),
        Arguments.of(
            Named.of(
                "an HDR10 source without a choice, a software encoder that writes 8-bit SDR",
                sourceOf(CompatibleBase.PQ)),
            NO_ENCODE_FEATURE,
            options(hd, null)),
        Arguments.of(
            Named.of(
                "an HLG source, a software encoder that writes PQ but not HLG",
                sourceOf(CompatibleBase.HLG)),
            TEN_BIT_AND_PQ,
            options(hd, DynamicRangeChoice.HDR)),
        Arguments.of(
            Named.of(
                "a Dolby Vision profile 5 source, a software encoder that writes PQ without the"
                    + " reshape",
                profile5Source()),
            TEN_BIT_AND_PQ,
            options(hd, null)));
  }

  // The capacity error stays for a fixed quality when not even an 8-bit SDR encode can start.
  @Test
  @DisplayName(
      "Should end a fixed quality's playback for lack of capacity when its PQ encode cannot"
          + " continue in software and no worker has a free slot for an SDR encode")
  void shouldEndAFixedQualitysPlaybackForLackOfCapacityWhenNoWorkerHasASlotForAnSdrEncode() {
    connectTheFailingWorker(NO_ENCODE_FEATURE);
    probes.setDefaultProbe(sourceOf(CompatibleBase.PQ));
    var hardware =
        accepted(
            playback.start(start(options(VideoQuality.FULL_HD_1080P, DynamicRangeChoice.HDR))));
    workers.limitJobSlots(FAILING_WORKER, 0);

    var failed = failTheHardwareEncoderOf(hardware);

    assertThat(failed.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            attempt ->
                assertThat(attempt.reason())
                    .isEqualTo(
                        new PlaybackSessionFailure.CreationRejected(
                            new CreateStreamSessionRejection.TranscodeCapacityUnavailable(
                                PROPERTIES.maxConcurrentTranscodes()))));
    assertThat(workers.getStartedRequests()).hasSize(1);
  }

  // In Auto an SDR picture that plays is better than an error, so the encode that cannot continue
  // in its range counts as failed: the next format attempt is 8-bit SDR and spends the budget, as
  // after a format error during a PQ or HLG encode (ADR 0013). The viewer sees the lost HDR range,
  // so Auto names it.
  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesWhoseSoftwareEncoderCannotWriteTheFormat")
  @DisplayName(
      "Should play 8-bit SDR in a format attempt that counts against the budget when the hardware"
          + " encoder fails in Auto and no software encoder writes the encode's PQ or HLG range")
  void shouldPlay8BitSdrInACountedFormatAttemptWhenAutosSoftwareEncoderCannotWriteTheFormat(
      MediaProbe source, Set<VideoEncodeFeature> softwareFeatures) {
    connectTheFailingWorker(softwareFeatures);
    probes.setDefaultProbe(source);
    var hardware = accepted(playback.start(start(options(VideoQuality.AUTO, null))));

    var sdr = failTheHardwareEncoderOf(hardware);

    assertThat(plannedEncode().bitDepth()).isEqualTo(8);
    assertThat(plannedEncode().dynamicRange()).isEqualTo(VideoDynamicRange.SDR);
    assertThat(sessionOf(sdr).getVideoRange()).isEqualTo(VideoRange.SDR);
    assertThat(multivariantPlaylist(sdr)).doesNotContain("VIDEO-RANGE=");
    assertThat(sdr.formatAttemptCount()).isEqualTo(2);
    assertThat(sdr.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(sdr.formatChange()).contains(PlaybackFormatChange.HDR_NOT_KEPT);
    assertThat(sdr.decisions()).extracting(PlaybackDecision::formatAttempt).containsExactly(1, 2);
    assertThat(sdr.decisions().getLast().reasons()).startsWith(DecisionReason.ENCODE_FAILED);
  }

  static Stream<Arguments> sourcesWhoseSoftwareEncoderCannotWriteTheFormat() {
    return Stream.of(
        Arguments.of(
            Named.of(
                "an HDR10 source, a software encoder that writes 8-bit SDR",
                sourceOf(CompatibleBase.PQ)),
            NO_ENCODE_FEATURE),
        Arguments.of(
            Named.of(
                "an HLG source, a software encoder that writes PQ but not HLG",
                sourceOf(CompatibleBase.HLG)),
            TEN_BIT_AND_PQ),
        Arguments.of(
            Named.of(
                "a Dolby Vision profile 5 source, a software encoder that writes PQ without the"
                    + " reshape",
                profile5Source()),
            TEN_BIT_AND_PQ));
  }

  // 8-bit SDR keeps the range of a 10-bit SDR encode, so the encode continues in 8 bits as a
  // software continuation does: in Auto and in a fixed quality, and without spending a format
  // attempt (ADR 0013).
  @ParameterizedTest(name = "in {0}")
  @MethodSource("autoAndAFixedQuality")
  @DisplayName(
      "Should continue in 8-bit SDR on the software encoder when the hardware encoder of a 10-bit"
          + " SDR encode fails and no software encoder writes 10 bits")
  void shouldContinueIn8BitSdrWhenNoSoftwareEncoderWritesThe10BitsOfA10BitSdrEncode(
      StreamingOptions options) {
    connectTheFailingWorker(NO_ENCODE_FEATURE);
    probes.setDefaultProbe(sourceOf(CompatibleBase.SDR));
    var hardware = accepted(playback.start(start(options)));
    var hardwarePlan = plannedEncode();
    assertThat(hardwarePlan.bitDepth()).isEqualTo(10);

    var software = failTheHardwareEncoderOf(hardware);

    assertThat(lastStartedDecision().softwareEncoder()).isTrue();
    assertThat(plannedEncode().codecProfile()).isEqualTo(hardwarePlan.codecProfile());
    assertThat(plannedEncode().bitDepth()).isEqualTo(8);
    assertThat(plannedEncode().dynamicRange()).isEqualTo(VideoDynamicRange.SDR);
    assertThat(sessionOf(software).getVideoRange()).isEqualTo(VideoRange.SDR);
    assertThat(software.formatAttemptCount()).isEqualTo(1);
    assertThat(software.playbackChoice()).isEqualTo(options.quality());
    assertThat(software.formatChange()).isEmpty();
    assertThat(software.decisions().getLast().reasons())
        .startsWith(DecisionReason.HARDWARE_ENCODER_FAILED);
  }

  static Stream<Arguments> autoAndAFixedQuality() {
    return Stream.of(
        Arguments.of(Named.of("Auto", options(VideoQuality.AUTO, null))),
        Arguments.of(Named.of("1080p", options(VideoQuality.FULL_HD_1080P, null))));
  }

  // Only a software encoder on a worker that can take the encode now keeps the 10 bits.
  @Test
  @DisplayName(
      "Should continue in 8-bit SDR on the failed worker in a fixed quality when the only software"
          + " encoder that writes 10 bits runs on a worker without a free slot")
  void shouldContinueIn8BitSdrOnTheFailedWorkerWhenThe10BitSoftwareEncodersWorkerIsFull() {
    connectTheFailingWorker(NO_ENCODE_FEATURE);
    connectAnotherWorker(EVERY_ENCODE_FEATURE);
    workers.limitJobSlots(OTHER_WORKER, 0);
    probes.setDefaultProbe(sourceOf(CompatibleBase.SDR));
    var hardware = accepted(playback.start(start(options(VideoQuality.FULL_HD_1080P, null))));
    assertThat(plannedEncode().bitDepth()).isEqualTo(10);

    var software = failTheHardwareEncoderOf(hardware);

    assertThat(lastStartedDecision().softwareEncoder()).isTrue();
    assertThat(plannedEncode().bitDepth()).isEqualTo(8);
    assertThat(workerOfTheStartedEncode()).contains(FAILING_WORKER);
    assertThat(sessionOf(software).getVideoRange()).isEqualTo(VideoRange.SDR);
    assertThat(software.formatAttemptCount()).isEqualTo(1);
  }

  // The range that Auto gave up stays out of every later format attempt of the playback session,
  // as after a format error (ADR 0013), also once the worker's encoders write it again. The
  // hardware encoder that failed stays out of them too.
  @Test
  @DisplayName(
      "Should keep 8-bit SDR on the software encoder in a later format attempt when the worker"
          + " offers its hardware encoder again and both of its AV1 encoders write PQ after Auto"
          + " gave HDR up")
  void shouldKeep8BitSdrOnTheSoftwareEncoderInALaterFormatAttemptWhenTheWorkerWritesPqAgain() {
    connectTheFailingWorker(NO_ENCODE_FEATURE);
    probes.setDefaultProbe(sourceOf(CompatibleBase.PQ));
    var hardware = accepted(playback.start(start(options(VideoQuality.AUTO, null))));
    var h264 = failTheHardwareEncoderOf(hardware);
    assertThat(plannedEncode().codecProfile()).contains(EncodeCodecProfile.H264_HIGH);
    workers.setTargetEncoders(FAILING_WORKER, List.of(av1Nvenc(EVERY_ENCODE_FEATURE), libx264()));
    workers.setTargetSoftwareEncoders(
        FAILING_WORKER, List.of(svtAv1(EVERY_ENCODE_FEATURE), libx264()));

    var av1 = rejectCurrentFormatAttempt(h264);

    assertThat(plannedEncode().codecProfile()).contains(EncodeCodecProfile.AV1_MAIN);
    assertThat(plannedEncode().bitDepth()).isEqualTo(8);
    assertThat(plannedEncode().dynamicRange()).isEqualTo(VideoDynamicRange.SDR);
    assertThat(lastStartedDecision().softwareEncoder()).isTrue();
    assertThat(av1.formatAttemptCount()).isEqualTo(3);
  }

  // A worker keeps offering a hardware encoder that refused an encode at its driver's session
  // limit. Auto's next format attempt leaves that encoder out, so the driver cannot refuse it
  // again, and keeps the codec family on its software encoder.
  @ParameterizedTest(name = "{0}")
  @MethodSource("clientsAndTheEncoderOfAutosNextFormatAttempt")
  @DisplayName(
      "Should plan Auto's next format attempt off a hardware encoder when it refused a PQ encode"
          + " that no software encoder writes")
  void
      shouldPlanAutosNextFormatAttemptOffAHardwareEncoderWhenItRefusedAPqEncodeThatNoSoftwareEncoderWrites(
          ClientCapabilityDeclaration client, WorkerVerifiedEncoder expectedEncoder) {
    connectTheFailingWorker(NO_ENCODE_FEATURE);
    probes.setDefaultProbe(sourceOf(CompatibleBase.PQ));
    var hardware = accepted(playback.start(start(options(VideoQuality.AUTO, null), client)));

    var sdr = refuseTheHardwareEncoderOf(hardware);

    assertThat(encoderOfTheStartedEncode(sdr)).contains(expectedEncoder);
    assertThat(plannedEncode().bitDepth()).isEqualTo(8);
    assertThat(plannedEncode().dynamicRange()).isEqualTo(VideoDynamicRange.SDR);
    assertThat(sdr.formatAttemptCount()).isEqualTo(2);
    assertThat(workers.getStartedRequests()).hasSize(2);
  }

  static Stream<Arguments> clientsAndTheEncoderOfAutosNextFormatAttempt() {
    var av1Only =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    av1EntryBuilder()
                        .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                        .build()))
            .build();
    return Stream.of(
        Arguments.of(
            Named.of("a client that declares H.264 beside AV1", hdrAv1Client()), libx264()),
        Arguments.of(
            Named.of("a client that declares AV1 alone", av1Only), svtAv1(NO_ENCODE_FEATURE)));
  }

  // A worker runs a software encode only on a software encoder of its codec family (ADR 0044), so
  // without one the codec family whose hardware encoder refused has no encode left, and Auto plays
  // the client's other codec family, although the client declares it at a lower frame rate.
  @Test
  @DisplayName(
      "Should play H.264 in Auto's next format attempt when the hardware AV1 encoder refused a PQ"
          + " encode and no worker has a software AV1 encoder")
  void
      shouldPlayH264InAutosNextFormatAttemptWhenTheHardwareAv1EncoderRefusedAndNoWorkerHasSoftwareAv1() {
    workers.setTargetEncoders(FAILING_WORKER, List.of(av1Nvenc(EVERY_ENCODE_FEATURE), libx264()));
    workers.setTargetSoftwareEncoders(FAILING_WORKER, List.of(libx264()));
    probes.setDefaultProbe(sourceOf(CompatibleBase.PQ));
    var h264AtHalfTheFrameRate =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    av1EntryBuilder()
                        .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                        .build(),
                    h264EntryBuilder().maxFrameRate(12).build()))
            .build();
    var hardware =
        accepted(playback.start(start(options(VideoQuality.AUTO, null), h264AtHalfTheFrameRate)));
    assertThat(plannedEncode().codecProfile()).contains(EncodeCodecProfile.AV1_MAIN);

    var h264 = refuseTheHardwareEncoderOf(hardware);

    assertThat(encoderOfTheStartedEncode(h264)).contains(libx264());
    assertThat(plannedEncode().codecProfile()).contains(EncodeCodecProfile.H264_HIGH);
    assertThat(h264.formatAttemptCount()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should end playback for its spent budget when the hardware encoder fails in Auto's last"
          + " format attempt and no software encoder writes PQ")
  void shouldEndPlaybackForItsSpentBudgetWhenAutosLastFormatAttemptCannotContinueInPq() {
    playback = playbackWithFormatAttempts(1);
    connectTheFailingWorker(NO_ENCODE_FEATURE);
    probes.setDefaultProbe(sourceOf(CompatibleBase.PQ));
    var hardware = accepted(playback.start(start(options(VideoQuality.AUTO, null))));

    var failed = failTheHardwareEncoderOf(hardware);

    assertThat(failed.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            attempt ->
                assertThat(attempt.reason())
                    .isEqualTo(new PlaybackSessionFailure.AttemptsExhausted()));
    assertThat(workers.getStartedRequests()).hasSize(1);
  }

  // ADR 0044: the software encode runs on any worker with room, the failed worker first.
  @Test
  @DisplayName(
      "Should continue in HDR10 on another worker's software encoder when the failed worker's"
          + " software encoder writes only 8-bit SDR")
  void shouldContinueInHdr10OnAnotherWorkersSoftwareEncoderWhenTheFailedWorkersWritesOnlySdr() {
    connectTheFailingWorker(NO_ENCODE_FEATURE);
    connectAnotherWorker(EVERY_ENCODE_FEATURE);
    probes.setDefaultProbe(sourceOf(CompatibleBase.PQ));
    var hardware =
        accepted(
            playback.start(start(options(VideoQuality.FULL_HD_1080P, DynamicRangeChoice.HDR))));
    var hardwarePlan = plannedEncode();

    var software = failTheHardwareEncoderOf(hardware);

    assertThat(lastStartedDecision().softwareEncoder()).isTrue();
    assertThat(plannedEncode()).isEqualTo(hardwarePlan);
    assertThat(workerOfTheStartedEncode()).contains(OTHER_WORKER);
    assertThat(sessionOf(software).getVideoRange()).isEqualTo(VideoRange.PQ);
  }

  // Only a software encoder on a worker that can take the encode now keeps the range.
  @ParameterizedTest(name = "{0}")
  @MethodSource("autoAndAFixedQualityWithTheHdrChoice")
  @DisplayName(
      "Should play 8-bit SDR on the failed worker when the only software encoder that writes PQ"
          + " runs on a worker without a free slot")
  void shouldPlay8BitSdrOnTheFailedWorkerWhenThePqSoftwareEncodersWorkerIsFull(
      StreamingOptions options) {
    connectTheFailingWorker(NO_ENCODE_FEATURE);
    connectAnotherWorker(EVERY_ENCODE_FEATURE);
    workers.limitJobSlots(OTHER_WORKER, 0);
    probes.setDefaultProbe(sourceOf(CompatibleBase.PQ));
    var hardware = accepted(playback.start(start(options)));

    var sdr = failTheHardwareEncoderOf(hardware);

    assertThat(plannedEncode().dynamicRange()).isEqualTo(VideoDynamicRange.SDR);
    assertThat(plannedEncode().bitDepth()).isEqualTo(8);
    assertThat(workerOfTheStartedEncode()).contains(FAILING_WORKER);
    assertThat(sdr.formatAttemptCount()).isEqualTo(2);
    assertThat(sdr.formatChange()).contains(PlaybackFormatChange.HDR_NOT_KEPT);
  }

  static Stream<Named<StreamingOptions>> autoAndAFixedQualityWithTheHdrChoice() {
    return Stream.of(
        Named.of("Auto", options(VideoQuality.AUTO, null)),
        Named.of(
            "1080p with the HDR choice",
            options(VideoQuality.FULL_HD_1080P, DynamicRangeChoice.HDR)));
  }

  // ADR 0044: with one worker, the software encode runs on that worker. The failed job attempt has
  // ended, so its slot holds the software encode, which takes one slot as any encode does.
  @Test
  @DisplayName(
      "Should continue in HDR10 in the failed worker's only slot when its hardware encoder fails"
          + " and its software encoder writes PQ")
  void shouldContinueInHdr10InTheFailedWorkersOnlySlotWhenItsSoftwareEncoderWritesPq() {
    connectTheFailingWorker(EVERY_ENCODE_FEATURE);
    workers.limitJobSlots(FAILING_WORKER, 1);
    probes.setDefaultProbe(sourceOf(CompatibleBase.PQ));
    var hardware = accepted(playback.start(start(options(VideoQuality.AUTO, null))));

    var software = failTheHardwareEncoderOf(hardware);

    assertThat(sessionOf(software).getVideoRange()).isEqualTo(VideoRange.PQ);
    assertThat(workerOfTheStartedEncode()).contains(FAILING_WORKER);
    assertThat(workers.getRunningCount()).isEqualTo(1);
    assertThat(rejectionOf(playback.start(start(options(VideoQuality.AUTO, null)))))
        .isInstanceOf(CreateStreamSessionRejection.TranscodeCapacityUnavailable.class);
  }

  // The continuation keeps the range in both directions: an encode that played 8-bit SDR, because
  // the hardware encoder writes nothing else, does not turn into HDR10 during the stream.
  @Test
  @DisplayName(
      "Should continue in 8-bit SDR on the software encoder when the hardware encoder of an 8-bit"
          + " SDR encode of an HDR10 source fails and the software encoder writes PQ")
  void shouldContinueIn8BitSdrWhenTheHardwareEncoderOfAnSdrEncodeFailsAndTheSoftwareWritesPq() {
    connectTheFailingWorker(EVERY_ENCODE_FEATURE);
    workers.setTargetEncoders(FAILING_WORKER, List.of(av1Nvenc(NO_ENCODE_FEATURE), libx264()));
    probes.setDefaultProbe(sourceOf(CompatibleBase.PQ));
    var hardware = accepted(playback.start(start(options(VideoQuality.AUTO, null))));
    var hardwarePlan = plannedEncode();
    assertThat(hardwarePlan.codecProfile()).contains(EncodeCodecProfile.AV1_MAIN);
    assertThat(hardwarePlan.dynamicRange()).isEqualTo(VideoDynamicRange.SDR);

    var software = failTheHardwareEncoderOf(hardware);

    assertThat(lastStartedDecision().softwareEncoder()).isTrue();
    assertThat(plannedEncode()).isEqualTo(hardwarePlan);
    assertThat(sessionOf(software).getVideoRange()).isEqualTo(VideoRange.SDR);
    assertThat(software.formatAttemptCount()).isEqualTo(1);
  }

  // ADR 0044: an 8-bit SDR encode continues on the software encoder of its codec family or the
  // stream session fails. It has no range to give up, so Auto encodes no other codec family.
  @Test
  @DisplayName(
      "Should end playback for lack of capacity without encoding another codec family when the"
          + " hardware encoder of an 8-bit SDR encode fails in Auto and its worker has no free"
          + " slot")
  void shouldEndPlaybackWithoutAnotherCodecFamilyWhenAn8BitSdrEncodesWorkerHasNoFreeSlot() {
    connectTheFailingWorker(NO_ENCODE_FEATURE);
    workers.setTargetEncoders(FAILING_WORKER, List.of(av1Nvenc(NO_ENCODE_FEATURE), libx264()));
    workers.setExecutionTargets(List.of(FAILING_WORKER, OTHER_WORKER));
    workers.setTargetEncoders(OTHER_WORKER, List.of(libx264()));
    workers.setTargetSoftwareEncoders(OTHER_WORKER, List.of(libx264()));
    probes.setDefaultProbe(sourceOf(CompatibleBase.PQ));
    var hardware = accepted(playback.start(start(options(VideoQuality.AUTO, null))));
    assertThat(plannedEncode().codecProfile()).contains(EncodeCodecProfile.AV1_MAIN);
    assertThat(plannedEncode().bitDepth()).isEqualTo(8);
    workers.limitJobSlots(FAILING_WORKER, 0);

    var failed = failTheHardwareEncoderOf(hardware);

    assertThat(failed.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            attempt ->
                assertThat(attempt.reason())
                    .isEqualTo(
                        new PlaybackSessionFailure.CreationRejected(
                            new CreateStreamSessionRejection.TranscodeCapacityUnavailable(
                                PROPERTIES.maxConcurrentTranscodes()))));
    assertThat(failed.formatAttemptCount()).isEqualTo(1);
    assertThat(workers.getStartedRequests()).hasSize(1);
  }

  // The worker stops offering its hardware encoder and fails the job attempt. The player's segment
  // request then ends as unrecoverable, and the encode continues in software (ADR 0044).
  private PlaybackSessionSnapshot failTheHardwareEncoderOf(PlaybackSessionSnapshot playing) {
    workers.setTargetEncoders(FAILING_WORKER, softwareEncodersOfTheFailingWorker);
    return refuseTheHardwareEncoderOf(playing);
  }

  // The hardware encoder fails the job attempt and the worker keeps offering it, as after a refusal
  // at the driver's session limit.
  private PlaybackSessionSnapshot refuseTheHardwareEncoderOf(PlaybackSessionSnapshot playing) {
    var job = workers.getStartedRequests().getLast();
    workers.failHardwareEncoder(job.sessionId(), job.variantLabel());

    var delivery =
        coordinator.deliver(
            job.sessionId(),
            job.variantLabel(),
            SegmentNames.storedSegmentName(job.variantLabel(), SegmentNames.mediaSegmentName(0)));

    assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    deliveryEvents
        .getEventsOfType(RequestedVariantHardwareEncoderFailedEvent.class)
        .forEach(playback::onRequestedVariantHardwareEncoderFailed);
    return playback.access(request(playing)).orElseThrow();
  }

  private PlaybackSessionSnapshot rejectCurrentFormatAttempt(PlaybackSessionSnapshot snapshot) {
    return playback
        .reportFormatFailure(
            ReportPlaybackFormatFailureCommand.builder()
                .playbackSessionId(snapshot.playbackSessionId())
                .streamSessionId(snapshot.currentFormatAttempt().streamSessionId())
                .identity(command.identity())
                .reason(PlaybackFormatFailure.DECODE_ERROR)
                .build())
        .orElseThrow();
  }

  private PlaybackSessionRequest request(PlaybackSessionSnapshot snapshot) {
    return PlaybackSessionRequest.builder()
        .playbackSessionId(snapshot.playbackSessionId())
        .identity(command.identity())
        .build();
  }

  private TranscodeDecision lastStartedDecision() {
    return workers.getStartedRequests().getLast().transcodeDecision();
  }

  private PlannedVideoOutput.Encode plannedEncode() {
    var planned = lastStartedDecision().plannedVideoOutput();
    assertThat(planned).isInstanceOf(PlannedVideoOutput.Encode.class);
    return (PlannedVideoOutput.Encode) planned;
  }

  private Optional<WorkerVerifiedEncoder> encoderOfTheStartedEncode(
      PlaybackSessionSnapshot snapshot) {
    var started = workers.getStartedRequests().getLast();
    return sessionOf(snapshot).getPinnedEncoder(started.variantLabel());
  }

  private Optional<ExecutionTargetId> workerOfTheStartedEncode() {
    var started = workers.getStartedRequests().getLast();
    return workers.runningExecutionTarget(started.sessionId(), started.variantLabel());
  }

  private static StreamSession sessionOf(PlaybackSessionSnapshot snapshot) {
    if (snapshot.currentFormatAttempt() instanceof PlaybackFormatAttempt.Ready(var session)) {
      return session;
    }

    throw new AssertionError("Expected a ready format attempt: " + snapshot.currentFormatAttempt());
  }

  private static String multivariantPlaylist(PlaybackSessionSnapshot snapshot) {
    return new HlsPlaylistService(PROPERTIES)
        .generateMultivariantPlaylist(sessionOf(snapshot), "t");
  }

  private static StreamingOptions options(VideoQuality quality, DynamicRangeChoice choice) {
    return StreamingOptions.builder().quality(quality).dynamicRange(choice).build();
  }

  // The client declares AV1 with SDR, PQ and HLG at 10 bits beside H.264 SDR and stereo AAC. It
  // declares no HEVC, so no HEVC source is copied.
  private StartPlaybackSessionCommand start(StreamingOptions options) {
    return start(options, hdrAv1Client());
  }

  private StartPlaybackSessionCommand start(
      StreamingOptions options, ClientCapabilityDeclaration client) {
    var file =
        mediaFiles.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/hdr.mkv")
                .filename("hdr.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
    command =
        StartPlaybackSessionCommand.builder()
            .mediaFileId(file.getId())
            .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
            .options(options)
            .capabilityDeclaration(client)
            .build();
    return command;
  }

  private static ClientCapabilityDeclaration hdrAv1Client() {
    var av1 =
        av1EntryBuilder()
            .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
            .build();
    return defaultDeclarationBuilder().video(List.of(av1, h264EntryBuilder().build())).build();
  }

  // The worker's hardware AV1 encoder writes every 10-bit and HDR output. After that encoder fails,
  // the worker offers its software AV1 encoder, which lists these encode features.
  private void connectTheFailingWorker(Set<VideoEncodeFeature> softwareAv1Features) {
    softwareEncodersOfTheFailingWorker = List.of(svtAv1(softwareAv1Features), libx264());
    workers.setTargetEncoders(FAILING_WORKER, List.of(av1Nvenc(EVERY_ENCODE_FEATURE), libx264()));
    workers.setTargetSoftwareEncoders(FAILING_WORKER, softwareEncodersOfTheFailingWorker);
  }

  // A second worker without a hardware encoder, whose software AV1 encoder lists these features.
  private void connectAnotherWorker(Set<VideoEncodeFeature> softwareAv1Features) {
    var encoders = List.of(svtAv1(softwareAv1Features), libx264());
    workers.setExecutionTargets(List.of(FAILING_WORKER, OTHER_WORKER));
    workers.setTargetEncoders(OTHER_WORKER, encoders);
    workers.setTargetSoftwareEncoders(OTHER_WORKER, encoders);
  }

  private PlaybackSessionService playbackWithFormatAttempts(int maximumFormatAttempts) {
    return PlaybackSessionService.builder()
        .eventPublisher(_ -> {})
        .streamingService(streaming)
        .authorityGate(new FakePlaybackAuthorityGate())
        .executor(Runnable::run)
        .maximumFormatAttempts(maximumFormatAttempts)
        .build();
  }

  private HlsStreamingService streaming() {
    var segments = new FakeSegmentStore();
    var registry = new FakeRuntimeStreamSessionRegistry();
    var meters = new SimpleMeterRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(workers)
            .segmentStore(segments)
            .properties(PROPERTIES)
            .runtimeRegistry(registry)
            .heldRequests(workers.heldSegmentRequests())
            .eventPublisher(deliveryEvents)
            .build();
    coordinator = rig.coordinator();
    return HlsStreamingService.builder()
        .languagePreferences(noLanguagePreferences())
        .mediaFileRepository(mediaFiles)
        .transcodeExecutor(workers)
        .segmentStore(segments)
        .playbackProbeService(playbackProbeServiceBuilder(probes).build())
        .transcodeDecisionService(new TranscodeDecisionService(PROPERTIES, meters))
        .qualityLadderService(new QualityLadderService())
        .properties(PROPERTIES)
        .authorityGate(new FakePlaybackAuthorityGate())
        .runtimeRegistry(registry)
        .producerLifecycle(rig.lifecycle())
        .deliveryCoordinator(rig.coordinator())
        .meterRegistry(meters)
        .build();
  }

  private static WorkerVerifiedEncoder svtAv1(Set<VideoEncodeFeature> features) {
    return encoder(backend("av1", "libsvtav1").kind(EncoderBackend.Kind.SOFTWARE), features);
  }

  private static WorkerVerifiedEncoder av1Nvenc(Set<VideoEncodeFeature> features) {
    return encoder(backend("av1", "av1_nvenc").kind(EncoderBackend.Kind.HARDWARE), features);
  }

  private static WorkerVerifiedEncoder libx264() {
    return encoder(
        backend("h264", "libx264").kind(EncoderBackend.Kind.SOFTWARE), NO_ENCODE_FEATURE);
  }

  private static EncoderBackend.EncoderBackendBuilder backend(String codecFamily, String encoder) {
    return EncoderBackend.builder().codecFamily(codecFamily).encoder(encoder);
  }

  private static WorkerVerifiedEncoder encoder(
      EncoderBackend.EncoderBackendBuilder backend, Set<VideoEncodeFeature> features) {
    return WorkerVerifiedEncoder.builder()
        .backend(backend.build())
        .keyframeVerified(true)
        .encodeFeatures(features)
        .build();
  }

  // A 1080p HEVC Main 10 source at 24 fps in the compatible base, beside stereo AAC.
  private static MediaProbe sourceOf(CompatibleBase base) {
    return source(new VideoDynamicRange(base, Presence.ABSENT, new DolbyVision.Absent()));
  }

  // Dolby Vision profile 5 has no compatible base, so a PQ encode also needs its RPUs reshaped.
  private static MediaProbe profile5Source() {
    var profile5 =
        DolbyVision.Present.builder()
            .declared(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(DolbyVisionProfile.PROFILE_5.profileNumber()))
                        .level(OptionalInt.of(6))
                        .baseLayerCompatibilityId(OptionalInt.of(0))
                        .baseLayer(true)
                        .rpu(true)
                        .build()))
            .observedRpu(Presence.PRESENT)
            .build();
    return source(new VideoDynamicRange(CompatibleBase.NONE, Presence.ABSENT, profile5));
  }

  private static MediaProbe source(VideoDynamicRange range) {
    var facts =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .streamIndex(0)
            .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
            .codecFamily(Optional.of(ProbedCodecFamily.HEVC))
            .bitDepth(OptionalInt.of(10))
            .codecString(Optional.of("hvc1.2.4.L150.90"))
            .videoProperties(
                Optional.of(
                    VideoStreamProperties.builder()
                        .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
                        .codecLevel(OptionalInt.of(40))
                        .codecTier(Optional.of(VideoCodecTier.MAIN))
                        .firstFramesRead(true)
                        .build()))
            .dynamicRange(range)
            .build();
    return defaultProbeBuilder().videoCodec("hevc").videoPlaybackFacts(Optional.of(facts)).build();
  }
}
