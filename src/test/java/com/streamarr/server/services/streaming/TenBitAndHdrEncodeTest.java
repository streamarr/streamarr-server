package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ChromaticityCoordinates;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DynamicRangeChoice;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MasteringDisplayColorVolume;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.NetworkRoute;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoEncodeFeature;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.UploadBudgetFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.services.streaming.events.RequestedVariantHardwareEncoderFailedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 10-bit and HDR encodes through the real transcode decision and playlist (#397). An HEVC or AV1
 * encode keeps a PQ or HLG source's range when the client declares that range, the playback choice
 * permits it and a worker's encoder of that codec family writes it. Dolby Vision profiles 5 and
 * 10.0 encode to HDR10 for a client that declares PQ. A PQ encode carries the source's static HDR
 * metadata, or a P3 D65 display of 1000 and 0.0001 cd/m² and a content light level of 0, which
 * means unknown. H.264 output stays 8-bit SDR in every case.
 */
@Tag("UnitTest")
@DisplayName("10-Bit And HDR Encode Tests")
class TenBitAndHdrEncodeTest {

  private static final StreamingProperties PROPERTIES =
      StreamingProperties.builder()
          .maxConcurrentTranscodes(3)
          .targetSegmentDuration(Duration.ofSeconds(6))
          .sessionTimeout(Duration.ofSeconds(60))
          .build();
  private static final Set<String> ENCODED_CODEC_FAMILIES = Set.of("h264", "av1");
  private static final Set<VideoEncodeFeature> EVERY_ENCODE_FEATURE =
      Set.of(VideoEncodeFeature.values());

  // A BT.2020 display from 0.005 to 4000 cd/m², unlike the P3 D65 display of the fallback.
  private static final MasteringDisplayColorVolume SOURCE_DISPLAY =
      MasteringDisplayColorVolume.builder()
          .red(new ChromaticityCoordinates(35_400, 14_600))
          .green(new ChromaticityCoordinates(8_500, 39_850))
          .blue(new ChromaticityCoordinates(6_550, 2_300))
          .whitePoint(new ChromaticityCoordinates(15_635, 16_450))
          .maxLuminance(40_000_000)
          .minLuminance(50)
          .build();
  private static final ContentLightLevel SOURCE_LIGHT_LEVEL = new ContentLightLevel(1_200, 350);

  // Decision 173: P3 D65 primaries, 1000 cd/m² peak and 0.0001 cd/m² black, in HEVC SEI units.
  private static final MasteringDisplayColorVolume FALLBACK_DISPLAY =
      MasteringDisplayColorVolume.builder()
          .red(new ChromaticityCoordinates(34_000, 16_000))
          .green(new ChromaticityCoordinates(13_250, 34_500))
          .blue(new ChromaticityCoordinates(7_500, 3_000))
          .whitePoint(new ChromaticityCoordinates(15_635, 16_450))
          .maxLuminance(10_000_000)
          .minLuminance(1)
          .build();
  private static final ContentLightLevel UNKNOWN_LIGHT_LEVEL = new ContentLightLevel(0, 0);
  // Level 6 light levels unlike the source's own SEI values.
  private static final ContentLightLevel LEVEL_6 = new ContentLightLevel(1_000, 400);

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeTranscodeExecutor workers = new FakeTranscodeExecutor();
  private final FakeMediaFileContainerInfoRepository probes =
      new FakeMediaFileContainerInfoRepository();
  private PlaybackSessionService playback;

  @BeforeEach
  void setUp() {
    workers.setEncodableVideoCodecFamilies(ENCODED_CODEC_FAMILIES);
    workers.setVerifiedVideoCodecFamiliesByWorker(
        List.of(
            WorkerVerifiedCodecFamilies.builder()
                .workerId(UUID.randomUUID())
                .codecFamilies(ENCODED_CODEC_FAMILIES)
                .build()));
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(svtAv1(EVERY_ENCODE_FEATURE), libx264(Set.of())));
    playback =
        PlaybackSessionService.builder()
            .eventPublisher(_ -> {})
            .streamingService(streaming())
            .authorityGate(new FakePlaybackAuthorityGate())
            .executor(Runnable::run)
            .maximumFormatAttempts(4)
            .build();
  }

  // An HDR10 source that the client cannot copy, because it declares no HEVC, plays as an AV1
  // HDR10 encode in every playback choice that permits HDR. Original cannot copy, so its encode
  // plays in Auto (ADR 0052).
  @ParameterizedTest(name = "{0}")
  @MethodSource("playbackChoicesThatPermitHdr")
  @DisplayName(
      "Should encode an HDR10 source to 10-bit AV1 HDR10 with the source's static metadata when"
          + " the client declares PQ and the playback choice permits HDR")
  void shouldEncodeAnHdr10SourceTo10BitAv1Hdr10WithTheSourcesStaticMetadata(
      StreamingOptions options,
      VideoQuality expectedChoice,
      Optional<PlaybackFormatChange> change) {
    probes.setDefaultProbe(
        hdr10Source(Optional.of(SOURCE_DISPLAY), Optional.of(SOURCE_LIGHT_LEVEL)));

    var encode = accepted(playback.start(start(options, hdrAv1Client())));

    assertThat(encode.playbackChoice()).isEqualTo(expectedChoice);
    assertThat(encode.formatChange()).isEqualTo(change);
    assertThat(plannedEncode())
        .isEqualTo(
            PlannedVideoOutput.Encode.builder()
                .codecProfile(Optional.of(EncodeCodecProfile.AV1_MAIN))
                .dynamicRange(rangeOf(CompatibleBase.PQ))
                .bitDepth(10)
                .chromaSubsampling(ChromaSubsampling.CHROMA_420)
                .masteringDisplay(Optional.of(SOURCE_DISPLAY))
                .contentLightLevel(Optional.of(SOURCE_LIGHT_LEVEL))
                .build());
    assertThat(sessionOf(encode).getVideoRange()).isEqualTo(VideoRange.PQ);
    assertThat(multivariantPlaylist(encode))
        .containsPattern("CODECS=\"av01\\.0\\.\\d\\dM\\.10,mp4a\\.40\\.2\"")
        .contains(",VIDEO-RANGE=PQ");
  }

  static Stream<Arguments> playbackChoicesThatPermitHdr() {
    return Stream.of(
        Arguments.of(
            Named.of("Auto", options(VideoQuality.AUTO, null)),
            VideoQuality.AUTO,
            Optional.empty()),
        Arguments.of(
            Named.of("a fixed quality without a choice", options(VideoQuality.FULL_HD_1080P, null)),
            VideoQuality.FULL_HD_1080P,
            Optional.empty()),
        Arguments.of(
            Named.of(
                "a fixed quality with the HDR choice",
                options(VideoQuality.FULL_HD_1080P, DynamicRangeChoice.HDR)),
            VideoQuality.FULL_HD_1080P,
            Optional.empty()),
        Arguments.of(
            Named.of("Original", options(VideoQuality.ORIGINAL, null)),
            VideoQuality.AUTO,
            Optional.of(PlaybackFormatChange.STARTED_WITH_ENCODE)));
  }

  @Test
  @DisplayName(
      "Should give an HDR10 encode the fallback display and an unknown content light level when"
          + " the probe stored neither for the source")
  void
      shouldGiveAnHdr10EncodeTheFallbackDisplayAndAnUnknownContentLightLevelWhenTheProbeStoredNeither() {
    probes.setDefaultProbe(hdr10Source(Optional.empty(), Optional.empty()));

    accepted(playback.start(start(options(VideoQuality.AUTO, null), hdrAv1Client())));

    assertThat(plannedEncode())
        .satisfies(
            encode -> {
              assertThat(encode.masteringDisplay()).contains(FALLBACK_DISPLAY);
              assertThat(encode.contentLightLevel()).contains(UNKNOWN_LIGHT_LEVEL);
            });
  }

  @Test
  @DisplayName(
      "Should keep the source's content light level beside the fallback display when the probe"
          + " stored only the content light level")
  void shouldKeepTheSourcesContentLightLevelBesideTheFallbackDisplayWhenTheProbeStoredOnlyIt() {
    probes.setDefaultProbe(hdr10Source(Optional.empty(), Optional.of(SOURCE_LIGHT_LEVEL)));

    accepted(playback.start(start(options(VideoQuality.AUTO, null), hdrAv1Client())));

    assertThat(plannedEncode())
        .satisfies(
            encode -> {
              assertThat(encode.masteringDisplay()).contains(FALLBACK_DISPLAY);
              assertThat(encode.contentLightLevel()).contains(SOURCE_LIGHT_LEVEL);
            });
  }

  @Test
  @DisplayName("Should encode an HLG source to 10-bit AV1 HLG without static metadata")
  void shouldEncodeAnHlgSourceTo10BitAv1HlgWithoutStaticMetadata() {
    probes.setDefaultProbe(
        hdrSource(rangeOf(CompatibleBase.HLG), Optional.empty(), Optional.empty()));

    var encode = accepted(playback.start(start(options(VideoQuality.AUTO, null), hdrAv1Client())));

    assertThat(plannedEncode())
        .satisfies(
            planned -> {
              assertThat(planned.dynamicRange()).isEqualTo(rangeOf(CompatibleBase.HLG));
              assertThat(planned.bitDepth()).isEqualTo(10);
              assertThat(planned.masteringDisplay()).isEmpty();
              assertThat(planned.contentLightLevel()).isEmpty();
            });
    assertThat(multivariantPlaylist(encode)).contains(",VIDEO-RANGE=HLG");
  }

  // Profiles 5 and 10.0 have no compatible base, so the worker reshapes them with their RPUs into
  // PQ, in every playback choice (#397). Original cannot copy, so its encode plays in Auto.
  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("dolbyVisionWithoutACompatibleBase")
  @DisplayName(
      "Should encode Dolby Vision without a compatible base to HDR10 when the client declares PQ"
          + " but not the profile and a worker reshapes it")
  void shouldEncodeDolbyVisionWithoutACompatibleBaseToHdr10WhenTheClientDeclaresPq(
      DolbyVisionProfile profile, VideoQuality quality, VideoQuality expectedChoice) {
    probes.setDefaultProbe(baselessSource(profile));

    var encode = accepted(playback.start(start(options(quality, null), hdrAv1Client())));

    assertThat(encode.playbackChoice()).isEqualTo(expectedChoice);
    assertThat(plannedEncode())
        .satisfies(
            planned -> {
              assertThat(planned.dynamicRange()).isEqualTo(rangeOf(CompatibleBase.PQ));
              assertThat(planned.bitDepth()).isEqualTo(10);
              assertThat(planned.masteringDisplay()).contains(FALLBACK_DISPLAY);
              assertThat(planned.contentLightLevel()).contains(UNKNOWN_LIGHT_LEVEL);
            });
    assertThat(multivariantPlaylist(encode))
        .contains(",VIDEO-RANGE=PQ")
        .doesNotContain("dvh1")
        .doesNotContain("dav1");
  }

  // Decision 173: an RPU's level 6 MaxCLL and MaxFALL, which probe version 8 stores, stand in when
  // the source carries no content light level of its own.
  @Test
  @DisplayName(
      "Should take an HDR10 encode's content light level from the RPUs' level 6 when the probe"
          + " stored none for a profile 5 source")
  void shouldTakeAnHdr10EncodesContentLightLevelFromTheRpusLevel6WhenTheProbeStoredNone() {
    probes.setDefaultProbe(baselessSource(DolbyVisionProfile.PROFILE_5, Optional.of(LEVEL_6)));

    accepted(playback.start(start(options(VideoQuality.AUTO, null), hdrAv1Client())));

    assertThat(plannedEncode())
        .satisfies(
            planned -> {
              assertThat(planned.masteringDisplay()).contains(FALLBACK_DISPLAY);
              assertThat(planned.contentLightLevel()).contains(LEVEL_6);
            });
  }

  @Test
  @DisplayName(
      "Should keep the source's own content light level over the RPUs' level 6 when an HDR10 encode"
          + " plays a profile 8.1 source")
  void shouldKeepTheSourcesOwnContentLightLevelOverTheRpusLevel6WhenAnHdr10EncodePlaysIt() {
    var range =
        new VideoDynamicRange(
            CompatibleBase.PQ,
            Presence.ABSENT,
            DolbyVision.Present.builder()
                .observedRpu(Presence.PRESENT)
                .rpuContentLightLevel(Optional.of(LEVEL_6))
                .build());
    probes.setDefaultProbe(
        hdrSource(range, Optional.of(SOURCE_DISPLAY), Optional.of(SOURCE_LIGHT_LEVEL)));

    accepted(playback.start(start(options(VideoQuality.AUTO, null), hdrAv1Client())));

    assertThat(plannedEncode())
        .satisfies(
            planned -> {
              assertThat(planned.masteringDisplay()).contains(SOURCE_DISPLAY);
              assertThat(planned.contentLightLevel()).contains(SOURCE_LIGHT_LEVEL);
            });
  }

  static Stream<Arguments> dolbyVisionWithoutACompatibleBase() {
    return Stream.of(DolbyVisionProfile.PROFILE_5, DolbyVisionProfile.PROFILE_10_0)
        .flatMap(
            profile ->
                Stream.of(
                    Arguments.of(profile, VideoQuality.AUTO, VideoQuality.AUTO),
                    Arguments.of(profile, VideoQuality.FULL_HD_1080P, VideoQuality.FULL_HD_1080P),
                    Arguments.of(profile, VideoQuality.ORIGINAL, VideoQuality.AUTO)));
  }

  @Test
  @DisplayName(
      "Should encode Dolby Vision profile 5 to SDR when no worker reshapes Dolby Vision into PQ")
  void shouldEncodeDolbyVisionProfile5ToSdrWhenNoWorkerReshapesDolbyVisionIntoPq() {
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL,
        List.of(
            svtAv1(Set.of(VideoEncodeFeature.TEN_BIT, VideoEncodeFeature.PQ)), libx264(Set.of())));
    probes.setDefaultProbe(baselessSource(DolbyVisionProfile.PROFILE_5));

    var encode = accepted(playback.start(start(options(VideoQuality.AUTO, null), hdrAv1Client())));

    assertThatAn8BitSdrEncodePlays(encode);
  }

  // ADR 0013: SDR in a fixed quality plays an HDR source as an SDR encode.
  @Test
  @DisplayName("Should encode an HDR10 source to 8-bit SDR when the viewer chooses SDR")
  void shouldEncodeAnHdr10SourceTo8BitSdrWhenTheViewerChoosesSdr() {
    probes.setDefaultProbe(
        hdr10Source(Optional.of(SOURCE_DISPLAY), Optional.of(SOURCE_LIGHT_LEVEL)));

    var encode =
        accepted(
            playback.start(
                start(
                    options(VideoQuality.FULL_HD_1080P, DynamicRangeChoice.SDR), hdrAv1Client())));

    assertThat(encode.playbackChoice()).isEqualTo(VideoQuality.FULL_HD_1080P);
    assertThatAn8BitSdrEncodePlays(encode);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("setupsThatCannotPlayAnHdrEncode")
  @DisplayName(
      "Should encode an HDR10 source to 8-bit SDR when the client or the workers cannot play or"
          + " write an HDR encode")
  void shouldEncodeAnHdr10SourceTo8BitSdrWhenTheClientOrTheWorkersCannotPlayOrWriteAnHdrEncode(
      ClientCapabilityDeclaration client, List<WorkerVerifiedEncoder> encoders) {
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, encoders);
    probes.setDefaultProbe(
        hdr10Source(Optional.of(SOURCE_DISPLAY), Optional.of(SOURCE_LIGHT_LEVEL)));

    var encode = accepted(playback.start(start(options(VideoQuality.AUTO, null), client)));

    assertThatAn8BitSdrEncodePlays(encode);
  }

  static Stream<Arguments> setupsThatCannotPlayAnHdrEncode() {
    var everyFeature = List.of(svtAv1(EVERY_ENCODE_FEATURE), libx264(Set.of()));
    return Stream.of(
        Arguments.of(
            Named.of(
                "a client whose AV1 entry declares only SDR", client(av1EntryBuilder().build())),
            everyFeature),
        Arguments.of(
            Named.of(
                "a client whose AV1 entry declares PQ at 8 bits",
                client(
                    av1EntryBuilder()
                        .maxBitDepth(8)
                        .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                        .build())),
            everyFeature),
        Arguments.of(
            Named.of(
                "a client whose H.264 entry alone declares PQ at 10 bits",
                clientWith(
                    h264EntryBuilder()
                        .maxBitDepth(10)
                        .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                        .build())),
            List.of(svtAv1(Set.of()), libx264(EVERY_ENCODE_FEATURE))),
        Arguments.of(
            Named.of("workers whose AV1 encoder writes only 10-bit SDR", hdrAv1Client()),
            List.of(svtAv1(Set.of(VideoEncodeFeature.TEN_BIT)), libx264(Set.of()))));
  }

  // ADR 0050 lets a copy above the viewer's limits play only when no encode plays, and offers a
  // fixed quality whose box an encode reaches. An entry that accepts only PQ takes an HDR10 encode,
  // so the smaller fixed qualities are offered and that encode plays within the limits.
  @Test
  @DisplayName(
      "Should offer and encode an HDR10 source in a fixed quality when only a PQ entry accepts the"
          + " encode and the copy is above the quality")
  void shouldOfferAndEncodeAnHdr10SourceInAFixedQualityWhenOnlyAPqEntryAcceptsTheEncode() {
    workers.setVideoCopySupportByWorker(
        List.of(
            WorkerVideoCopySupport.builder()
                .plannedOutput(true)
                .bitstreamRewrites(Set.of())
                .build()));
    probes.setDefaultProbe(
        hdr10Source(Optional.of(SOURCE_DISPLAY), Optional.of(SOURCE_LIGHT_LEVEL)));

    var encode =
        accepted(
            playback.start(
                start(
                    options(VideoQuality.HIGH_720P, null),
                    clientWith(
                        hevcEntryBuilder()
                            .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                            .build(),
                        av1EntryBuilder().compatibleBases(List.of(CompatibleBase.PQ)).build()))));

    assertThat(encode.playbackChoice()).isEqualTo(VideoQuality.HIGH_720P);
    assertThat(encode.offeredChoices())
        .containsExactly(
            VideoQuality.ORIGINAL,
            VideoQuality.AUTO,
            VideoQuality.FULL_HD_1080P,
            VideoQuality.HIGH_720P,
            VideoQuality.MEDIUM_480P,
            VideoQuality.LOW_360P);
    assertThat(sessionOf(encode).getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(plannedEncode().dynamicRange()).isEqualTo(rangeOf(CompatibleBase.PQ));
  }

  // A busy worker's encoder cannot take the encode, so its encode features must not plan an HDR
  // encode that only it writes while an idle worker could play the source in SDR.
  @Test
  @DisplayName(
      "Should encode an HDR10 source to 8-bit SDR on an idle worker when only a busy worker's"
          + " encoder writes PQ")
  void shouldEncodeAnHdr10SourceTo8BitSdrOnAnIdleWorkerWhenOnlyABusyWorkersEncoderWritesPq() {
    var busy = new ExecutionTargetId("busy");
    var idle = new ExecutionTargetId("idle");
    workers.setExecutionTargets(List.of(busy, idle));
    workers.setTargetEncoders(busy, List.of(svtAv1(EVERY_ENCODE_FEATURE), libx264(Set.of())));
    workers.setTargetEncoders(idle, List.of(svtAv1(Set.of()), libx264(Set.of())));
    workers.limitJobSlots(busy, 0);
    probes.setDefaultProbe(
        hdr10Source(Optional.of(SOURCE_DISPLAY), Optional.of(SOURCE_LIGHT_LEVEL)));

    var encode =
        accepted(
            playback.start(start(options(VideoQuality.AUTO, null), clientWith(hdrAv1Entry()))));

    assertThatAn8BitSdrEncodePlays(encode);
  }

  // Frame rate comes first (ADR 0009), so a client entry that plays the source's range or bit depth
  // only at half the source's frame rate loses to one that plays every frame in 8-bit SDR.
  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesWhoseRangeOrBitDepthPlaysOnlyAtHalfTheirFrameRate")
  @DisplayName(
      "Should encode a 60 fps source to 8-bit SDR at 60 fps when the client plays its range or bit"
          + " depth only at 30 fps")
  void shouldEncodeA60FpsSourceTo8BitSdrAt60FpsWhenTheClientPlaysItsRangeOrBitDepthOnlyAt30Fps(
      CompatibleBase sourceBase, VideoCapabilityEntry halfRateEntry) {
    probes.setDefaultProbe(sixtyFpsSource(sourceBase));

    var encode =
        accepted(
            playback.start(
                start(
                    options(VideoQuality.AUTO, null),
                    clientWith(halfRateEntry, av1EntryBuilder().maxBitDepth(8).build()))));

    assertThat(plannedEncode().bitDepth()).isEqualTo(8);
    assertThat(plannedEncode().dynamicRange()).isEqualTo(VideoDynamicRange.SDR);
    assertThat(multivariantPlaylist(encode))
        .contains(",FRAME-RATE=60.000")
        .doesNotContain("30.000");
  }

  // The encode takes the fastest frame rate first, so a fixed quality that only the slower PQ entry
  // reaches would play the faster SDR entry's smaller picture, and is not offered (ADR 0050).
  @Test
  @DisplayName(
      "Should not offer a fixed quality that only a slower HDR encode reaches when a faster SDR"
          + " encode wins the codec family")
  void shouldNotOfferAFixedQualityThatOnlyASlowerHdrEncodeReaches() {
    probes.setDefaultProbe(sixtyFpsSource(CompatibleBase.PQ));

    var encode =
        accepted(
            playback.start(
                start(
                    options(VideoQuality.AUTO, null),
                    clientWith(
                        av1EntryBuilder()
                            .compatibleBases(List.of(CompatibleBase.PQ))
                            .maxWidth(1920)
                            .maxHeight(1080)
                            .maxFrameRate(30)
                            .build(),
                        av1EntryBuilder().maxBitDepth(8).maxWidth(1280).maxHeight(720).build()))));

    assertThat(encode.offeredChoices())
        .containsExactly(
            VideoQuality.ORIGINAL,
            VideoQuality.AUTO,
            VideoQuality.HIGH_720P,
            VideoQuality.MEDIUM_480P,
            VideoQuality.LOW_360P);
    assertThat(plannedEncode().dynamicRange()).isEqualTo(VideoDynamicRange.SDR);
  }

  // The offered choices last for the whole playback session, so a fixed quality that only the
  // viewer's SDR choice reaches stays offered beside the ones that HDR reaches (ADR 0050).
  @Test
  @DisplayName(
      "Should offer and play a fixed quality that only an SDR encode reaches when the viewer"
          + " chooses SDR and a faster HDR encode wins without the choice")
  void shouldOfferAndPlayAFixedQualityThatOnlyAnSdrEncodeReachesWhenTheViewerChoosesSdr() {
    probes.setDefaultProbe(sixtyFpsSource(CompatibleBase.PQ));

    var encode =
        accepted(
            playback.start(
                start(
                    options(VideoQuality.FULL_HD_1080P, DynamicRangeChoice.SDR),
                    clientWith(
                        av1EntryBuilder()
                            .compatibleBases(List.of(CompatibleBase.PQ))
                            .maxWidth(1280)
                            .maxHeight(720)
                            .build(),
                        av1EntryBuilder().maxBitDepth(8).maxFrameRate(30).build()))));

    assertThat(encode.playbackChoice()).isEqualTo(VideoQuality.FULL_HD_1080P);
    assertThat(encode.offeredChoices())
        .containsExactly(
            VideoQuality.ORIGINAL,
            VideoQuality.AUTO,
            VideoQuality.FULL_HD_1080P,
            VideoQuality.HIGH_720P,
            VideoQuality.MEDIUM_480P,
            VideoQuality.LOW_360P);
    assertThat(plannedEncode().dynamicRange()).isEqualTo(VideoDynamicRange.SDR);
    assertThat(multivariantPlaylist(encode)).contains("RESOLUTION=1920x1080");
  }

  static Stream<Arguments> sourcesWhoseRangeOrBitDepthPlaysOnlyAtHalfTheirFrameRate() {
    return Stream.of(
        Arguments.of(
            Named.of("an HDR10 source", CompatibleBase.PQ),
            av1EntryBuilder()
                .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                .maxFrameRate(30)
                .build()),
        Arguments.of(
            Named.of("a 10-bit SDR source", CompatibleBase.SDR),
            av1EntryBuilder().maxBitDepth(10).maxFrameRate(30).build()));
  }

  // An SDR encode keeps the codec preference of ADR 0009, so the client here declares AV1 alone.
  // The worker's encoder must also list 10-bit, and the client's entry must accept 10 bits.
  @ParameterizedTest
  @EnumSource(value = TenBitSdrCase.class)
  @DisplayName(
      "Should encode a 10-bit SDR source to 10-bit AV1 only when the client and the worker's"
          + " encoder accept 10 bits")
  void shouldEncodeA10BitSdrSourceTo10BitAv1OnlyWhenTheClientAndTheEncoderAccept10Bits(
      TenBitSdrCase tenBitCase) {
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(svtAv1(tenBitCase.features), libx264(Set.of())));
    probes.setDefaultProbe(
        hdrSource(rangeOf(CompatibleBase.SDR), Optional.empty(), Optional.empty()));

    var encode =
        accepted(
            playback.start(
                start(
                    options(VideoQuality.AUTO, null),
                    clientWith(av1EntryBuilder().maxBitDepth(tenBitCase.clientBitDepth).build()))));

    assertThat(plannedEncode().bitDepth()).isEqualTo(tenBitCase.expectedBitDepth);
    assertThat(plannedEncode().dynamicRange()).isEqualTo(rangeOf(CompatibleBase.SDR));
    assertThat(multivariantPlaylist(encode))
        .containsPattern(
            "CODECS=\"av01\\.0\\.\\d\\dM\\.%02d,".formatted(tenBitCase.expectedBitDepth));
  }

  @ParameterizedTest
  @EnumSource(
      value = CompatibleBase.class,
      names = {"SDR", "PQ", "HLG"})
  @DisplayName(
      "Should encode only 8-bit SDR in HEVC Main when the worker and client entry allow 10 bits")
  void shouldEncodeOnly8BitSdrInHevcMainWhenTheWorkerAndClientEntryAllow10Bits(
      CompatibleBase sourceRange) {
    workers.setEncodableVideoCodecFamilies(Set.of("hevc"));
    workers.setVerifiedVideoCodecFamiliesByWorker(
        List.of(
            WorkerVerifiedCodecFamilies.builder()
                .workerId(UUID.randomUUID())
                .codecFamilies(Set.of("hevc"))
                .build()));
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(encoder("hevc", "libx265", EVERY_ENCODE_FEATURE)));
    probes.setDefaultProbe(hdrSource(rangeOf(sourceRange), Optional.empty(), Optional.empty()));
    var entry =
        hevcEntryBuilder()
            .maxBitDepth(10)
            .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
            .codecProfiles(
                List.of(
                    VideoCodecProfileCapability.builder()
                        .profile(VideoCodecProfile.HEVC_MAIN)
                        .maxLevel(51)
                        .maxTier(Optional.of(VideoCodecTier.MAIN))
                        .build()))
            .build();

    var encode =
        accepted(playback.start(start(options(VideoQuality.AUTO, null), clientWith(entry))));

    assertThat(plannedEncode().codecProfile()).contains(EncodeCodecProfile.HEVC_MAIN);
    assertThatAn8BitSdrEncodePlays(encode);
    assertThat(multivariantPlaylist(encode)).containsPattern("CODECS=\"hvc1\\.1\\.6\\.L\\d+\\.90,");
  }

  enum TenBitSdrCase {
    CLIENT_AND_ENCODER_ACCEPT_10_BITS(10, Set.of(VideoEncodeFeature.TEN_BIT), 10),
    ENCODER_WRITES_ONLY_8_BITS(10, Set.of(), 8),
    CLIENT_ACCEPTS_ONLY_8_BITS(8, Set.of(VideoEncodeFeature.TEN_BIT), 8);

    private final int clientBitDepth;
    private final Set<VideoEncodeFeature> features;
    private final int expectedBitDepth;

    TenBitSdrCase(int clientBitDepth, Set<VideoEncodeFeature> features, int expectedBitDepth) {
      this.clientBitDepth = clientBitDepth;
      this.features = features;
      this.expectedBitDepth = expectedBitDepth;
    }
  }

  // ADR 0044: after a hardware encoder fails, the encode continues on the software encoder of the
  // codec family, so it keeps only the range and bit depth that the software encoders write.
  @ParameterizedTest(name = "{0}")
  @MethodSource("softwareEncodersAndTheirOutput")
  @DisplayName(
      "Should plan the software encode's range and bit depth from the software encoders when the"
          + " hardware encoder of an HDR10 encode fails")
  void shouldPlanTheSoftwareEncodesRangeAndBitDepthFromTheSoftwareEncodersWhenTheHardwareFails(
      Set<VideoEncodeFeature> softwareFeatures,
      VideoDynamicRange expectedRange,
      int expectedBitDepth) {
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(av1Nvenc(EVERY_ENCODE_FEATURE), libx264(Set.of())));
    workers.setTargetSoftwareEncoders(ExecutionTargetId.LOCAL, List.of(svtAv1(softwareFeatures)));
    probes.setDefaultProbe(
        hdr10Source(Optional.of(SOURCE_DISPLAY), Optional.of(SOURCE_LIGHT_LEVEL)));
    var command = start(options(VideoQuality.AUTO, null), hdrAv1Client());
    var hardware = accepted(playback.start(command));
    assertThat(sessionOf(hardware).getVideoRange()).isEqualTo(VideoRange.PQ);

    playback.onRequestedVariantHardwareEncoderFailed(
        RequestedVariantHardwareEncoderFailedEvent.builder()
            .streamSessionId(hardware.currentFormatAttempt().streamSessionId())
            .variantLabel("default")
            .build());

    var software = workers.getStartedRequests().getLast().transcodeDecision();
    assertThat(software.softwareEncoder()).isTrue();
    assertThat(software.plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.Encode.class,
            encode -> {
              assertThat(encode.dynamicRange()).isEqualTo(expectedRange);
              assertThat(encode.bitDepth()).isEqualTo(expectedBitDepth);
            });
  }

  static Stream<Arguments> softwareEncodersAndTheirOutput() {
    return Stream.of(
        Arguments.of(
            Named.of("software encoders that write only 8-bit SDR", Set.of()),
            VideoDynamicRange.SDR,
            8),
        Arguments.of(
            Named.of("software encoders that write HDR10", EVERY_ENCODE_FEATURE),
            rangeOf(CompatibleBase.PQ),
            10));
  }

  // Decision 179 and #397: at the same frame rate, a 10-bit SDR source keeps 10 bits in AV1 rather
  // than 8-bit H.264, when the client declares 10-bit AV1 and a worker's AV1 encoder writes 10
  // bits.
  // Otherwise H.264 keeps its place in ADR 0009's codec preference.
  @ParameterizedTest(name = "{0}")
  @MethodSource("tenBitSdrSourcesBesideH264")
  @DisplayName(
      "Should encode a 10-bit SDR source to 10-bit AV1 rather than 8-bit H.264 when the client and"
          + " a worker's AV1 encoder accept 10 bits")
  void
      shouldEncodeA10BitSdrSourceTo10BitAv1RatherThan8BitH264WhenTheClientAndTheEncoderAccept10Bits(
          TenBitSdrBesideH264Case tenBitCase) {
    var quality = tenBitCase.quality();
    var clientAv1BitDepth = tenBitCase.clientAv1BitDepth();
    var av1Features = tenBitCase.av1Features();
    var expectedProfile = tenBitCase.expectedProfile();
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(svtAv1(av1Features), libx264(Set.of())));
    probes.setDefaultProbe(
        hdrSource(rangeOf(CompatibleBase.SDR), Optional.empty(), Optional.empty()));

    var encode =
        accepted(
            playback.start(
                start(
                    options(quality, null),
                    client(av1EntryBuilder().maxBitDepth(clientAv1BitDepth).build()))));

    var expectedBitDepth = expectedProfile == EncodeCodecProfile.AV1_MAIN ? 10 : 8;
    assertThat(encode.playbackChoice()).isEqualTo(quality);
    assertThat(plannedEncode().codecProfile()).contains(expectedProfile);
    assertThat(plannedEncode().bitDepth()).isEqualTo(expectedBitDepth);
    assertThat(plannedEncode().dynamicRange()).isEqualTo(rangeOf(CompatibleBase.SDR));
    assertThat(multivariantPlaylist(encode))
        .contains(
            expectedProfile == EncodeCodecProfile.AV1_MAIN ? "CODECS=\"av01." : "CODECS=\"avc1.")
        .doesNotContain("VIDEO-RANGE=");
  }

  static Stream<TenBitSdrBesideH264Case> tenBitSdrSourcesBesideH264() {
    var tenBit = Set.of(VideoEncodeFeature.TEN_BIT);
    return Stream.of(
        TenBitSdrBesideH264Case.builder()
            .name("Auto, a client and an encoder that accept 10-bit AV1")
            .av1Features(tenBit)
            .expectedProfile(EncodeCodecProfile.AV1_MAIN)
            .build(),
        TenBitSdrBesideH264Case.builder()
            .name("1080p, a client and an encoder that accept 10-bit AV1")
            .quality(VideoQuality.FULL_HD_1080P)
            .av1Features(tenBit)
            .expectedProfile(EncodeCodecProfile.AV1_MAIN)
            .build(),
        TenBitSdrBesideH264Case.builder()
            .name("Auto, a client that declares only 8-bit AV1")
            .clientAv1BitDepth(8)
            .av1Features(tenBit)
            .expectedProfile(EncodeCodecProfile.H264_HIGH)
            .build(),
        TenBitSdrBesideH264Case.builder()
            .name("Auto, an AV1 encoder that writes only 8 bits")
            .av1Features(Set.of())
            .expectedProfile(EncodeCodecProfile.H264_HIGH)
            .build());
  }

  @Builder
  private record TenBitSdrBesideH264Case(
      @NonNull String name,
      @NonNull VideoQuality quality,
      int clientAv1BitDepth,
      @NonNull Set<VideoEncodeFeature> av1Features,
      @NonNull EncodeCodecProfile expectedProfile) {

    // Lombok builder defaults — its generated setters assign them
    @SuppressWarnings({"java:S1068", "java:S9438"})
    private static class TenBitSdrBesideH264CaseBuilder {
      private VideoQuality quality = VideoQuality.AUTO;
      private int clientAv1BitDepth = 10;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  // ADR 0009 and decision 179: frame rate comes first, so a 10-bit AV1 entry that plays the source
  // only at half its rate loses to 8-bit H.264 at the full rate.
  @Test
  @DisplayName(
      "Should encode a 60 fps 10-bit SDR source to 8-bit H.264 at 60 fps when the client plays"
          + " 10-bit AV1 only at 30 fps")
  void shouldEncodeA60Fps10BitSdrSourceTo8BitH264At60FpsWhenTheClientPlays10BitAv1OnlyAt30Fps() {
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL,
        List.of(svtAv1(Set.of(VideoEncodeFeature.TEN_BIT)), libx264(Set.of())));
    probes.setDefaultProbe(sixtyFpsSource(CompatibleBase.SDR));

    var encode =
        accepted(
            playback.start(
                start(
                    options(VideoQuality.AUTO, null),
                    client(av1EntryBuilder().maxBitDepth(10).maxFrameRate(30).build()))));

    assertThat(plannedEncode().codecProfile()).contains(EncodeCodecProfile.H264_HIGH);
    assertThat(plannedEncode().bitDepth()).isEqualTo(8);
    assertThat(multivariantPlaylist(encode)).contains(",FRAME-RATE=60.000");
  }

  // ADR 0009 and decision 179: a codec family that a worker encodes in hardware still comes before
  // H.264 and before any software family, so a hardware AV1 encoder that writes only 8 bits
  // encodes the 10-bit SDR source in 8-bit AV1, and a client that declares only 8-bit AV1 gets
  // hardware AV1 too.
  @ParameterizedTest(name = "a client that declares {0}-bit AV1")
  @ValueSource(ints = {8, 10})
  @DisplayName(
      "Should encode a 10-bit SDR source to 8-bit AV1 when a worker encodes AV1 in hardware in 8"
          + " bits only")
  void shouldEncodeA10BitSdrSourceTo8BitAv1WhenAWorkerEncodesAv1InHardwareIn8BitsOnly(
      int clientAv1BitDepth) {
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(hardwareAv1WithoutFeatures(), libx264(Set.of())));
    probes.setDefaultProbe(
        hdrSource(rangeOf(CompatibleBase.SDR), Optional.empty(), Optional.empty()));

    accepted(
        playback.start(
            start(
                options(VideoQuality.AUTO, null),
                client(av1EntryBuilder().maxBitDepth(clientAv1BitDepth).build()))));

    assertThat(plannedEncode().codecProfile()).contains(EncodeCodecProfile.AV1_MAIN);
    assertThat(plannedEncode().bitDepth()).isEqualTo(8);
  }

  // #58: an HEVC encode that keeps an HDR range or 10 bits forces H.265 Main 10. The client here
  // declares HEVC alone, so the AV1 source cannot be copied.
  @Test
  @DisplayName(
      "Should encode an HDR10 source to HEVC Main 10 HDR10 with the source's static metadata when"
          + " the client declares HEVC with PQ and libx265 writes PQ")
  void
      shouldEncodeAnHdr10SourceToHevcMain10Hdr10WhenTheClientDeclaresHevcWithPqAndLibx265WritesPq() {
    encodeWithLibx265(EVERY_ENCODE_FEATURE);
    probes.setDefaultProbe(
        av1Source(
            rangeOf(CompatibleBase.PQ),
            Optional.of(SOURCE_DISPLAY),
            Optional.of(SOURCE_LIGHT_LEVEL)));

    var encode = accepted(playback.start(start(options(VideoQuality.AUTO, null), hdrHevcClient())));

    assertThat(plannedEncode())
        .isEqualTo(
            PlannedVideoOutput.Encode.builder()
                .codecProfile(Optional.of(EncodeCodecProfile.HEVC_MAIN_10))
                .dynamicRange(rangeOf(CompatibleBase.PQ))
                .bitDepth(10)
                .chromaSubsampling(ChromaSubsampling.CHROMA_420)
                .masteringDisplay(Optional.of(SOURCE_DISPLAY))
                .contentLightLevel(Optional.of(SOURCE_LIGHT_LEVEL))
                .build());
    assertThat(multivariantPlaylist(encode))
        .containsPattern("CODECS=\"hvc1\\.2\\.4\\.L\\d+\\.90,mp4a\\.40\\.2\"")
        .contains(",VIDEO-RANGE=PQ");
  }

  @Test
  @DisplayName(
      "Should encode an HLG source to HEVC Main 10 HLG without static metadata when the client"
          + " declares HEVC with HLG")
  void
      shouldEncodeAnHlgSourceToHevcMain10HlgWithoutStaticMetadataWhenTheClientDeclaresHevcWithHlg() {
    encodeWithLibx265(EVERY_ENCODE_FEATURE);
    probes.setDefaultProbe(
        av1Source(rangeOf(CompatibleBase.HLG), Optional.empty(), Optional.empty()));

    var encode = accepted(playback.start(start(options(VideoQuality.AUTO, null), hdrHevcClient())));

    assertThat(plannedEncode())
        .satisfies(
            planned -> {
              assertThat(planned.codecProfile()).contains(EncodeCodecProfile.HEVC_MAIN_10);
              assertThat(planned.dynamicRange()).isEqualTo(rangeOf(CompatibleBase.HLG));
              assertThat(planned.bitDepth()).isEqualTo(10);
              assertThat(planned.masteringDisplay()).isEmpty();
              assertThat(planned.contentLightLevel()).isEmpty();
            });
    assertThat(multivariantPlaylist(encode))
        .containsPattern("CODECS=\"hvc1\\.2\\.4\\.L\\d+\\.90,")
        .contains(",VIDEO-RANGE=HLG");
  }

  @Test
  @DisplayName(
      "Should encode Dolby Vision profile 5 to HEVC Main 10 HDR10 when the client declares HEVC"
          + " with PQ and libx265 reshapes Dolby Vision into PQ")
  void shouldEncodeDolbyVisionProfile5ToHevcMain10Hdr10WhenLibx265ReshapesDolbyVisionIntoPq() {
    encodeWithLibx265(EVERY_ENCODE_FEATURE);
    probes.setDefaultProbe(baselessSource(DolbyVisionProfile.PROFILE_5));

    var encode = accepted(playback.start(start(options(VideoQuality.AUTO, null), hdrHevcClient())));

    assertThat(plannedEncode())
        .satisfies(
            planned -> {
              assertThat(planned.codecProfile()).contains(EncodeCodecProfile.HEVC_MAIN_10);
              assertThat(planned.dynamicRange()).isEqualTo(rangeOf(CompatibleBase.PQ));
              assertThat(planned.masteringDisplay()).contains(FALLBACK_DISPLAY);
            });
    assertThat(multivariantPlaylist(encode)).contains(",VIDEO-RANGE=PQ").doesNotContain("dvh1");
  }

  // The same feature check as AV1's: without PQ, libx265 writes 8-bit SDR in H.265 Main. The
  // client declares HEVC alone, because H.264 ranks before software HEVC for an SDR encode.
  @Test
  @DisplayName(
      "Should encode an HDR10 source to 8-bit SDR in HEVC Main when libx265 does not list PQ")
  void shouldEncodeAnHdr10SourceTo8BitSdrInHevcMainWhenLibx265DoesNotListPq() {
    encodeWithLibx265(Set.of(VideoEncodeFeature.TEN_BIT, VideoEncodeFeature.HLG));
    probes.setDefaultProbe(
        av1Source(
            rangeOf(CompatibleBase.PQ),
            Optional.of(SOURCE_DISPLAY),
            Optional.of(SOURCE_LIGHT_LEVEL)));

    var encode =
        accepted(
            playback.start(start(options(VideoQuality.AUTO, null), clientWith(hdrHevcEntry()))));

    assertThat(plannedEncode().codecProfile()).contains(EncodeCodecProfile.HEVC_MAIN);
    assertThatAn8BitSdrEncodePlays(encode);
    assertThat(multivariantPlaylist(encode)).containsPattern("CODECS=\"hvc1\\.1\\.6\\.L\\d+\\.90,");
  }

  // An SDR encode keeps the codec preference, so the client declares HEVC alone.
  @ParameterizedTest
  @EnumSource(value = TenBitHevcCase.class)
  @DisplayName(
      "Should encode a 10-bit SDR source to HEVC Main 10 only when the client declares Main 10 and"
          + " libx265 lists 10 bits")
  void shouldEncodeA10BitSdrSourceToHevcMain10OnlyWhenTheClientDeclaresMain10AndLibx265Lists10Bits(
      TenBitHevcCase hevcCase) {
    encodeWithLibx265(hevcCase.features);
    probes.setDefaultProbe(
        av1Source(rangeOf(CompatibleBase.SDR), Optional.empty(), Optional.empty()));

    var encode =
        accepted(
            playback.start(start(options(VideoQuality.AUTO, null), clientWith(hevcCase.entry()))));

    assertThat(plannedEncode().codecProfile()).contains(hevcCase.expectedProfile);
    assertThat(plannedEncode().bitDepth()).isEqualTo(hevcCase.expectedBitDepth);
    assertThat(multivariantPlaylist(encode)).containsPattern(hevcCase.codecStringPattern);
  }

  enum TenBitHevcCase {
    CLIENT_DECLARES_MAIN_10_AND_LIBX265_WRITES_10_BITS(
        true, Set.of(VideoEncodeFeature.TEN_BIT), EncodeCodecProfile.HEVC_MAIN_10, 10),
    LIBX265_WRITES_ONLY_8_BITS(true, Set.of(), EncodeCodecProfile.HEVC_MAIN, 8),
    CLIENT_DECLARES_ONLY_MAIN(
        false, Set.of(VideoEncodeFeature.TEN_BIT), EncodeCodecProfile.HEVC_MAIN, 8);

    private final boolean declaresMain10;
    private final Set<VideoEncodeFeature> features;
    private final EncodeCodecProfile expectedProfile;
    private final int expectedBitDepth;
    private final String codecStringPattern;

    TenBitHevcCase(
        boolean declaresMain10,
        Set<VideoEncodeFeature> features,
        EncodeCodecProfile expectedProfile,
        int expectedBitDepth) {
      this.declaresMain10 = declaresMain10;
      this.features = features;
      this.expectedProfile = expectedProfile;
      this.expectedBitDepth = expectedBitDepth;
      this.codecStringPattern =
          expectedBitDepth == 10 ? "CODECS=\"hvc1\\.2\\.4\\." : "CODECS=\"hvc1\\.1\\.6\\.";
    }

    VideoCapabilityEntry entry() {
      if (declaresMain10) {
        return hevcEntryBuilder().build();
      }

      return hevcEntryBuilder()
          .maxBitDepth(8)
          .codecProfiles(
              List.of(
                  VideoCodecProfileCapability.builder()
                      .profile(VideoCodecProfile.HEVC_MAIN)
                      .maxLevel(51)
                      .maxTier(Optional.of(VideoCodecTier.MAIN))
                      .build()))
          .build();
    }
  }

  private void assertThatAn8BitSdrEncodePlays(PlaybackSessionSnapshot snapshot) {
    var planned = plannedEncode();
    assertThat(planned.bitDepth()).isEqualTo(8);
    assertThat(planned.dynamicRange()).isEqualTo(VideoDynamicRange.SDR);
    assertThat(planned.masteringDisplay()).isEmpty();
    assertThat(sessionOf(snapshot).getVideoRange()).isEqualTo(VideoRange.SDR);
    assertThat(multivariantPlaylist(snapshot)).doesNotContain("VIDEO-RANGE=");
  }

  private PlannedVideoOutput.Encode plannedEncode() {
    var planned = workers.getStartedRequests().getLast().transcodeDecision().plannedVideoOutput();
    assertThat(planned).isInstanceOf(PlannedVideoOutput.Encode.class);
    return (PlannedVideoOutput.Encode) planned;
  }

  private static StreamSession sessionOf(PlaybackSessionSnapshot snapshot) {
    if (snapshot.currentFormatAttempt() instanceof PlaybackFormatAttempt.Ready(var session)) {
      return session;
    }

    throw new AssertionError("Expected a ready format attempt: " + snapshot.currentFormatAttempt());
  }

  private static String multivariantPlaylist(PlaybackSessionSnapshot snapshot) {
    return new HlsPlaylistService(PROPERTIES, UploadBudgetFixture.OFF)
        .generateMultivariantPlaylist(sessionOf(snapshot), "t");
  }

  private static StreamingOptions options(VideoQuality quality, DynamicRangeChoice choice) {
    return StreamingOptions.builder().quality(quality).dynamicRange(choice).build();
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
    return StartPlaybackSessionCommand.builder()
        .mediaFileId(file.getId())
        .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
        .options(options)
        .capabilityDeclaration(client)
        .networkRoute(NetworkRoute.INTERNET)
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
            .build();
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
        .uploadBudget(UploadBudgetFixture.OFF)
        .meterRegistry(meters)
        .build();
  }

  // An AV1 entry that declares SDR, PQ and HLG at 10 bits beside H.264 SDR and stereo AAC. It
  // declares no HEVC, so an HEVC source cannot be copied.
  private static ClientCapabilityDeclaration hdrAv1Client() {
    return client(hdrAv1Entry());
  }

  private static VideoCapabilityEntry hdrAv1Entry() {
    return av1EntryBuilder()
        .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
        .build();
  }

  // Workers encode H.264, HEVC and AV1, and libx265 lists the encode features.
  private void encodeWithLibx265(Set<VideoEncodeFeature> features) {
    var families = Set.of("h264", "hevc", "av1");
    workers.setEncodableVideoCodecFamilies(families);
    workers.setVerifiedVideoCodecFamiliesByWorker(
        List.of(
            WorkerVerifiedCodecFamilies.builder()
                .workerId(UUID.randomUUID())
                .codecFamilies(families)
                .build()));
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL,
        List.of(encoder("hevc", "libx265", features), svtAv1(Set.of()), libx264(Set.of())));
  }

  // The HDR HEVC entry beside H.264 SDR and stereo AAC. It declares no AV1, so an AV1 source
  // cannot be copied.
  private static ClientCapabilityDeclaration hdrHevcClient() {
    return client(hdrHevcEntry());
  }

  // An HEVC entry with H.265 Main and Main 10 that declares SDR, PQ and HLG at 10 bits.
  private static VideoCapabilityEntry hdrHevcEntry() {
    return hevcEntryBuilder()
        .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
        .build();
  }

  private static WorkerVerifiedEncoder svtAv1(Set<VideoEncodeFeature> features) {
    return encoder("av1", "libsvtav1", features);
  }

  // NVENC AV1, which lists no encode feature, so it writes 8-bit SDR alone.
  private static WorkerVerifiedEncoder hardwareAv1WithoutFeatures() {
    return av1Nvenc(Set.of());
  }

  private static WorkerVerifiedEncoder av1Nvenc(Set<VideoEncodeFeature> features) {
    return WorkerVerifiedEncoder.builder()
        .backend(
            EncoderBackend.builder()
                .codecFamily("av1")
                .encoder("av1_nvenc")
                .kind(EncoderBackend.Kind.HARDWARE)
                .build())
        .keyframeVerified(true)
        .encodeFeatures(features)
        .build();
  }

  private static WorkerVerifiedEncoder libx264(Set<VideoEncodeFeature> features) {
    return encoder("h264", "libx264", features);
  }

  private static WorkerVerifiedEncoder encoder(
      String codecFamily, String encoder, Set<VideoEncodeFeature> features) {
    return WorkerVerifiedEncoder.builder()
        .backend(
            EncoderBackend.builder()
                .codecFamily(codecFamily)
                .encoder(encoder)
                .kind(EncoderBackend.Kind.SOFTWARE)
                .build())
        .keyframeVerified(true)
        .encodeFeatures(features)
        .build();
  }

  private static ClientCapabilityDeclaration client(VideoCapabilityEntry entry) {
    return clientWith(entry, h264EntryBuilder().build());
  }

  private static ClientCapabilityDeclaration clientWith(VideoCapabilityEntry... entries) {
    return defaultDeclarationBuilder().video(List.of(entries)).build();
  }

  private static VideoDynamicRange rangeOf(CompatibleBase base) {
    return new VideoDynamicRange(base, Presence.ABSENT, new DolbyVision.Absent());
  }

  private static MediaProbe hdr10Source(
      Optional<MasteringDisplayColorVolume> display, Optional<ContentLightLevel> lightLevel) {
    return hdrSource(rangeOf(CompatibleBase.PQ), display, lightLevel);
  }

  // The default 1080p HEVC Main 10 source at 24 fps, whose keyframes open every 6 s interval,
  // beside stereo AAC.
  private static MediaProbe hdrSource(
      VideoDynamicRange range,
      Optional<MasteringDisplayColorVolume> display,
      Optional<ContentLightLevel> lightLevel) {
    return source(
        hevcFacts()
            .dynamicRange(range)
            .masteringDisplay(display)
            .contentLightLevel(lightLevel)
            .build(),
        "hevc");
  }

  // The default 1080p 10-bit source at 24 fps in AV1 Main.
  private static MediaProbe av1Source(
      VideoDynamicRange range,
      Optional<MasteringDisplayColorVolume> display,
      Optional<ContentLightLevel> lightLevel) {
    return source(
        hevcFacts()
            .codecFamily(Optional.of(ProbedCodecFamily.AV1))
            .codecString(Optional.of("av01.0.08M.10"))
            .videoProperties(Optional.of(properties(VideoCodecProfile.AV1_MAIN)))
            .dynamicRange(range)
            .masteringDisplay(display)
            .contentLightLevel(lightLevel)
            .build(),
        "av1");
  }

  // Profile 5 in HEVC or profile 10.0 in AV1, with its record at level 6 and no compatible base.
  private static MediaProbe baselessSource(DolbyVisionProfile profile) {
    return baselessSource(profile, Optional.empty());
  }

  private static MediaProbe baselessSource(
      DolbyVisionProfile profile, Optional<ContentLightLevel> level6) {
    var range =
        new VideoDynamicRange(
            CompatibleBase.NONE,
            Presence.ABSENT,
            DolbyVision.Present.builder()
                .declared(
                    Optional.of(
                        DolbyVisionConfiguration.builder()
                            .profile(OptionalInt.of(profile.profileNumber()))
                            .level(OptionalInt.of(6))
                            .baseLayerCompatibilityId(OptionalInt.of(0))
                            .baseLayer(true)
                            .rpu(true)
                            .build()))
                .observedRpu(Presence.PRESENT)
                .rpuContentLightLevel(level6)
                .build());
    if (profile.codecFamily() == ProbedCodecFamily.AV1) {
      return source(
          hevcFacts()
              .codecFamily(Optional.of(ProbedCodecFamily.AV1))
              .codecString(Optional.of("av01.0.08M.10"))
              .videoProperties(Optional.of(properties(VideoCodecProfile.AV1_MAIN)))
              .dynamicRange(range)
              .build(),
          "av1");
    }

    return source(hevcFacts().dynamicRange(range).build(), "hevc");
  }

  // The default HEVC Main 10 source at 60 fps in the compatible base.
  private static MediaProbe sixtyFpsSource(CompatibleBase base) {
    return defaultProbeBuilder()
        .videoCodec("hevc")
        .framerate(OptionalDouble.of(60))
        .videoPlaybackFacts(
            Optional.of(
                hevcFacts()
                    .dynamicRange(rangeOf(base))
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60, 1)))
                    .build()))
        .build();
  }

  private static VideoPlaybackFacts.VideoPlaybackFactsBuilder hevcFacts() {
    return VideoPlaybackFactsFixture.sdrH264FactsBuilder()
        .streamIndex(0)
        .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
        .codecFamily(Optional.of(ProbedCodecFamily.HEVC))
        .bitDepth(OptionalInt.of(10))
        .codecString(Optional.of("hvc1.2.4.L150.90"))
        .videoProperties(Optional.of(properties(VideoCodecProfile.HEVC_MAIN_10)));
  }

  private static MediaProbe source(VideoPlaybackFacts facts, String codec) {
    return defaultProbeBuilder().videoCodec(codec).videoPlaybackFacts(Optional.of(facts)).build();
  }

  private static VideoStreamProperties properties(VideoCodecProfile profile) {
    return VideoStreamProperties.builder()
        .codecProfile(Optional.of(profile))
        .codecLevel(OptionalInt.of(40))
        .codecTier(Optional.of(VideoCodecTier.MAIN))
        .firstFramesRead(true)
        .build();
  }
}
