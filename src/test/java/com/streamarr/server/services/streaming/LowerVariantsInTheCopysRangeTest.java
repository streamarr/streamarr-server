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
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
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
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.UploadBudgetFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The encoded lower variants beside an Auto copy keep the copy's range and bit depth, through the
 * real transcode decision, ladder and playlist (ADR 0050). A bandwidth dip must not move a
 * television between HDR and SDR, so a copy whose range no verified encoder writes, or whose range
 * the client plays in no codec that a worker encodes, lists no lower variants. Neither does a copy
 * that the playlist describes as Dolby Vision, which no encode writes. Auto still gives HDR up for
 * SDR only after an HDR encode fails (#397).
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
  private static final String TEN_BIT_AV1 = "av01\\.0\\.\\d\\dM\\.10";

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeTranscodeExecutor workers = new FakeTranscodeExecutor();
  private final FakeMediaFileContainerInfoRepository probes =
      new FakeMediaFileContainerInfoRepository();
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
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(svtAv1(EVERY_ENCODE_FEATURE)));
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
        .allSatisfy(entry -> assertThat(videoCodecOf(entry)).matches(TEN_BIT_AV1));
    assertThat(sessionOf(copy).decisionOf("720p").plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.Encode.class,
            encode -> {
              assertThat(encode.codecProfile()).contains(EncodeCodecProfile.AV1_MAIN);
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
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(svtAv1(EVERY_ENCODE_FEATURE)));
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
        ExecutionTargetId.LOCAL,
        List.of(encoder("hevc", "libx265", EVERY_ENCODE_FEATURE), encoder("h264", "libx264")));
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
        .allSatisfy(entry -> assertThat(videoCodecOf(entry)).startsWith("hvc1.2.4."));
  }

  // ADR 0049: an output fits one capability entry. Each lower variant is sized by an entry that
  // plays the lower variants' own range and bit depth, so an entry that plays 8-bit SDR alone at 60
  // fps does not give a PQ lower variant its frame rate.
  @Test
  @DisplayName(
      "Should fit every lower variant to the capability entry that plays the copy's range when"
          + " another entry of its codec family plays SDR alone at a higher frame rate")
  void shouldFitEveryLowerVariantToTheEntryThatPlaysTheCopysRangeWhenAnotherPlaysSdrAloneFaster() {
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(svtAv1(EVERY_ENCODE_FEATURE)));
    probes.setDefaultProbe(
        source(
                hevcFacts()
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60, 1)))
                    .dynamicRange(
                        VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
                    .build())
            .toBuilder()
            .framerate(OptionalDouble.of(60))
            .build());

    var copy =
        accepted(
            playback.start(
                startAuto(
                    clientWith(
                        hevcEntryBuilder().compatibleBases(EVERY_RANGE).build(),
                        av1EntryBuilder().build(),
                        av1EntryBuilder()
                            .compatibleBases(List.of(CompatibleBase.PQ))
                            .maxFrameRate(30)
                            .build()))));

    var session = sessionOf(copy);
    assertThat(session.decisionOf("720p").videoEncodeFormat())
        .isEqualTo(EncodeFormat.hdr(VideoRange.PQ));
    assertThat(session.getVariants())
        .isNotEmpty()
        .extracting(QualityVariant::frameRate)
        .containsOnly(new Fraction(30, 1));
    var entries = streamInfEntries(copy);
    assertThat(entries.getFirst()).contains("FRAME-RATE=60.000");
    assertThat(entries.subList(1, entries.size()))
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(entry).contains("FRAME-RATE=30.000"));
  }

  @Test
  @DisplayName(
      "Should plan the copy's PQ range with the source's static metadata in a lower variant's job"
          + " when the player first requests one of its segments")
  void shouldPlanTheCopysPqRangeWithTheSourcesStaticMetadataInALowerVariantsJob() {
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, List.of(svtAv1(EVERY_ENCODE_FEATURE)));
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
                          .codecProfile(Optional.of(EncodeCodecProfile.AV1_MAIN))
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
        copyAlone("HDR10 beside an AV1 encoder without PQ")
            .source(hevc(CompatibleBase.PQ))
            .encoder(svtAv1(without(VideoEncodeFeature.PQ)))
            .build(),
        copyAlone("HLG beside an AV1 encoder without HLG")
            .source(hevc(CompatibleBase.HLG))
            .encoder(svtAv1(without(VideoEncodeFeature.HLG)))
            .build(),
        copyAlone("10-bit SDR beside an AV1 encoder without 10 bits")
            .source(hevc(CompatibleBase.SDR))
            .encoder(svtAv1(without(VideoEncodeFeature.TEN_BIT)))
            .build(),
        copyAlone("Dolby Vision 8.1, copied without it, beside an AV1 encoder without PQ")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1))
            .client(clientThatPlaysNoDolbyVision())
            .encoder(svtAv1(without(VideoEncodeFeature.PQ)))
            .build(),
        copyAlone("HDR10 beside encoders that list no encode feature")
            .source(hevc(CompatibleBase.PQ))
            .encoder(svtAv1(Set.of()))
            .encoder(encoder("h264", "libx264"))
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("clientsThatPlayNoEncodeInTheCopysRange")
  @DisplayName(
      "Should list the copy alone when no capability entry plays an encode in the copy's range and"
          + " bit depth")
  void shouldListTheCopyAloneWhenNoCapabilityEntryPlaysAnEncodeInTheCopysRangeAndBitDepth(
      CopyAlone copyAlone) {
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(svtAv1(EVERY_ENCODE_FEATURE), encoder("h264", "libx264")));
    probes.setDefaultProbe(copyAlone.source());

    var copy = accepted(playback.start(startAuto(copyAlone.client())));

    assertThatTheCopyPlaysAlone(copy);
  }

  static Stream<CopyAlone> clientsThatPlayNoEncodeInTheCopysRange() {
    var hdrHevc = hevcEntryBuilder().compatibleBases(EVERY_RANGE).build();
    return Stream.of(
        copyAlone("HDR10 on a client whose AV1 entry declares SDR alone")
            .source(hevc(CompatibleBase.PQ))
            .client(clientWith(hdrHevc, av1EntryBuilder().build()))
            .build(),
        copyAlone("HDR10 on a client whose AV1 entry declares PQ at 8 bits")
            .source(hevc(CompatibleBase.PQ))
            .client(
                clientWith(
                    hdrHevc, av1EntryBuilder().compatibleBases(EVERY_RANGE).maxBitDepth(8).build()))
            .build(),
        copyAlone("10-bit SDR on a client whose AV1 entry declares 8 bits")
            .source(hevc(CompatibleBase.SDR))
            .client(clientWith(hdrHevc, av1EntryBuilder().maxBitDepth(8).build()))
            .build());
  }

  // ADR 0050: a lower variant encodes only once the player requests it, so a busy worker's verified
  // encoder counts when the server lists it. Its job then runs only where an encoder lists the
  // features, and no SDR encode on a free worker replaces it.
  @Test
  @DisplayName(
      "Should list a busy worker's PQ lower variants and start one only on that worker when the"
          + " free worker's encoder writes 8-bit SDR alone")
  void shouldListABusyWorkersPqLowerVariantsAndStartOneOnlyOnThatWorker() {
    workers.setExecutionTargets(List.of(SDR_WORKER, HDR_WORKER));
    workers.setTargetEncoders(SDR_WORKER, List.of(encoder("h264", "libx264")));
    workers.setTargetEncoders(HDR_WORKER, List.of(svtAv1(EVERY_ENCODE_FEATURE)));
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

  // #397: a rejected HDR encode keeps its format out of every later format attempt and keeps its
  // codec family, so the next attempt is Auto's 8-bit SDR ladder, and AV1 can still follow it.
  @Test
  @DisplayName(
      "Should give HDR up for Auto's SDR ladder and keep the codec family when the player fails on"
          + " a PQ lower variant")
  void shouldGiveHdrUpForAutosSdrLadderAndKeepTheCodecFamilyWhenThePlayerFailsOnAPqLowerVariant() {
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(svtAv1(EVERY_ENCODE_FEATURE), encoder("h264", "libx264")));
    probes.setDefaultProbe(hevc(CompatibleBase.PQ));
    var command = startAuto(clientThatPlaysEveryRange());
    var copy = accepted(playback.start(command));
    rig.lifecycle()
        .prepareRequestedVariant(sessionOf(copy).getSessionId(), "720p", "720p/segment0.m4s");

    var afterTheLowerVariant = rejectCurrentFormatAttempt(copy, command);
    var afterTheSdrLadder = rejectCurrentFormatAttempt(afterTheLowerVariant, command);

    assertThat(sessionOf(copy).decisionOf("720p").videoEncodeFormat())
        .isEqualTo(EncodeFormat.hdr(VideoRange.PQ));
    assertThat(sessionOf(afterTheLowerVariant).getTranscodeDecision())
        .satisfies(
            decision -> {
              assertThat(decision.transcodeMode().copiesVideo()).isFalse();
              assertThat(decision.videoCodecFamily()).isEqualTo("h264");
              assertThat(decision.videoEncodeFormat()).isEqualTo(EncodeFormat.SDR_8_BIT);
            });
    assertThat(streamInfEntries(afterTheLowerVariant))
        .hasSize(4)
        .extracting(LowerVariantsInTheCopysRangeTest::rangeOf)
        .containsOnly(VideoRange.SDR);
    assertThat(sessionOf(afterTheSdrLadder).getTranscodeDecision())
        .satisfies(
            decision -> {
              assertThat(decision.videoCodecFamily()).isEqualTo("av1");
              assertThat(decision.videoEncodeFormat()).isEqualTo(EncodeFormat.SDR_8_BIT);
            });
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
    var segments = new FakeSegmentStore();
    var registry = new FakeRuntimeStreamSessionRegistry();
    var meters = new SimpleMeterRegistry();
    rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(workers)
            .segmentStore(segments)
            .properties(PROPERTIES)
            .runtimeRegistry(registry)
            .heldRequests(workers.heldSegmentRequests())
            .build();
    return HlsStreamingService.builder()
        .languagePreferences(noLanguagePreferences())
        .uploadBudget(UploadBudgetFixture.OFF)
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

  private static WorkerVerifiedEncoder svtAv1(Set<VideoEncodeFeature> features) {
    return encoder("av1", "libsvtav1", features);
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
