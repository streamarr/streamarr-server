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
import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncodeFormat;
import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.NetworkRoute;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoEncodeFeature;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.VideoSampleEntry;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.ServerSettingsFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.UploadBudgetFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.streaming.events.RequestedVariantHardwareEncoderFailedEvent;
import com.streamarr.server.services.streaming.events.TerminalTranscodeFailureEvent;
import com.streamarr.server.services.streaming.events.VariantRecoveryExhaustedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The encoded lower variants beside an Auto copy keep the copy's range, bit depth and codec family,
 * through the real transcode decision, ladder and playlist (ADR 0050). A bandwidth dip must not
 * move a television between HDR and SDR, and a player moves only between variants of one codec
 * family, so a copy lists no lower variants when no verified encoder writes its range in its codec
 * family, or when no capability entry plays that encode. Neither does a copy that the playlist
 * describes as Dolby Vision, which no encode writes. A failure of a lower variant withdraws the
 * lower variants: a stream session that lists the copy alone replaces the stream session, in the
 * same range and without spending a format attempt.
 */
@Tag("UnitTest")
@DisplayName("Lower Variants In The Copy's Range Tests")
class LowerVariantsInTheCopysRangeTest {

  private static final StreamingProperties PROPERTIES =
      StreamingProperties.builder()
          .maxConcurrentTranscodes(3)
          .targetSegmentDuration(Duration.ofSeconds(6))
          .sessionTimeout(Duration.ofSeconds(60))
          .build();
  private static final int DOLBY_VISION_LEVEL = 6;
  private static final Set<String> ENCODED_CODEC_FAMILIES = Set.of("h264", "hevc", "av1");
  private static final Set<VideoEncodeFeature> EVERY_ENCODE_FEATURE =
      Set.of(VideoEncodeFeature.values());
  private static final List<CompatibleBase> EVERY_RANGE =
      List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG);
  private static final ExecutionTargetId HDR_WORKER = new ExecutionTargetId("hdr-worker");
  private static final ExecutionTargetId SDR_WORKER = new ExecutionTargetId("sdr-worker");
  private static final Pattern VIDEO_RANGE_ATTRIBUTE = Pattern.compile(",VIDEO-RANGE=(\\w+)");
  private static final Pattern CODECS_ATTRIBUTE = Pattern.compile("CODECS=\"([^\",]+)");
  private static final String MAIN_10_H265 = "hvc1.2.4.";

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeTranscodeExecutor workers = new FakeTranscodeExecutor();
  private final FakeMediaFileContainerInfoRepository probes =
      new FakeMediaFileContainerInfoRepository();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final FakeSegmentStore segments = new FakeSegmentStore();
  private StreamingRigFixture.StreamingRig rig;
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
    workers.setVideoCopySupportByWorker(
        List.of(
            WorkerVideoCopySupport.builder()
                .plannedOutput(true)
                .bitstreamRewrites(Set.of(BitstreamRewrite.values()))
                .dolbyVisionSampleEntries(Set.of(VideoSampleEntry.DVH1))
                .build()));
    playback =
        PlaybackSessionService.builder()
            .eventPublisher(_ -> {})
            .streamingService(streaming())
            .authorityGate(new FakePlaybackAuthorityGate())
            .executor(Runnable::run)
            .maximumFormatAttempts(4)
            .build();
  }

  // Each copy lists 720p, 480p and 360p below its own 1080p picture. Every entry announces one
  // range and none announces Dolby Vision, so a player reads them as one ladder.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesWhoseRangeAWorkerEncodes")
  @DisplayName(
      "Should encode every lower variant in the copy's range and bit depth when a verified encoder"
          + " writes that range and the client plays it")
  void shouldEncodeEveryLowerVariantInTheCopysRangeAndBitDepthWhenAVerifiedEncoderWritesThatRange(
      CopyLadder ladder) {
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(x265(EVERY_ENCODE_FEATURE)));
    probes.setDefaultProbe(ladder.source());

    var copy = accepted(playback.start(startAuto(ladder.client())));

    var entries = streamInfEntries(copy);
    var lowerEntries = entries.subList(1, entries.size());
    assertThat(sessionOf(copy).getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(sessionOf(copy).getVariants())
        .extracting(QualityVariant::label)
        .containsExactly("720p", "480p", "360p");
    assertThat(entries)
        .hasSize(4)
        .extracting(LowerVariantsInTheCopysRangeTest::rangeOf)
        .containsOnly(ladder.range());
    assertThat(entries).noneMatch(entry -> entry.contains("SUPPLEMENTAL-CODECS"));
    assertThat(videoCodecOf(entries.getFirst())).startsWith("hvc1.");
    assertThat(lowerEntries)
        .hasSize(3)
        .allSatisfy(entry -> assertThat(videoCodecOf(entry)).startsWith(MAIN_10_H265));
    assertThat(sessionOf(copy).decisionOf("720p").plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.Encode.class,
            encode -> {
              assertThat(encode.codecProfile()).contains(EncodeCodecProfile.HEVC_MAIN_10);
              assertThat(encode.dynamicRange())
                  .isEqualTo(
                      VideoPlaybackFactsFixture.rangeWithoutEnhancement(
                          CompatibleBase.valueOf(ladder.range().name())));
              assertThat(encode.bitDepth()).isEqualTo(10);
              assertThat(encode.masteringDisplay().isPresent())
                  .isEqualTo(ladder.range() == VideoRange.PQ);
            });
  }

  static Stream<CopyLadder> copiesWhoseRangeAWorkerEncodes() {
    return Stream.of(
        ladder("HDR10").source(hevc(CompatibleBase.PQ)).range(VideoRange.PQ).build(),
        ladder("HDR10+")
            .source(
                hevcSource(
                    new VideoDynamicRange(
                        CompatibleBase.PQ, Presence.PRESENT, new DolbyVision.Absent())))
            .range(VideoRange.PQ)
            .build(),
        ladder("HLG").source(hevc(CompatibleBase.HLG)).range(VideoRange.HLG).build(),
        ladder("10-bit SDR").source(hevc(CompatibleBase.SDR)).range(VideoRange.SDR).build(),
        ladder("Dolby Vision 8.1, copied without it for a client that declares PQ alone")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1))
            .range(VideoRange.PQ)
            .client(clientThatPlaysNoDolbyVision())
            .build(),
        ladder("Dolby Vision 8.2 on a 10-bit SDR base")
            .source(
                hevcSource(
                    new VideoDynamicRange(
                        CompatibleBase.SDR,
                        Presence.ABSENT,
                        new DolbyVision.Present(
                            Optional.of(
                                DolbyVisionConfiguration.builder()
                                    .profile(OptionalInt.of(8))
                                    .level(OptionalInt.of(DOLBY_VISION_LEVEL))
                                    .baseLayerCompatibilityId(OptionalInt.of(2))
                                    .baseLayer(true)
                                    .rpu(true)
                                    .build()),
                            Presence.PRESENT))))
            .range(VideoRange.SDR)
            .build());
  }

  // No encode writes Dolby Vision (ADR 0013), so a lower variant would take a television out of
  // its Dolby Vision mode. The encoder and the client here list lower variants beside an HDR10
  // copy.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesThatThePlaylistDescribesAsDolbyVision")
  @DisplayName("Should list the copy alone when the playlist describes the copy as Dolby Vision")
  void shouldListTheCopyAloneWhenThePlaylistDescribesTheCopyAsDolbyVision(
      DolbyVisionCopy dolbyVision) {
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(x265(EVERY_ENCODE_FEATURE)));
    probes.setDefaultProbe(dolbyVision.source());

    var copy = accepted(playback.start(startAuto(clientThatPlaysEveryRange())));

    assertThatTheCopyPlaysAlone(copy);
    assertThat(streamInfEntries(copy))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(videoCodecOf(entry)).startsWith(dolbyVision.sampleEntry() + ".");
              assertThat(supplementalCodecsOf(entry)).isEqualTo(dolbyVision.supplementalCodecs());
              assertThat(rangeOf(entry)).isEqualTo(dolbyVision.range());
            });
  }

  static Stream<DolbyVisionCopy> copiesThatThePlaylistDescribesAsDolbyVision() {
    return Stream.of(
        dolbyVisionCopy("Dolby Vision 8.1")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1))
            .range(VideoRange.PQ)
            .supplementalCodecs(Optional.of("dvh1.08.06/db1p"))
            .build(),
        dolbyVisionCopy("Dolby Vision 8.4")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_4))
            .range(VideoRange.HLG)
            .supplementalCodecs(Optional.of("dvh1.08.06/db4h"))
            .build(),
        dolbyVisionCopy("single-track Dolby Vision 7, converted to 8.1")
            .source(singleTrackProfile7Source())
            .range(VideoRange.PQ)
            .supplementalCodecs(Optional.of("dvh1.08.06/db1p"))
            .build(),
        dolbyVisionCopy("Dolby Vision 5 in its own sample entry")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_5))
            .range(VideoRange.PQ)
            .sampleEntry("dvh1")
            .build());
  }

  // #58: H.265 Main 10 holds only 10 bits, so a lower variant in it beside an HDR10 copy is a PQ
  // encode, and its playlist entry names Main 10 and PQ as its job writes them.
  @Test
  @DisplayName(
      "Should pair H.265 Main 10 with a 10-bit PQ encode in every lower variant when the lower"
          + " variants beside an HDR10 copy encode H.265")
  void shouldPairH265Main10WithATenBitPqEncodeInEveryLowerVariantWhenTheyEncodeH265() {
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(x265(EVERY_ENCODE_FEATURE), encoder("h264", "libx264")));
    probes.setDefaultProbe(hevc(CompatibleBase.PQ));

    var copy =
        accepted(
            playback.start(
                startAuto(
                    clientWith(
                        hevcEntryBuilder().compatibleBases(EVERY_RANGE).build(),
                        h264EntryBuilder().build()))));

    var session = sessionOf(copy);
    assertThat(session.getVariants())
        .isNotEmpty()
        .allSatisfy(
            variant -> {
              var decision = session.decisionOf(variant.label());
              assertThat(decision.encodeCodecProfile()).contains(EncodeCodecProfile.HEVC_MAIN_10);
              assertThat(decision.videoEncodeFormat()).isEqualTo(EncodeFormat.hdr(VideoRange.PQ));
            });
    var entries = streamInfEntries(copy);
    assertThat(entries)
        .extracting(LowerVariantsInTheCopysRangeTest::rangeOf)
        .containsOnly(VideoRange.PQ);
    assertThat(entries.subList(1, entries.size()))
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(videoCodecOf(entry)).startsWith(MAIN_10_H265));
  }

  // ADR 0049: an output fits one capability entry. Each lower variant is sized by an entry that
  // plays the lower variants' own range and bit depth, so a client whose entry for the copy's codec
  // family plays PQ alone still gets PQ lower variants.
  @Test
  @DisplayName(
      "Should fit every lower variant to the capability entry that plays the copy's range when no"
          + " entry of the copy's codec family plays SDR")
  void shouldFitEveryLowerVariantToTheEntryThatPlaysTheCopysRangeWhenNoEntryOfItsFamilyPlaysSdr() {
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(x265(EVERY_ENCODE_FEATURE)));
    probes.setDefaultProbe(hevc(CompatibleBase.PQ));

    var copy =
        accepted(
            playback.start(
                startAuto(
                    clientWith(
                        hevcEntryBuilder().compatibleBases(List.of(CompatibleBase.PQ)).build()))));

    var session = sessionOf(copy);
    assertThat(session.getVariants())
        .extracting(QualityVariant::label)
        .containsExactly("720p", "480p", "360p");
    assertThat(session.decisionOf("720p").videoEncodeFormat())
        .isEqualTo(EncodeFormat.hdr(VideoRange.PQ));
    assertThat(streamInfEntries(copy))
        .hasSize(4)
        .extracting(LowerVariantsInTheCopysRangeTest::rangeOf)
        .containsOnly(VideoRange.PQ);
  }

  @Test
  @DisplayName(
      "Should plan the copy's PQ range with the source's static metadata in a lower variant's job"
          + " when the player first requests one of its segments")
  void shouldPlanTheCopysPqRangeInALowerVariantsJobWhenThePlayerFirstRequestsOneOfItsSegments() {
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(x265(EVERY_ENCODE_FEATURE)));
    probes.setDefaultProbe(
        source(
            hevcFacts()
                .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
                .masteringDisplay(Optional.of(VideoPlaybackFactsFixture.MASTERING_DISPLAY))
                .contentLightLevel(Optional.of(VideoPlaybackFactsFixture.CONTENT_LIGHT_LEVEL))
                .build()));
    var copy = accepted(playback.start(startAuto(clientThatPlaysEveryRange())));
    var sessionId = sessionOf(copy).getSessionId();

    rig.lifecycle().prepareRequestedVariant(sessionId, "480p", "480p/segment0.m4s");

    assertThat(workers.getStartedRequests())
        .hasSize(2)
        .last()
        .satisfies(
            lower -> {
              assertThat(lower.variantLabel()).isEqualTo("480p");
              assertThat(lower.transcodeDecision().plannedVideoOutput())
                  .isEqualTo(
                      PlannedVideoOutput.Encode.builder()
                          .codecProfile(Optional.of(EncodeCodecProfile.HEVC_MAIN_10))
                          .dynamicRange(
                              VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
                          .bitDepth(10)
                          .chromaSubsampling(ChromaSubsampling.CHROMA_420)
                          .masteringDisplay(
                              Optional.of(VideoPlaybackFactsFixture.MASTERING_DISPLAY))
                          .contentLightLevel(
                              Optional.of(VideoPlaybackFactsFixture.CONTENT_LIGHT_LEVEL))
                          .build());
            });
  }

  // The web player moves only between variants of one codec family and one range, so the lower
  // variants take the copy's codec family even when the codec preference puts another family
  // first: here a worker encodes AV1 in hardware, and the client plays AV1 in every range.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesBesideAPreferredAv1Encoder")
  @DisplayName(
      "Should encode every lower variant in the copy's codec family when the codec preference puts"
          + " another codec family first")
  void shouldEncodeEveryLowerVariantInTheCopysCodecFamilyWhenThePreferencePutsAnotherFamilyFirst(
      CopyFamily copyFamily) {
    workerVerifies(hardwareAv1(), x265(EVERY_ENCODE_FEATURE), encoder("h264", "libx264"));
    probes.setDefaultProbe(copyFamily.source());

    var copy = accepted(playback.start(startAuto(clientThatPlaysEveryRange())));

    var session = sessionOf(copy);
    var entries = streamInfEntries(copy);
    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(session.getVariants())
        .extracting(QualityVariant::label)
        .containsExactly("720p", "480p", "360p");
    assertThat(session.getVariants())
        .allSatisfy(
            variant ->
                assertThat(session.decisionOf(variant.label()).encodeCodecProfile())
                    .contains(copyFamily.lowerVariantProfile()));
    assertThat(entries)
        .hasSize(4)
        .allSatisfy(
            entry -> assertThat(videoCodecOf(entry)).startsWith(copyFamily.sampleEntry() + "."));
    assertThat(entries)
        .extracting(LowerVariantsInTheCopysRangeTest::rangeOf)
        .containsOnly(copyFamily.range());
  }

  static Stream<CopyFamily> copiesBesideAPreferredAv1Encoder() {
    return Stream.of(
        copyFamily("an H.265 HDR10 copy")
            .source(hevc(CompatibleBase.PQ))
            .lowerVariantProfile(EncodeCodecProfile.HEVC_MAIN_10)
            .sampleEntry("hvc1")
            .range(VideoRange.PQ)
            .build(),
        copyFamily("an H.264 SDR copy")
            .source(defaultProbeBuilder().audioChannels(OptionalInt.of(2)).build())
            .lowerVariantProfile(EncodeCodecProfile.H264_HIGH)
            .sampleEntry("avc1")
            .range(VideoRange.SDR)
            .build());
  }

  // The fallback is no lower variants, never ones in another codec family or in SDR: the copy
  // plays alone, as it does in a fixed quality. The hardware AV1 encoder here writes every range,
  // and the client plays AV1 in every range.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesWhoseCodecFamilyNoVerifiedEncoderWritesInTheirRange")
  @DisplayName(
      "Should list the copy alone when no verified encoder writes the copy's codec family in the"
          + " copy's range and bit depth")
  void shouldListTheCopyAloneWhenNoVerifiedEncoderWritesItsCodecFamilyInItsRangeAndBitDepth(
      CopyAlone copyAlone) {
    workerVerifies(copyAlone.encoders().toArray(WorkerVerifiedEncoder[]::new));
    probes.setDefaultProbe(copyAlone.source());

    var copy = accepted(playback.start(startAuto(copyAlone.client())));

    assertThatTheCopyPlaysAlone(copy);
  }

  static Stream<CopyAlone> copiesWhoseCodecFamilyNoVerifiedEncoderWritesInTheirRange() {
    return Stream.of(
        copyAlone("an H.265 HDR10 copy beside AV1 and H.264 encoders")
            .source(hevc(CompatibleBase.PQ))
            .encoder(hardwareAv1())
            .encoder(encoder("h264", "libx264"))
            .build(),
        copyAlone("an H.265 HDR10 copy beside an H.265 encoder without PQ")
            .source(hevc(CompatibleBase.PQ))
            .encoder(hardwareAv1())
            .encoder(x265(without(VideoEncodeFeature.PQ)))
            .build(),
        copyAlone("an H.265 HLG copy beside an H.265 encoder without HLG")
            .source(hevc(CompatibleBase.HLG))
            .encoder(hardwareAv1())
            .encoder(x265(without(VideoEncodeFeature.HLG)))
            .build(),
        copyAlone("a 10-bit SDR H.265 copy beside an H.265 encoder without 10 bits")
            .source(hevc(CompatibleBase.SDR))
            .encoder(hardwareAv1())
            .encoder(x265(without(VideoEncodeFeature.TEN_BIT)))
            .build(),
        copyAlone("Dolby Vision 8.1, copied without it, beside an H.265 encoder without PQ")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1))
            .client(clientThatPlaysNoDolbyVision())
            .encoder(hardwareAv1())
            .encoder(x265(without(VideoEncodeFeature.PQ)))
            .build(),
        copyAlone("an H.264 SDR copy beside AV1 and H.265 encoders")
            .source(defaultProbeBuilder().audioChannels(OptionalInt.of(2)).build())
            .encoder(hardwareAv1())
            .encoder(x265(EVERY_ENCODE_FEATURE))
            .build());
  }

  // An encode forces H.264 High (ADR 0013), which an entry that lists H.264 Main alone refuses,
  // while it accepts the Main copy. The client plays AV1, which the worker encodes in hardware.
  @Test
  @DisplayName(
      "Should list the copy alone when no capability entry accepts an encode in the copy's codec"
          + " family")
  void shouldListTheCopyAloneWhenNoCapabilityEntryAcceptsAnEncodeInTheCopysCodecFamily() {
    workerVerifies(hardwareAv1(), x265(EVERY_ENCODE_FEATURE), encoder("h264", "libx264"));
    probes.setDefaultProbe(
        defaultProbeBuilder()
            .audioChannels(OptionalInt.of(2))
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .streamIndex(0)
                        .frameRateRule(
                            VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                        .codecString(Optional.of("avc1.4d4028"))
                        .videoProperties(
                            Optional.of(
                                VideoPlaybackFactsFixture.h264HighPropertiesBuilder()
                                    .codecProfile(Optional.of(VideoCodecProfile.H264_MAIN))
                                    .build()))
                        .build()))
            .build());
    var mainAlone =
        h264EntryBuilder()
            .codecProfiles(
                List.of(
                    VideoCodecProfileCapability.builder()
                        .profile(VideoCodecProfile.H264_MAIN)
                        .maxLevel(52)
                        .build()))
            .build();

    var copy =
        accepted(
            playback.start(
                startAuto(
                    defaultDeclarationBuilder()
                        .video(
                            List.of(
                                mainAlone, av1EntryBuilder().compatibleBases(EVERY_RANGE).build()))
                        .build())));

    assertThatTheCopyPlaysAlone(copy);
  }

  // ADR 0009: a stream session without a copy encodes its whole ladder in the codec family that
  // the codec preference puts first. The client here declares no H.264 entry, so the H.264 source
  // is not copied.
  @Test
  @DisplayName(
      "Should encode the whole ladder in the preferred codec family when the stream session copies"
          + " no video")
  void shouldEncodeTheWholeLadderInThePreferredCodecFamilyWhenTheStreamSessionCopiesNoVideo() {
    workerVerifies(hardwareAv1(), x265(EVERY_ENCODE_FEATURE), encoder("h264", "libx264"));
    probes.setDefaultProbe(defaultProbeBuilder().audioChannels(OptionalInt.of(2)).build());

    var encode =
        accepted(
            playback.start(
                startAuto(
                    defaultDeclarationBuilder()
                        .video(
                            List.of(
                                hevcEntryBuilder().compatibleBases(EVERY_RANGE).build(),
                                av1EntryBuilder().compatibleBases(EVERY_RANGE).build()))
                        .build())));

    var session = sessionOf(encode);
    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(session.getTranscodeDecision().encodeCodecProfile())
        .contains(EncodeCodecProfile.AV1_MAIN);
    assertThat(streamInfEntries(encode))
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(videoCodecOf(entry)).startsWith("av01."));
  }

  // The fallback is no lower variants, never SDR ones: the copy plays alone, as it does in a fixed
  // quality.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesWhoseRangeNoVerifiedEncoderWrites")
  @DisplayName(
      "Should list the copy alone when no verified encoder lists the encode features that the"
          + " copy's range and bit depth need")
  void shouldListTheCopyAloneWhenNoVerifiedEncoderListsTheEncodeFeaturesThatItsRangeNeeds(
      CopyAlone copyAlone) {
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, copyAlone.encoders());
    probes.setDefaultProbe(copyAlone.source());

    var copy = accepted(playback.start(startAuto(copyAlone.client())));

    assertThatTheCopyPlaysAlone(copy);
  }

  static Stream<CopyAlone> copiesWhoseRangeNoVerifiedEncoderWrites() {
    return Stream.of(
        copyAlone("HDR10 beside encoders that list no encode feature")
            .source(hevc(CompatibleBase.PQ))
            .encoder(x265(Set.of()))
            .encoder(encoder("h264", "libx264"))
            .build());
  }

  // ADR 0050: a lower variant encodes only once the player requests it, so a busy worker's verified
  // encoder counts when the server lists it. Its job then runs only where an encoder lists the
  // features, and no SDR encode on a free worker replaces it.
  @Test
  @DisplayName(
      "Should list a busy worker's PQ lower variants and start one only on that worker when the"
          + " free worker's encoder writes 8-bit SDR alone")
  void shouldStartABusyWorkersPqLowerVariantOnlyOnThatWorkerWhenTheFreeWorkerWritesSdrAlone() {
    workers.setExecutionTargets(List.of(SDR_WORKER, HDR_WORKER));
    workers.setTargetEncoders(SDR_WORKER, List.of(encoder("h264", "libx264")));
    workers.setTargetEncoders(HDR_WORKER, List.of(x265(EVERY_ENCODE_FEATURE)));
    workers.limitJobSlots(HDR_WORKER, 0);
    probes.setDefaultProbe(hevc(CompatibleBase.PQ));
    var copy = accepted(playback.start(startAuto(clientThatPlaysEveryRange())));
    var sessionId = sessionOf(copy).getSessionId();

    rig.lifecycle().prepareRequestedVariant(sessionId, "720p", "720p/segment0.m4s");
    var startedWhileBusy = workers.getStartedRequests().size();
    var runningWhileBusy = workers.isRunning(sessionId, "720p");
    workers.limitJobSlots(HDR_WORKER, 1);
    rig.lifecycle().prepareRequestedVariant(sessionId, "720p", "720p/segment0.m4s");

    assertThat(streamInfEntries(copy))
        .hasSize(4)
        .extracting(LowerVariantsInTheCopysRangeTest::rangeOf)
        .containsOnly(VideoRange.PQ);
    assertThat(startedWhileBusy).isEqualTo(1);
    assertThat(runningWhileBusy).isFalse();
    assertThat(workers.runningExecutionTarget(sessionId, "720p")).contains(HDR_WORKER);
    assertThat(workers.getStartedRequests().getLast().transcodeDecision().videoEncodeFormat())
        .isEqualTo(EncodeFormat.hdr(VideoRange.PQ));
  }

  // A television stays in HDR as long as the copy plays: the player's failure on an encoded lower
  // variant says nothing about the copy. A stream session that lists the copy alone replaces the
  // stream session, in the same range and without spending a format attempt, and the player loads
  // it as it loads any replacement.
  @Test
  @DisplayName(
      "Should replace the stream session with the HDR copy alone when the player reports a format"
          + " failure on a lower variant")
  void shouldReplaceTheStreamSessionWithTheHdrCopyAloneWhenThePlayerFailsOnALowerVariant() {
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = startAnHdr10CopyBesideLowerVariants(command);
    var replaced = sessionOf(copy);
    rig.lifecycle().prepareRequestedVariant(replaced.getSessionId(), "720p", "720p/segment0.m4s");

    var afterTheFailure = rejectCurrentFormatAttempt(copy, command);

    assertThatTheCopyAloneReplaced(afterTheFailure, replaced);
    assertThatTheReplacedStreamSessionEnded(replaced.getSessionId());
  }

  // The worker contract says that every job attempt with the same decision repeats these failures.
  // The copy runs another decision, so it plays on in the stream session that replaces this one.
  @ParameterizedTest
  @EnumSource(
      value = TranscodeFailure.class,
      names = {"OUTPUT_MISMATCH", "INVALID_SPECIFICATION", "SEGMENT_CAP_EXCEEDED"})
  @DisplayName(
      "Should replace the stream session with the HDR copy alone when a lower variant's job"
          + " attempts fail for good")
  void shouldReplaceTheStreamSessionWithTheHdrCopyAloneWhenALowerVariantsJobAttemptsFailForGood(
      TranscodeFailure failure) {
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = startAnHdr10CopyBesideLowerVariants(command);
    var replaced = sessionOf(copy);
    var replacedId = replaced.getSessionId();
    rig.lifecycle().prepareRequestedVariant(replacedId, "720p", "720p/segment0.m4s");

    workers.failTerminally(replacedId, "720p", failure);
    playback.onTerminalTranscodeFailure(terminalFailure(replacedId, "720p", failure));

    var afterTheFailure = snapshotOf(copy, command);
    assertThatTheCopyAloneReplaced(afterTheFailure, replaced);
    assertThatTheReplacedStreamSessionEnded(replacedId);
    assertThat(workers.isRunning(sessionOf(afterTheFailure).getSessionId(), "default")).isTrue();
  }

  // Two lower variants can fail close together, and a worker's report can arrive after the
  // replacement. It names the replaced stream session, so nothing more changes.
  @Test
  @DisplayName(
      "Should replace the stream session once when a second lower variant's job attempts fail for"
          + " good after the first replaced it")
  void shouldReplaceTheStreamSessionOnceWhenASecondLowerVariantFailsAfterTheFirstReplacedIt() {
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = startAnHdr10CopyBesideLowerVariants(command);
    var replaced = sessionOf(copy);
    var replacedId = replaced.getSessionId();

    playback.onTerminalTranscodeFailure(
        terminalFailure(replacedId, "720p", TranscodeFailure.OUTPUT_MISMATCH));
    var copyAloneId = snapshotOf(copy, command).currentFormatAttempt().streamSessionId();
    playback.onTerminalTranscodeFailure(
        terminalFailure(replacedId, "480p", TranscodeFailure.OUTPUT_MISMATCH));

    var afterTheSecond = snapshotOf(copy, command);
    assertThat(afterTheSecond.currentFormatAttempt().streamSessionId()).isEqualTo(copyAloneId);
    assertThat(afterTheSecond.decisions()).hasSize(2);
    assertThatTheCopyAloneReplaced(afterTheSecond, replaced);
  }

  // ADR 0044 continues a failed hardware encode in software in a new format attempt that encodes
  // every variant, which would end the copy. A lower variant's hardware encoder failure replaces
  // the stream session with the copy alone instead.
  @Test
  @DisplayName(
      "Should replace the stream session with the HDR copy alone when the hardware encoder of the"
          + " requested lower variant fails")
  void shouldReplaceTheStreamSessionWithTheHdrCopyAloneWhenALowerVariantsHardwareEncoderFails() {
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = startAnHdr10CopyBesideLowerVariants(command);
    var replaced = sessionOf(copy);
    var replacedId = replaced.getSessionId();
    rig.lifecycle().prepareRequestedVariant(replacedId, "720p", "720p/segment0.m4s");

    workers.failHardwareEncoder(replacedId, "720p");
    playback.onRequestedVariantHardwareEncoderFailed(
        RequestedVariantHardwareEncoderFailedEvent.builder()
            .streamSessionId(replacedId)
            .variantLabel("720p")
            .build());

    assertThatTheCopyAloneReplaced(snapshotOf(copy, command), replaced);
    assertThatTheReplacedStreamSessionEnded(replacedId);
  }

  // The request that meets the failed hardware encoder replaces the stream session on its way out
  // and ends as unrecoverable, as a request that meets ADR 0044's software continuation does. The
  // player then finds the replacement in the playback status.
  @Test
  @DisplayName(
      "Should replace the stream session with the HDR copy alone when a request meets a lower"
          + " variant's failed hardware encoder")
  void shouldReplaceTheStreamSessionWhenARequestMeetsALowerVariantsFailedHardwareEncoder() {
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = startAnHdr10CopyBesideLowerVariants(command);
    var replaced = sessionOf(copy);
    var replacedId = replaced.getSessionId();
    rig.lifecycle().prepareRequestedVariant(replacedId, "720p", "720p/segment0.m4s");
    workers.failHardwareEncoder(replacedId, "720p");

    var lowerVariantSegment = rig.coordinator().deliver(replacedId, "720p", "720p/segment0.m4s");

    assertThat(lowerVariantSegment).isEqualTo(new SegmentDelivery.Unrecoverable());
    assertThatTheCopyAloneReplaced(snapshotOf(copy, command), replaced);
  }

  // ADR 0050: a lower variant fails for good when its job attempts fail on every worker that can
  // run it, as when a failure repeats. The request that finds its recovery exhausted replaces the
  // stream session on its way out.
  @Test
  @DisplayName(
      "Should replace the stream session with the HDR copy alone when a lower variant's job attempt"
          + " fails on every worker that can run it")
  void shouldReplaceTheStreamSessionWhenALowerVariantsJobAttemptFailsOnEveryWorker() {
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = startAnHdr10CopyBesideLowerVariants(command);
    var replaced = sessionOf(copy);
    var replacedId = replaced.getSessionId();
    rig.lifecycle().prepareRequestedVariant(replacedId, "720p", "720p/segment0.m4s");
    workers.markDead(replacedId, "720p");
    workers.refuseTarget(ExecutionTargetId.LOCAL);

    var lowerVariantSegment = rig.coordinator().deliver(replacedId, "720p", "720p/segment0.m4s");

    assertThat(lowerVariantSegment).isEqualTo(new SegmentDelivery.Unrecoverable());
    assertThatTheCopyAloneReplaced(snapshotOf(copy, command), replaced);
  }

  // ADR 0052: the copy's own failure still moves Auto to an HDR encode in a new stream session, and
  // only that encode's failure gives HDR up.
  @Test
  @DisplayName(
      "Should start an HDR encode in a new stream session when the player reports a format failure"
          + " on the copy itself beside lower variants")
  void shouldStartAnHdrEncodeInANewStreamSessionWhenThePlayerFailsOnTheCopyItself() {
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = startAnHdr10CopyBesideLowerVariants(command);
    var copySessionId = sessionOf(copy).getSessionId();
    rig.lifecycle().prepareRequestedVariant(copySessionId, "default", "segment0.m4s");

    var afterTheCopy = rejectCurrentFormatAttempt(copy, command);

    assertThat(afterTheCopy.currentFormatAttempt().streamSessionId()).isNotEqualTo(copySessionId);
    assertThat(afterTheCopy.formatAttemptCount()).isEqualTo(2);
    assertThat(sessionOf(afterTheCopy).getTranscodeDecision())
        .satisfies(
            decision -> {
              assertThat(decision.transcodeMode().copiesVideo()).isFalse();
              assertThat(decision.videoEncodeFormat()).isEqualTo(EncodeFormat.hdr(VideoRange.PQ));
            });
  }

  // A lower variant's job can fail while the player still plays that variant's segments, so the
  // player's report can follow the replacement. It names the replaced stream session, which no
  // longer plays, so it changes nothing.
  @Test
  @DisplayName(
      "Should keep the copy alone when the player reports a format failure on the stream session"
          + " that a lower variant's job failure replaced")
  void shouldKeepTheCopyAloneWhenThePlayerFailsOnTheStreamSessionThatALowerVariantReplaced() {
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = startAnHdr10CopyBesideLowerVariants(command);
    var replaced = sessionOf(copy);
    var replacedId = replaced.getSessionId();
    rig.lifecycle().prepareRequestedVariant(replacedId, "720p", "720p/segment0.m4s");
    workers.failTerminally(replacedId, "720p", TranscodeFailure.OUTPUT_MISMATCH);
    playback.onTerminalTranscodeFailure(
        terminalFailure(replacedId, "720p", TranscodeFailure.OUTPUT_MISMATCH));

    var afterTheReport = rejectCurrentFormatAttempt(copy, command);

    assertThatTheCopyAloneReplaced(afterTheReport, replaced);
  }

  // The copy alone fails as any copy does, so its failure takes ADR 0052's recovery: an HDR encode
  // first, in a format attempt that counts.
  @Test
  @DisplayName(
      "Should recover as a failed copy does when the player reports a format failure on the copy"
          + " alone that replaced the stream session")
  void shouldRecoverAsAFailedCopyDoesWhenThePlayerFailsOnTheCopyAloneThatReplacedTheSession() {
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = startAnHdr10CopyBesideLowerVariants(command);
    var replacedId = sessionOf(copy).getSessionId();
    rig.lifecycle().prepareRequestedVariant(replacedId, "720p", "720p/segment0.m4s");
    var copyAlone = rejectCurrentFormatAttempt(copy, command);
    var copyAloneId = sessionOf(copyAlone).getSessionId();
    rig.lifecycle().prepareRequestedVariant(copyAloneId, "default", "segment0.m4s");

    var afterTheCopy = rejectCurrentFormatAttempt(copyAlone, command);

    assertThat(afterTheCopy.currentFormatAttempt().streamSessionId()).isNotEqualTo(copyAloneId);
    assertThat(afterTheCopy.formatAttemptCount()).isEqualTo(2);
    assertThat(sessionOf(afterTheCopy).getTranscodeDecision().videoEncodeFormat())
        .isEqualTo(EncodeFormat.hdr(VideoRange.PQ));
    assertThat(afterTheCopy.decisions().getLast().reasons())
        .first()
        .isEqualTo(DecisionReason.COPY_FAILED);
  }

  // The replaced stream session ends with every job attempt in it. The initialization request
  // starts 480p beside the 720p that the player plays.
  @Test
  @DisplayName(
      "Should stop every running lower variant when the player's format failure on a lower variant"
          + " replaces the stream session")
  void shouldStopEveryRunningLowerVariantWhenThePlayersFailureOnALowerVariantReplacesTheSession() {
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = startAnHdr10CopyBesideLowerVariants(command);
    var replacedId = sessionOf(copy).getSessionId();
    rig.lifecycle().prepareRequestedVariant(replacedId, "720p", "720p/segment0.m4s");
    rig.lifecycle().prepareRequestedVariant(replacedId, "480p", "480p/init.mp4");
    var runningBeforeTheFailure = runningVariants(replacedId);

    rejectCurrentFormatAttempt(copy, command);

    assertThat(runningBeforeTheFailure).containsExactly("720p", "480p");
    assertThat(runningVariants(replacedId)).isEmpty();
  }

  // ADR 0052: after the client fails a Dolby Vision copy, only the copy with Dolby Vision stripped
  // may follow, and it lists 10-bit PQ lower variants. When one of them fails, the copy alone that
  // replaces it strips Dolby Vision too, instead of offering the copy that failed again.
  @Test
  @DisplayName(
      "Should strip Dolby Vision from the copy alone when a lower variant fails beside the copy"
          + " that followed a failed Dolby Vision copy")
  void shouldStripDolbyVisionFromTheCopyAloneWhenALowerVariantFailsAfterADolbyVisionCopyFailed() {
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(x265(EVERY_ENCODE_FEATURE), encoder("h264", "libx264")));
    probes.setDefaultProbe(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1));
    var command = startAuto(clientThatPlaysEveryRange());
    var dolbyVisionCopy = accepted(playback.start(command));
    var stripped = rejectCurrentFormatAttempt(dolbyVisionCopy, command);
    var replaced = sessionOf(stripped);
    rig.lifecycle().prepareRequestedVariant(replaced.getSessionId(), "720p", "720p/segment0.m4s");

    var afterTheLowerVariant = rejectCurrentFormatAttempt(stripped, command);

    assertThat(replaced.getTranscodeDecision().videoBitstreamRewrites())
        .contains(BitstreamRewrite.STRIP_DOLBY_VISION);
    assertThat(replaced.getVariants())
        .extracting(QualityVariant::label)
        .containsExactly("720p", "480p", "360p");
    var copyAlone = sessionOf(afterTheLowerVariant);
    assertThat(copyAlone.getSessionId()).isNotEqualTo(replaced.getSessionId());
    assertThat(copyAlone.getTranscodeDecision().plannedVideoOutput())
        .isEqualTo(replaced.getTranscodeDecision().plannedVideoOutput());
    assertThat(copyAlone.getVariants()).isEmpty();
    assertThat(afterTheLowerVariant.formatAttemptCount()).isEqualTo(2);
  }

  // Recovery reads the failure that each variant's own decision repeats, so a lower variant's
  // failure ends only that variant's recovery.
  @Test
  @DisplayName(
      "Should end recovery for a lower variant and keep recovering the copy when only the lower"
          + " variant's job attempts fail for good")
  void shouldEndRecoveryForALowerVariantAndKeepTheCopysWhenOnlyTheLowerVariantFailsForGood() {
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = startAnHdr10CopyBesideLowerVariants(command);
    var sessionId = sessionOf(copy).getSessionId();
    var lowerVariantRequest =
        rig.lifecycle().prepareRequestedVariant(sessionId, "720p", "720p/segment0.m4s");
    workers.failTerminally(sessionId, "720p", TranscodeFailure.OUTPUT_MISMATCH);

    var lowerVariantRecovery = rig.lifecycle().recover(lowerVariantRequest);
    var copyRequest = rig.lifecycle().prepareRequestedVariant(sessionId, "default", "segment0.m4s");

    assertThat(lowerVariantRecovery).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);
    assertThat(rig.lifecycle().recover(copyRequest))
        .isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
  }

  // The upload budget leaves the copy out of the playlist that the player read, so it plays the
  // lower variants alone. The copy alone that replaces the stream session is created as any stream
  // session is, so the budget sizes it again: the copy is its lowest variant, so it stays, and the
  // budget's start rule starts it.
  @Test
  @DisplayName(
      "Should create the copy alone through the upload budget when a lower variant fails while the"
          + " budget leaves the copy out")
  void shouldCreateTheCopyAloneThroughTheUploadBudgetWhenALowerVariantFailsWhileItLeavesItOut() {
    var settings = ServerSettingsFixture.defaultServerSettings();
    var budgeted = budgetedPlayback(settings);
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(encoder("h264", "libx264")));
    probes.setDefaultProbe(defaultProbeBuilder().audioChannels(OptionalInt.of(2)).build());
    accepted(budgeted.playback().start(startAuto(clientThatPlaysEveryRange())));
    accepted(
        settings.changeUploadBudget(
            AuthenticatedIdentityFixture.accountScopedBuilder().build(), Optional.of(11)));
    var command = startAuto(clientThatPlaysEveryRange());
    var second = accepted(budgeted.playback().start(command));
    var replacedId = sessionOf(second).getSessionId();
    var playlists = new HlsPlaylistService(PROPERTIES, budgeted.budget());
    var servedFirst = listedUris(playlists.generateMultivariantPlaylist(sessionOf(second), "t"));
    rig.lifecycle().prepareRequestedVariant(replacedId, "720p", "720p/segment0.m4s");

    workers.failTerminally(replacedId, "720p", TranscodeFailure.SEGMENT_CAP_EXCEEDED);
    budgeted
        .playback()
        .onTerminalTranscodeFailure(
            terminalFailure(replacedId, "720p", TranscodeFailure.SEGMENT_CAP_EXCEEDED));

    var after =
        budgeted
            .playback()
            .access(
                PlaybackSessionRequest.builder()
                    .playbackSessionId(second.playbackSessionId())
                    .identity(command.identity())
                    .build())
            .orElseThrow();
    var copyAlone = sessionOf(after);
    assertThat(servedFirst)
        .containsExactly("720p/stream.m3u8?t=t", "480p/stream.m3u8?t=t", "360p/stream.m3u8?t=t");
    assertThat(copyAlone.getSessionId()).isNotEqualTo(replacedId);
    assertThat(after.formatAttemptCount()).isEqualTo(1);
    assertThat(listedUris(playlists.generateMultivariantPlaylist(copyAlone, "t")))
        .containsExactly("stream.m3u8?t=t");
    assertThat(workers.isRunning(copyAlone.getSessionId(), "default")).isTrue();
  }

  // ADR 0050 for an AV1 copy: a hardware AV1 encoder that writes every range runs beside H.265 and
  // H.264 encoders, and the client plays AV1 in every range at 10 bits.
  @ParameterizedTest(name = "{0}")
  @MethodSource("av1Copies")
  @DisplayName(
      "Should encode every lower variant of an AV1 copy in AV1 in the copy's range and bit depth"
          + " when a verified AV1 encoder writes them")
  void shouldEncodeEveryLowerVariantOfAnAv1CopyInAv1WhenAVerifiedAv1EncoderWritesItsFormat(
      Av1Copy av1Copy) {
    workerVerifies(hardwareAv1(), x265(EVERY_ENCODE_FEATURE), encoder("h264", "libx264"));
    probes.setDefaultProbe(av1Copy.source());

    var copy = accepted(playback.start(startAuto(clientThatPlaysEveryRange())));

    var session = sessionOf(copy);
    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(session.getVariants())
        .extracting(QualityVariant::label)
        .containsExactly("720p", "480p", "360p");
    assertThat(session.getVariants())
        .allSatisfy(
            variant -> {
              var decision = session.decisionOf(variant.label());
              assertThat(decision.encodeCodecProfile()).contains(EncodeCodecProfile.AV1_MAIN);
              assertThat(decision.videoEncodeFormat()).isEqualTo(av1Copy.format());
            });
    assertThat(streamInfEntries(copy))
        .hasSize(4)
        .allSatisfy(
            entry -> {
              assertThat(videoCodecOf(entry)).startsWith("av01.");
              assertThat(rangeOf(entry)).isEqualTo(av1Copy.format().range());
            });
  }

  static Stream<Av1Copy> av1Copies() {
    return Stream.of(
        new Av1Copy(
            "an 8-bit SDR AV1 copy", av1Source(CompatibleBase.SDR, 8), EncodeFormat.SDR_8_BIT),
        new Av1Copy(
            "a 10-bit SDR AV1 copy", av1Source(CompatibleBase.SDR, 10), EncodeFormat.SDR_10_BIT),
        new Av1Copy(
            "an HDR10 AV1 copy",
            av1Source(CompatibleBase.PQ, 10),
            EncodeFormat.hdr(VideoRange.PQ)));
  }

  /** An AV1 copy with the format that its lower variants keep. */
  private record Av1Copy(
      @NonNull String name, @NonNull MediaProbe source, @NonNull EncodeFormat format) {

    @Override
    public String toString() {
      return name;
    }
  }

  private record BudgetedPlayback(PlaybackSessionService playback, ServerUploadBudget budget) {}

  // A playback service whose stream sessions the upload budget of these settings sizes (#796).
  private BudgetedPlayback budgetedPlayback(ServerSettingsService settings) {
    var registry = new FakeRuntimeStreamSessionRegistry();
    rig = rigBuilder(registry).serverSettings(settings).build();
    var budget = new ServerUploadBudget(settings, registry, rig.lifecycle(), PROPERTIES, meters);
    var budgeted =
        PlaybackSessionService.builder()
            .eventPublisher(_ -> {})
            .streamingService(streamingBuilder(registry).uploadBudget(budget).build())
            .authorityGate(new FakePlaybackAuthorityGate())
            .executor(Runnable::run)
            .maximumFormatAttempts(4)
            .build();
    return new BudgetedPlayback(budgeted, budget);
  }

  private static List<String> listedUris(String multivariantPlaylist) {
    return multivariantPlaylist.lines().filter(line -> !line.startsWith("#")).toList();
  }

  private List<String> runningVariants(UUID sessionId) {
    return Stream.of(StreamSession.defaultVariant(), "720p", "480p", "360p")
        .filter(label -> workers.isRunning(sessionId, label))
        .toList();
  }

  // A worker whose H.265 and H.264 encoders write every range, and an HDR10 source that Auto
  // copies above 720p, 480p and 360p in 10-bit PQ H.265.
  private PlaybackSessionSnapshot startAnHdr10CopyBesideLowerVariants(
      StartPlaybackSessionCommand command) {
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(x265(EVERY_ENCODE_FEATURE), encoder("h264", "libx264")));
    probes.setDefaultProbe(hevc(CompatibleBase.PQ));
    var copy = accepted(playback.start(command));
    assertThat(sessionOf(copy).getVariants())
        .extracting(QualityVariant::label)
        .containsExactly("720p", "480p", "360p");
    return copy;
  }

  // A stream session that lists the HDR copy alone replaces the copy's stream session: the same
  // planned copy, in PQ, in the same playback choice and without spending a format attempt. Its
  // decision names the lower variant's failure first, and no job attempt in another range started.
  private void assertThatTheCopyAloneReplaced(
      PlaybackSessionSnapshot snapshot, StreamSession replaced) {
    var copyAlone = sessionOf(snapshot);
    assertThat(copyAlone.getSessionId()).isNotEqualTo(replaced.getSessionId());
    assertThat(copyAlone.getTranscodeDecision().plannedVideoOutput())
        .isEqualTo(replaced.getTranscodeDecision().plannedVideoOutput());
    assertThat(copyAlone.getVideoRange()).isEqualTo(VideoRange.PQ);
    assertThatTheCopyPlaysAlone(snapshot);
    assertThat(snapshot.formatAttemptCount()).isEqualTo(1);
    assertThat(snapshot.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(snapshot.formatChange()).isEmpty();
    assertThat(snapshot.decisions())
        .last()
        .satisfies(
            decision -> {
              assertThat(decision.formatAttempt()).isEqualTo(2);
              assertThat(decision.streamSessionId()).isEqualTo(copyAlone.getSessionId());
              assertThat(decision.reasons()).first().isEqualTo(DecisionReason.LOWER_VARIANT_FAILED);
            });
    assertThat(streamInfEntries(snapshot))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(videoCodecOf(entry)).startsWith("hvc1.");
              assertThat(rangeOf(entry)).isEqualTo(VideoRange.PQ);
            });
    assertThat(workers.getStartedRequests())
        .isNotEmpty()
        .allSatisfy(
            request ->
                assertThat(request.transcodeDecision().videoRange()).isEqualTo(VideoRange.PQ));
  }

  // The replaced stream session ends as every replaced stream session does: its job attempts stop,
  // and a request for the copy or a lower variant finds the stream session gone.
  private void assertThatTheReplacedStreamSessionEnded(UUID replacedId) {
    assertThat(runningVariants(replacedId)).isEmpty();
    assertThat(rig.coordinator().deliver(replacedId, "default", "segment1.m4s"))
        .isEqualTo(new SegmentDelivery.SessionEnded());
    assertThat(rig.coordinator().deliver(replacedId, "720p", "720p/segment1.m4s"))
        .isEqualTo(new SegmentDelivery.SessionEnded());
  }

  private static TerminalTranscodeFailureEvent terminalFailure(
      UUID streamSessionId, String variantLabel, TranscodeFailure failure) {
    return TerminalTranscodeFailureEvent.builder()
        .streamSessionId(streamSessionId)
        .jobAttemptId(UUID.randomUUID())
        .variantLabel(variantLabel)
        .failure(failure)
        .build();
  }

  private PlaybackSessionSnapshot snapshotOf(
      PlaybackSessionSnapshot snapshot, StartPlaybackSessionCommand command) {
    return playback
        .access(
            PlaybackSessionRequest.builder()
                .playbackSessionId(snapshot.playbackSessionId())
                .identity(command.identity())
                .build())
        .orElseThrow();
  }

  private void assertThatTheCopyPlaysAlone(PlaybackSessionSnapshot copy) {
    var session = sessionOf(copy);
    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(session.getVariants()).isEmpty();
    assertThat(streamInfEntries(copy)).hasSize(1);
  }

  /** A copy that lists lower variants on this client, with the range that its entries announce. */
  @Builder(builderMethodName = "copyLadderBuilder")
  private record CopyLadder(
      @NonNull String name,
      @NonNull MediaProbe source,
      @NonNull VideoRange range,
      @NonNull ClientCapabilityDeclaration client) {

    @Override
    public String toString() {
      return name;
    }
  }

  private static CopyLadder.CopyLadderBuilder ladder(String name) {
    return CopyLadder.copyLadderBuilder().name(name).client(clientThatPlaysEveryRange());
  }

  /** A copy with the encode that its lower variants run and what each playlist entry announces. */
  @Builder(builderMethodName = "copyFamilyBuilder")
  private record CopyFamily(
      @NonNull String name,
      @NonNull MediaProbe source,
      @NonNull EncodeCodecProfile lowerVariantProfile,
      @NonNull String sampleEntry,
      @NonNull VideoRange range) {

    @Override
    public String toString() {
      return name;
    }
  }

  private static CopyFamily.CopyFamilyBuilder copyFamily(String name) {
    return CopyFamily.copyFamilyBuilder().name(name);
  }

  /** A copy that keeps its Dolby Vision, with what its playlist entry announces. */
  @Builder(builderMethodName = "dolbyVisionCopyBuilder")
  private record DolbyVisionCopy(
      @NonNull String name,
      @NonNull MediaProbe source,
      @NonNull VideoRange range,
      @NonNull String sampleEntry,
      @NonNull Optional<String> supplementalCodecs) {

    @Override
    public String toString() {
      return name;
    }
  }

  private static DolbyVisionCopy.DolbyVisionCopyBuilder dolbyVisionCopy(String name) {
    return DolbyVisionCopy.dolbyVisionCopyBuilder()
        .name(name)
        .sampleEntry("hvc1")
        .supplementalCodecs(Optional.empty());
  }

  /** A copy that lists no lower variants beside these encoders and on this client. */
  @Builder(builderMethodName = "copyAloneBuilder")
  private record CopyAlone(
      @NonNull String name,
      @NonNull MediaProbe source,
      @Singular List<WorkerVerifiedEncoder> encoders,
      @NonNull ClientCapabilityDeclaration client) {

    @Override
    public String toString() {
      return name;
    }
  }

  private static CopyAlone.CopyAloneBuilder copyAlone(String name) {
    return CopyAlone.copyAloneBuilder().name(name).client(clientThatPlaysEveryRange());
  }

  private List<String> streamInfEntries(PlaybackSessionSnapshot snapshot) {
    return new HlsPlaylistService(PROPERTIES, UploadBudgetFixture.OFF)
        .generateMultivariantPlaylist(sessionOf(snapshot), "t")
        .lines()
        .filter(line -> line.startsWith("#EXT-X-STREAM-INF:"))
        .toList();
  }

  // An entry without VIDEO-RANGE announces SDR (RFC 8216).
  private static VideoRange rangeOf(String streamInf) {
    var matcher = VIDEO_RANGE_ATTRIBUTE.matcher(streamInf);
    if (!matcher.find()) {
      return VideoRange.SDR;
    }

    return VideoRange.valueOf(matcher.group(1));
  }

  private static String videoCodecOf(String streamInf) {
    var matcher = CODECS_ATTRIBUTE.matcher(streamInf);
    assertThat(matcher.find()).as("CODECS in %s", streamInf).isTrue();
    return matcher.group(1);
  }

  private static Optional<String> supplementalCodecsOf(String streamInf) {
    var matcher = Pattern.compile("SUPPLEMENTAL-CODECS=\"([^\"]+)\"").matcher(streamInf);
    if (!matcher.find()) {
      return Optional.empty();
    }

    return Optional.of(matcher.group(1));
  }

  private PlaybackSessionSnapshot rejectCurrentFormatAttempt(
      PlaybackSessionSnapshot snapshot, StartPlaybackSessionCommand command) {
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

  private static StreamSession sessionOf(PlaybackSessionSnapshot snapshot) {
    if (snapshot.currentFormatAttempt() instanceof PlaybackFormatAttempt.Ready(var session)) {
      return session;
    }

    throw new AssertionError("Expected a ready format attempt: " + snapshot.currentFormatAttempt());
  }

  private StartPlaybackSessionCommand startAuto(ClientCapabilityDeclaration client) {
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
        .options(StreamingOptions.builder().quality(VideoQuality.AUTO).build())
        .capabilityDeclaration(client)
        .networkRoute(NetworkRoute.INTERNET)
        .build();
  }

  private HlsStreamingService streaming() {
    var registry = new FakeRuntimeStreamSessionRegistry();
    rig = rigBuilder(registry).build();
    return streamingBuilder(registry).uploadBudget(UploadBudgetFixture.OFF).build();
  }

  private StreamingRigFixture.StreamingRigBuilder rigBuilder(
      FakeRuntimeStreamSessionRegistry registry) {
    return StreamingRigFixture.streamingRigBuilder()
        .transcodeExecutor(workers)
        .segmentStore(segments)
        .properties(PROPERTIES)
        .runtimeRegistry(registry)
        .heldRequests(workers.heldSegmentRequests())
        .eventPublisher(this::dispatchToPlayback);
  }

  // Hands the delivery coordinator's failure events to the playback service on the publishing
  // thread, as Spring does.
  private void dispatchToPlayback(Object event) {
    switch (event) {
      case RequestedVariantHardwareEncoderFailedEvent failed ->
          playback.onRequestedVariantHardwareEncoderFailed(failed);
      case VariantRecoveryExhaustedEvent exhausted ->
          playback.onVariantRecoveryExhausted(exhausted);
      default -> {
        // The playback service listens to no other event that the rig publishes.
      }
    }
  }

  private HlsStreamingService.HlsStreamingServiceBuilder streamingBuilder(
      FakeRuntimeStreamSessionRegistry registry) {
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
        .meterRegistry(meters);
  }

  // An HEVC entry that declares every range, HDR10+ and Dolby Vision profiles 5, 8.1 and 8.4, and
  // an AV1 entry that declares every range at 10 bits, beside H.264 SDR and stereo AAC.
  private static ClientCapabilityDeclaration clientThatPlaysEveryRange() {
    return clientWith(
        hevcEntryBuilder()
            .compatibleBases(EVERY_RANGE)
            .hdr10Plus(true)
            .dolbyVisionProfiles(
                profiles(
                    DolbyVisionProfile.PROFILE_5,
                    DolbyVisionProfile.PROFILE_8_1,
                    DolbyVisionProfile.PROFILE_8_4))
            .build(),
        av1EntryBuilder().compatibleBases(EVERY_RANGE).build());
  }

  // The same client without a Dolby Vision profile, so it gets a Dolby Vision source's copy with
  // the Dolby Vision stripped.
  private static ClientCapabilityDeclaration clientThatPlaysNoDolbyVision() {
    return clientWith(
        hevcEntryBuilder().compatibleBases(EVERY_RANGE).hdr10Plus(true).build(),
        av1EntryBuilder().compatibleBases(EVERY_RANGE).build());
  }

  // The entries beside H.264 SDR and stereo AAC.
  private static ClientCapabilityDeclaration clientWith(VideoCapabilityEntry... entries) {
    return defaultDeclarationBuilder()
        .video(
            Stream.concat(Arrays.stream(entries), Stream.of(h264EntryBuilder().build()))
                .distinct()
                .toList())
        .build();
  }

  private static List<DolbyVisionProfileCapability> profiles(DolbyVisionProfile... profiles) {
    return Stream.of(profiles)
        .map(
            profile -> DolbyVisionProfileCapability.builder().profile(profile).maxLevel(13).build())
        .toList();
  }

  private static Set<VideoEncodeFeature> without(VideoEncodeFeature feature) {
    var features = EnumSet.allOf(VideoEncodeFeature.class);
    features.remove(feature);
    return features;
  }

  // Lists these verified encoders on the one connected worker, which encodes no other codec family.
  private void workerVerifies(WorkerVerifiedEncoder... encoders) {
    var families =
        Arrays.stream(encoders)
            .map(encoder -> encoder.backend().codecFamily())
            .collect(Collectors.toUnmodifiableSet());
    workers.setEncodableVideoCodecFamilies(families);
    workers.setVerifiedVideoCodecFamiliesByWorker(
        List.of(
            WorkerVerifiedCodecFamilies.builder()
                .workerId(UUID.randomUUID())
                .codecFamilies(families)
                .build()));
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(encoders));
  }

  private static WorkerVerifiedEncoder x265(Set<VideoEncodeFeature> features) {
    return encoder("hevc", "libx265", features);
  }

  // A hardware AV1 encoder that lists every encode feature, which the codec preference puts first
  // (ADR 0009).
  private static WorkerVerifiedEncoder hardwareAv1() {
    return WorkerVerifiedEncoder.builder()
        .backend(
            EncoderBackend.builder()
                .codecFamily("av1")
                .encoder("av1_nvenc")
                .kind(EncoderBackend.Kind.HARDWARE)
                .build())
        .keyframeVerified(true)
        .encodeFeatures(EVERY_ENCODE_FEATURE)
        .build();
  }

  // A software encoder that lists no encode feature, so it writes 8-bit SDR alone.
  private static WorkerVerifiedEncoder encoder(String codecFamily, String encoder) {
    return encoder(codecFamily, encoder, Set.of());
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

  // The default source in the compatible base, with neither HDR10+ nor Dolby Vision.
  private static MediaProbe hevc(CompatibleBase base) {
    return hevcSource(VideoPlaybackFactsFixture.rangeWithoutEnhancement(base));
  }

  private static MediaProbe hevcSource(VideoDynamicRange range) {
    return source(hevcFacts().dynamicRange(range).build());
  }

  // A single-layer source with the profile's record at level 6 on the profile's base.
  private static MediaProbe dolbyVisionSource(DolbyVisionProfile profile) {
    var compatibilityId =
        switch (profile.compatibleBase()) {
          case HLG -> 4;
          case NONE -> 0;
          default -> 1;
        };
    return hevcSource(
        new VideoDynamicRange(
            profile.compatibleBase(),
            Presence.ABSENT,
            new DolbyVision.Present(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(profile.profileNumber()))
                        .level(OptionalInt.of(DOLBY_VISION_LEVEL))
                        .baseLayerCompatibilityId(OptionalInt.of(compatibilityId))
                        .baseLayer(true)
                        .rpu(true)
                        .build()),
                Presence.PRESENT)));
  }

  // A single-track profile 7 source: its record names profile 7 with an enhancement layer at level
  // 6, and its RPUs ride in the one video stream, each at the end of its own picture's sample.
  private static MediaProbe singleTrackProfile7Source() {
    return hevcSource(
        new VideoDynamicRange(
            CompatibleBase.PQ,
            Presence.ABSENT,
            new DolbyVision.Present(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(7))
                        .level(OptionalInt.of(DOLBY_VISION_LEVEL))
                        .baseLayerCompatibilityId(OptionalInt.of(6))
                        .baseLayer(true)
                        .enhancementLayer(true)
                        .rpu(true)
                        .build()),
                Presence.PRESENT,
                Optional.of(
                    DolbyVisionRpuHeader.builder()
                        .vdrRpuProfile(1)
                        .enhancementLayerSpatialResamplingFilter(true)
                        .vdrBitDepth(12)
                        .build()),
                DolbyVisionRpuPlacement.AFTER_OWN_PICTURE)));
  }

  // 10-bit HEVC Main 10 at 24 fps.
  private static VideoPlaybackFacts.VideoPlaybackFactsBuilder hevcFacts() {
    return VideoPlaybackFactsFixture.sdrH264FactsBuilder()
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
                    .build()));
  }

  // AV1 Main at 24 fps in the compatible base, with neither HDR10+ nor Dolby Vision.
  private static MediaProbe av1Source(CompatibleBase base, int bitDepth) {
    return defaultProbeBuilder()
        .audioChannels(OptionalInt.of(2))
        .containerFormat(Optional.of("matroska,webm"))
        .videoCodec("av1")
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                    .codecFamily(Optional.of(ProbedCodecFamily.AV1))
                    .bitDepth(OptionalInt.of(bitDepth))
                    .codecString(Optional.of("av01.0.08M.%02d".formatted(bitDepth)))
                    .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(base))
                    .videoProperties(
                        Optional.of(
                            VideoStreamProperties.builder()
                                .codecProfile(Optional.of(VideoCodecProfile.AV1_MAIN))
                                .codecLevel(OptionalInt.of(40))
                                .codecTier(Optional.of(VideoCodecTier.MAIN))
                                .firstFramesRead(true)
                                .build()))
                    .build()))
        .build();
  }

  // The default 1080p source, whose keyframes open every 6 s interval, beside stereo AAC.
  private static MediaProbe source(VideoPlaybackFacts facts) {
    return defaultProbeBuilder()
        .audioChannels(OptionalInt.of(2))
        .containerFormat(Optional.of("matroska,webm"))
        .videoCodec("hevc")
        .videoPlaybackFacts(Optional.of(facts))
        .build();
  }
}
