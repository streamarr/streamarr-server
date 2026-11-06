package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.VideoSampleEntry;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The decision for Dolby Vision on a PQ or HLG base and without a compatible base (ADR 0013, ADR
 * 0036): the server copies the best picture that the client declares, one output for each client
 * and format attempt.
 */
@Tag("UnitTest")
@DisplayName("Transcode Decision Dolby Vision Copy Tests")
class TranscodeDecisionDolbyVisionCopyTest {

  private static final Set<String> EVERY_VIDEO_CODEC_FAMILY = Set.of("h264", "hevc", "av1");

  private static final WorkerVideoCopySupport EVERY_REWRITE_WORKER =
      WorkerVideoCopySupport.builder()
          .plannedOutput(true)
          .bitstreamRewrites(Set.of(BitstreamRewrite.values()))
          .build();

  // Every source's record names this level, so the copy's level is the record's.
  private static final int SOURCE_LEVEL = 6;

  private static final Outcome ENCODE =
      Outcome.builder()
          .mode(TranscodeMode.VIDEO_TRANSCODE)
          .rewrites(List.of())
          .dolbyVisionOutput(Optional.empty())
          .range(VideoRange.SDR)
          .build();

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          new SimpleMeterRegistry());

  @ParameterizedTest(name = "{0}")
  @MethodSource("decisionMatrix")
  @DisplayName(
      "Should plan the best picture that the client declares when the source carries Dolby Vision")
  void shouldPlanTheBestPictureThatTheClientDeclaresWhenTheSourceCarriesDolbyVision(
      String scenario, MediaProbe source, ClientCapabilityDeclaration client, Outcome expected) {
    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(client)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .videoCopySupport(List.of(EVERY_REWRITE_WORKER))
                .build());

    assertThat(decision.map(Outcome::of)).contains(expected);
  }

  // Dolby's level table puts 1920×1080 at 30 fps at level 4 and at 60 fps at level 5. Each source
  // runs at 24 fps for the first 30 s that the frame-rate rule scans.
  @ParameterizedTest(name = "{0}")
  @MethodSource("levelsFromTheMaximumFrameRate")
  @DisplayName(
      "Should keep Dolby Vision at the level of the maximum frame rate when no record names a"
          + " level")
  void shouldKeepDolbyVisionAtTheLevelOfTheMaximumFrameRateWhenNoRecordNamesALevel(
      String scenario, MediaProbe source, int clientLevel) {
    var decision = decide(source, Clients.dolbyVision(clientLevel)).map(Outcome::of).orElseThrow();

    assertThat(decision.dolbyVisionOutput())
        .contains(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, clientLevel));
  }

  static Stream<Arguments> levelsFromTheMaximumFrameRate() {
    return Stream.of(
        Arguments.of(
            "No record, and a later section at 60 fps, to a client up to level 5",
            variableRateHevc(profile81FromItsRpuHeader(), new Fraction(60, 1)),
            5),
        Arguments.of(
            "A record without a level, and a later section at 30 fps, to a client up to level 4",
            variableRateHevc(profile81WithoutRecordLevel(), new Fraction(30, 1)),
            4));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionCopiesWithoutALevel")
  @DisplayName(
      "Should strip Dolby Vision when the maximum frame rate puts the copy above the client's level"
          + " or is unknown")
  void shouldStripDolbyVisionWhenTheMaximumFrameRatePutsTheCopyAboveTheClientsLevelOrIsUnknown(
      String scenario, MediaProbe source, int clientLevel) {
    var decision = decide(source, Clients.dolbyVision(clientLevel)).map(Outcome::of).orElseThrow();

    assertThat(decision.dolbyVisionOutput()).isEmpty();
    assertThat(decision.mode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.rewrites()).contains(BitstreamRewrite.STRIP_DOLBY_VISION);
  }

  static Stream<Arguments> dolbyVisionCopiesWithoutALevel() {
    return Stream.of(
        Arguments.of(
            "No record, and a later section at 60 fps, to a client up to level 4",
            variableRateHevc(profile81FromItsRpuHeader(), new Fraction(60, 1)),
            4),
        Arguments.of(
            "No record, and an unknown maximum frame rate, to a client up to level 13",
            hevc(profile81FromItsRpuHeader()),
            13));
  }

  static Stream<Arguments> decisionMatrix() {
    return Stream.of(
        Arguments.of(
            "Profile 8.1 to a client that declares profile 8.1",
            hevc(profile8(CompatibleBase.PQ, 1)),
            Clients.dolbyVision(13),
            keepingDolbyVision(DolbyVisionProfile.PROFILE_8_1, VideoRange.PQ)),
        Arguments.of(
            "Profile 8.4 to a client that declares profile 8.4",
            hevc(profile8(CompatibleBase.HLG, 4)),
            Clients.dolbyVision(13),
            keepingDolbyVision(DolbyVisionProfile.PROFILE_8_4, VideoRange.HLG)),
        Arguments.of(
            "Profile 10.1 to a client that declares profile 10.1",
            av1(profile10(CompatibleBase.PQ, 1)),
            Clients.dolbyVision(13),
            keepingDolbyVision(DolbyVisionProfile.PROFILE_10_1, VideoRange.PQ)),
        Arguments.of(
            "Profile 10.4 to a client that declares profile 10.4",
            av1(profile10(CompatibleBase.HLG, 4)),
            Clients.dolbyVision(13),
            keepingDolbyVision(DolbyVisionProfile.PROFILE_10_4, VideoRange.HLG)),
        Arguments.of(
            "Profile 8.1 at the client's maximum level",
            hevc(profile8(CompatibleBase.PQ, 1)),
            Clients.dolbyVision(SOURCE_LEVEL),
            keepingDolbyVision(DolbyVisionProfile.PROFILE_8_1, VideoRange.PQ)),
        Arguments.of(
            "Profile 8.1 with HDR10+ to a client that declares profile 8.1 and HDR10+",
            hevc(withHdr10Plus(profile8(CompatibleBase.PQ, 1))),
            Clients.dolbyVision(13),
            Outcome.builder()
                .mode(TranscodeMode.REMUX)
                .rewrites(
                    List.of(
                        BitstreamRewrite.STRIP_HDR10_PLUS,
                        BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
                .dolbyVisionOutput(
                    Optional.of(
                        new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, SOURCE_LEVEL)))
                .range(VideoRange.PQ)
                .build()),
        Arguments.of(
            "Profile 8 with compatibility identifier 6 to a client that declares profile 8.1",
            hevc(profile8(CompatibleBase.PQ, 6)),
            Clients.dolbyVision(13),
            keepingDolbyVision(DolbyVisionProfile.PROFILE_8_1, VideoRange.PQ)),
        Arguments.of(
            "Profile 8 with compatibility identifier 6 to a client that declares only PQ",
            hevc(profile8(CompatibleBase.PQ, 6)),
            Clients.baseOnly(false),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Profile 8.1 above the client's maximum level",
            hevc(profile8(CompatibleBase.PQ, 1)),
            Clients.dolbyVision(SOURCE_LEVEL - 1),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Profile 8.1 to a client that declares only PQ",
            hevc(profile8(CompatibleBase.PQ, 1)),
            Clients.baseOnly(false),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Profile 8.4 to a client that declares only HLG",
            hevc(profile8(CompatibleBase.HLG, 4)),
            Clients.baseOnly(false),
            strippingDolbyVision(VideoRange.HLG)),
        Arguments.of(
            "Profile 10.1 to a client that declares only PQ",
            av1(profile10(CompatibleBase.PQ, 1)),
            Clients.baseOnly(false),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Profile 8.1 with HDR10+ to a client that declares only PQ",
            hevc(withHdr10Plus(profile8(CompatibleBase.PQ, 1))),
            Clients.baseOnly(false),
            Outcome.builder()
                .mode(TranscodeMode.REMUX)
                .rewrites(
                    List.of(BitstreamRewrite.STRIP_DOLBY_VISION, BitstreamRewrite.STRIP_HDR10_PLUS))
                .dolbyVisionOutput(Optional.empty())
                .range(VideoRange.PQ)
                .build()),
        Arguments.of(
            "Profile 8.1 with HDR10+ to a client that declares PQ and HDR10+",
            hevc(withHdr10Plus(profile8(CompatibleBase.PQ, 1))),
            Clients.baseOnly(true),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Profile 8.1 whose record names no level, without a stored maximum frame rate",
            hevc(profile81WithoutRecordLevel()),
            Clients.dolbyVision(13),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Dolby Vision whose profile neither a record nor an RPU header names",
            hevc(
                new VideoDynamicRange(
                    CompatibleBase.PQ,
                    Presence.ABSENT,
                    new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED))),
            Clients.dolbyVision(13),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Single-track profile 7 to a client that declares profile 8.1",
            hevc(singleTrackProfile7()),
            Clients.dolbyVision(13),
            convertingProfile7()),
        Arguments.of(
            "Single-track profile 7 at the client's maximum level",
            hevc(singleTrackProfile7()),
            Clients.dolbyVision(SOURCE_LEVEL),
            convertingProfile7()),
        Arguments.of(
            "Single-track profile 7 with HDR10+ to a client that declares profile 8.1 and HDR10+",
            hevc(withHdr10Plus(singleTrackProfile7())),
            Clients.dolbyVision(13),
            convertingProfile7AndStrippingHdr10Plus()),
        Arguments.of(
            "Single-track profile 7 above the client's maximum level",
            hevc(singleTrackProfile7()),
            Clients.dolbyVision(SOURCE_LEVEL - 1),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Single-track profile 7 to a client that declares only PQ",
            hevc(singleTrackProfile7()),
            Clients.baseOnly(false),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Single-track profile 7 whose record names no level, until the maximum frame rate is"
                + " stored",
            hevc(singleTrackProfile7WithoutRecordLevel()),
            Clients.dolbyVision(13),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Single-track profile 7 to a client that declares only SDR",
            hevc(singleTrackProfile7()),
            Clients.sdrOnly(),
            ENCODE),
        Arguments.of(
            "Single-track profile 7 whose RPUs start their samples",
            hevc(withRpusStartingTheirSamples(singleTrackProfile7())),
            Clients.dolbyVision(13),
            ENCODE),
        Arguments.of(
            "The base layer track of a dual-track profile 7 source",
            hevc(dualTrackBaseLayer(7, 6)),
            Clients.dolbyVision(13),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "The enhancement layer track of a dual-track profile 7 source",
            hevc(dualTrackEnhancementLayer()),
            Clients.dolbyVision(13),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "The base layer track of a profile 6 source",
            hevc(dualTrackBaseLayer(6, 1)),
            Clients.dolbyVision(13),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Profile 5 that the RPU header names beside a PQ transfer",
            hevc(profile5FromTheRpuHeaderBesideAPqTransfer()),
            Clients.baseOnly(true),
            ENCODE),
        Arguments.of(
            "Profile 5 with its record, to a client that declares PQ",
            hevc(VideoPlaybackFactsFixture.dolbyVisionProfile5()),
            Clients.baseOnly(true),
            ENCODE),
        Arguments.of(
            "Dolby Vision whose profile nothing names, on an SDR base",
            hevc(
                new VideoDynamicRange(
                    CompatibleBase.SDR,
                    Presence.ABSENT,
                    new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED))),
            Clients.dolbyVision(13),
            ENCODE),
        Arguments.of(
            "Profile 8.1 whose RPUs start their samples",
            hevc(withRpusStartingTheirSamples(profile8(CompatibleBase.PQ, 1))),
            Clients.dolbyVision(13),
            ENCODE),
        Arguments.of(
            "Profile 9 that claims a PQ base, to a client that declares H.264 with PQ",
            h264(dolbyVisionRecord(CompatibleBase.PQ, 9, 6)),
            Clients.h264Pq(),
            ENCODE),
        Arguments.of(
            "H.264 Dolby Vision whose profile nothing names, beside a PQ transfer",
            h264(unnamedDolbyVisionOn(CompatibleBase.PQ)),
            Clients.h264Pq(),
            ENCODE),
        Arguments.of(
            "H.264 Dolby Vision whose profile nothing names, beside an HLG transfer",
            h264(unnamedDolbyVisionOn(CompatibleBase.HLG)),
            Clients.h264Pq(),
            ENCODE),
        Arguments.of(
            "Profile 8.1 to a client that declares only SDR",
            hevc(profile8(CompatibleBase.PQ, 1)),
            Clients.sdrOnly(),
            ENCODE),
        Arguments.of(
            "Profile 8.1 from a probe version 4 outcome",
            hevcFromProbeVersion4(profile8(CompatibleBase.PQ, 1)),
            Clients.dolbyVision(13),
            ENCODE));
  }

  // ADR 0036: a copy would hand players the misplaced RPUs, so the property issue rules out every
  // copy, whatever the client declares. Each client copies the same source when its RPUs end their
  // own pictures' samples.
  @ParameterizedTest(name = "{0}")
  @MethodSource("clientsThatCopyTheSourceWithoutThePropertyIssue")
  @DisplayName(
      "Should encode an H.264 or HEVC Dolby Vision stream for every client when its RPUs start"
          + " their samples")
  void shouldEncodeAnH264OrHevcDolbyVisionStreamForEveryClientWhenItsRpusStartTheirSamples(
      String scenario,
      Function<VideoDynamicRange, MediaProbe> codec,
      VideoDynamicRange range,
      ClientCapabilityDeclaration client) {
    var copied = decide(codec.apply(range), client);
    var misplaced = decide(codec.apply(withRpusStartingTheirSamples(range)), client);

    assertThat(copied.map(Outcome::of).map(Outcome::mode)).contains(TranscodeMode.REMUX);
    assertThat(misplaced.map(Outcome::of)).contains(ENCODE);
  }

  static Stream<Arguments> clientsThatCopyTheSourceWithoutThePropertyIssue() {
    Function<VideoDynamicRange, MediaProbe> hevc = TranscodeDecisionDolbyVisionCopyTest::hevc;
    Function<VideoDynamicRange, MediaProbe> h264 = TranscodeDecisionDolbyVisionCopyTest::h264;
    return Stream.of(
        Arguments.of(
            "HEVC profile 8.1 to a client that declares profile 8.1",
            hevc,
            profile8(CompatibleBase.PQ, 1),
            Clients.dolbyVision(13)),
        Arguments.of(
            "HEVC profile 8.1 to a client that declares only PQ",
            hevc,
            profile8(CompatibleBase.PQ, 1),
            Clients.baseOnly(false)),
        Arguments.of(
            "HEVC profile 8.4 to a client that declares profile 8.4",
            hevc,
            profile8(CompatibleBase.HLG, 4),
            Clients.dolbyVision(13)),
        Arguments.of(
            "HEVC profile 8.2 to a client that declares only SDR",
            hevc,
            profile8(CompatibleBase.SDR, 2),
            Clients.sdrOnly()),
        Arguments.of(
            "HEVC profile 5 to a client that declares profile 5",
            hevc,
            dolbyVisionRecord(CompatibleBase.NONE, 5, 0),
            Clients.baseless(13)),
        Arguments.of(
            "H.264 profile 9 to a client that declares only SDR",
            h264,
            dolbyVisionRecord(CompatibleBase.SDR, 9, 2),
            Clients.sdrOnly()));
  }

  // ADR 0036: an AV1 RPU belongs ahead of its frame, so the probe names no RPU placement for AV1
  // and reports no property issue. The profile 10.1 copy keeps its Dolby Vision.
  @Test
  @DisplayName(
      "Should copy AV1 profile 10.1 with its Dolby Vision when the probe observes RPUs ahead of"
          + " their frames")
  void shouldCopyAv1Profile101WithItsDolbyVisionWhenTheProbeObservesRpusAheadOfTheirFrames() {
    var profile101 = (DolbyVision.Present) profile10(CompatibleBase.PQ, 1).dolbyVision();
    var observedAheadOfTheirFrames =
        new VideoDynamicRange(
            CompatibleBase.PQ,
            Presence.ABSENT,
            new DolbyVision.Present(
                profile101.declared(),
                Presence.PRESENT,
                Optional.empty(),
                DolbyVisionRpuPlacement.UNSPECIFIED));

    var decision = decide(av1(observedAheadOfTheirFrames), Clients.dolbyVision(13));

    assertThat(decision.map(Outcome::of))
        .contains(keepingDolbyVision(DolbyVisionProfile.PROFILE_10_1, VideoRange.PQ));
  }

  private Optional<TranscodeDecision> decide(
      MediaProbe source, ClientCapabilityDeclaration client) {
    return service.decide(
        TranscodeDecisionRequest.builder()
            .source(source)
            .capabilityDeclaration(client)
            .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
            .videoCopySupport(List.of(EVERY_REWRITE_WORKER, workerWriting(VideoSampleEntry.DVH1)))
            .build());
  }

  // ADR 0052: after a failed Dolby Vision copy, the next format attempt may only strip the Dolby
  // Vision, although the client still declares the profile. A source without a PQ or HLG base to
  // strip down to is encoded.
  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesAfterAFailedDolbyVisionCopy")
  @DisplayName(
      "Should copy without the Dolby Vision or encode when only the copy with Dolby Vision stripped"
          + " may follow")
  void shouldCopyWithoutTheDolbyVisionOrEncodeWhenOnlyTheCopyWithDolbyVisionStrippedMayFollow(
      String scenario, MediaProbe source, Outcome expected) {
    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(Clients.dolbyVision(13))
                .videoFormatConstraint(
                    VideoFormatConstraint.COPY_WITH_DOLBY_VISION_STRIPPED_OR_ENCODE)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .videoCopySupport(List.of(EVERY_REWRITE_WORKER))
                .build());

    assertThat(decision.map(Outcome::of)).contains(expected);
  }

  static Stream<Arguments> sourcesAfterAFailedDolbyVisionCopy() {
    return Stream.of(
        Arguments.of(
            "Profile 8.1",
            hevc(profile8(CompatibleBase.PQ, 1)),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Profile 8.4",
            hevc(profile8(CompatibleBase.HLG, 4)),
            strippingDolbyVision(VideoRange.HLG)),
        Arguments.of(
            "Profile 10.1",
            av1(profile10(CompatibleBase.PQ, 1)),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Profile 10.4",
            av1(profile10(CompatibleBase.HLG, 4)),
            strippingDolbyVision(VideoRange.HLG)),
        Arguments.of(
            "Profile 8.1 with HDR10+, which the client declares",
            hevc(withHdr10Plus(profile8(CompatibleBase.PQ, 1))),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Single-track profile 7, whose conversion the worker failed",
            hevc(singleTrackProfile7()),
            strippingDolbyVision(VideoRange.PQ)),
        Arguments.of(
            "Profile 5 with its record",
            hevc(VideoPlaybackFactsFixture.dolbyVisionProfile5()),
            ENCODE),
        Arguments.of(
            "An HDR10 source without Dolby Vision",
            hevc(
                new VideoDynamicRange(
                    CompatibleBase.PQ, Presence.ABSENT, new DolbyVision.Absent())),
            ENCODE));
  }

  // ADR 0013: a profile 5 or 10.0 copy matches through its Dolby Vision profile and level alone. It
  // keeps its RPUs under the dvh1 or dav1 sample entry with the record that the playlist describes,
  // and only a worker that writes that entry delivers it. Every other client gets an encode.
  @ParameterizedTest(name = "{0}")
  @MethodSource("baselessDolbyVisionDecisions")
  @DisplayName(
      "Should copy Dolby Vision without a compatible base only when the client declares its profile"
          + " and a worker writes its sample entry")
  void shouldCopyDolbyVisionWithoutABaseOnlyWhenTheClientDeclaresItAndAWorkerWritesItsSampleEntry(
      String scenario,
      MediaProbe source,
      ClientCapabilityDeclaration client,
      WorkerVideoCopySupport worker,
      Outcome expected) {
    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(client)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .videoCopySupport(List.of(worker))
                .build());

    assertThat(decision.map(Outcome::of)).contains(expected);
  }

  static Stream<Arguments> baselessDolbyVisionDecisions() {
    var profile5 = hevc(dolbyVisionRecord(CompatibleBase.NONE, 5, 0));
    var profile100 = av1(dolbyVisionRecord(CompatibleBase.NONE, 10, 0));
    var dvh1Worker = workerWriting(VideoSampleEntry.DVH1);
    var dav1Worker = workerWriting(VideoSampleEntry.DAV1);
    return Stream.of(
        Arguments.of(
            "Profile 5 to a client that declares profile 5, beside a worker that writes dvh1",
            profile5,
            Clients.baseless(13),
            dvh1Worker,
            keepingDolbyVision(DolbyVisionProfile.PROFILE_5, VideoRange.PQ)),
        Arguments.of(
            "Profile 5 at the client's maximum level",
            profile5,
            Clients.baseless(SOURCE_LEVEL),
            dvh1Worker,
            keepingDolbyVision(DolbyVisionProfile.PROFILE_5, VideoRange.PQ)),
        Arguments.of(
            "Profile 5 stored under hev1, whose copy the worker writes under dvh1",
            hevcUnderHev1(dolbyVisionRecord(CompatibleBase.NONE, 5, 0)),
            Clients.baseless(13),
            dvh1Worker,
            keepingDolbyVision(DolbyVisionProfile.PROFILE_5, VideoRange.PQ)),
        Arguments.of(
            "Profile 5 from a probe version 4 outcome, which names no codec profile to check",
            hevcFromProbeVersion4(dolbyVisionRecord(CompatibleBase.NONE, 5, 0)),
            Clients.baseless(13),
            dvh1Worker,
            ENCODE),
        Arguments.of(
            "Profile 5 above the client's maximum level",
            profile5,
            Clients.baseless(SOURCE_LEVEL - 1),
            dvh1Worker,
            ENCODE),
        Arguments.of(
            "Profile 5 beside a worker that writes only dav1",
            profile5,
            Clients.baseless(13),
            dav1Worker,
            ENCODE),
        Arguments.of(
            "Profile 5 beside a worker that writes no Dolby Vision sample entry",
            profile5,
            Clients.baseless(13),
            EVERY_REWRITE_WORKER,
            ENCODE),
        Arguments.of(
            "Profile 5 beside a worker that writes dvh1 but does not normalize the record",
            profile5,
            Clients.baseless(13),
            WorkerVideoCopySupport.builder()
                .plannedOutput(true)
                .bitstreamRewrites(
                    Set.of(BitstreamRewrite.STRIP_DOLBY_VISION, BitstreamRewrite.STRIP_HDR10_PLUS))
                .dolbyVisionSampleEntries(Set.of(VideoSampleEntry.DVH1))
                .build(),
            ENCODE),
        Arguments.of(
            "Profile 5 to a client that declares profiles 8.1 and 8.4 with every base",
            profile5,
            Clients.dolbyVision(13),
            dvh1Worker,
            ENCODE),
        Arguments.of(
            "Profile 10.0 beside a worker that writes only dvh1, as the pinned worker does",
            profile100,
            Clients.baseless(13),
            dvh1Worker,
            ENCODE),
        Arguments.of(
            "Profile 10.0 beside a worker that writes dav1",
            profile100,
            Clients.baseless(13),
            dav1Worker,
            keepingDolbyVision(DolbyVisionProfile.PROFILE_10_0, VideoRange.PQ)),
        Arguments.of(
            "Profile 10.0 to a client that declares profiles 10.1 and 10.4 with every base",
            profile100,
            Clients.dolbyVision(13),
            dav1Worker,
            ENCODE));
  }

  // A worker that applies every rewrite and writes the Dolby Vision sample entries.
  private static WorkerVideoCopySupport workerWriting(VideoSampleEntry... sampleEntries) {
    return WorkerVideoCopySupport.builder()
        .plannedOutput(true)
        .bitstreamRewrites(Set.of(BitstreamRewrite.values()))
        .dolbyVisionSampleEntries(Set.of(sampleEntries))
        .build();
  }

  // ADR 0036: the server sends a rewrite only to a worker that declares it. When no connected
  // worker declares every rewrite that the copy needs, the client receives an encode instead.
  @ParameterizedTest(name = "{0}")
  @MethodSource("workersWithoutTheNormalizeRewrite")
  @DisplayName(
      "Should encode a profile 8.1 source when no connected worker normalizes the Dolby Vision"
          + " record")
  void shouldEncodeAProfile81SourceWhenNoConnectedWorkerNormalizesTheDolbyVisionRecord(
      String workers, List<WorkerVideoCopySupport> support) {
    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(hevc(profile8(CompatibleBase.PQ, 1)))
                .capabilityDeclaration(Clients.dolbyVision(13))
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .videoCopySupport(support)
                .build());

    assertThat(decision.map(Outcome::of)).contains(ENCODE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("workersWithoutTheStripRewrite")
  @DisplayName(
      "Should encode a profile 8.1 source for a client that declares only PQ when no connected"
          + " worker strips Dolby Vision")
  void
      shouldEncodeAProfile81SourceForAClientThatDeclaresOnlyPqWhenNoConnectedWorkerStripsDolbyVision(
          String workers, List<WorkerVideoCopySupport> support) {
    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(hevc(profile8(CompatibleBase.PQ, 1)))
                .capabilityDeclaration(Clients.baseOnly(false))
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .videoCopySupport(support)
                .build());

    assertThat(decision.map(Outcome::of)).contains(ENCODE);
  }

  // ADR 0036: the server sends the conversion only to a worker that declares it, and a copy whose
  // rewrites no connected worker applies becomes an encode.
  @Test
  @DisplayName(
      "Should encode a single-track profile 7 source when no connected worker converts profile 7")
  void shouldEncodeASingleTrackProfile7SourceWhenNoConnectedWorkerConvertsProfile7() {
    var strippingAndNormalizingWorker =
        WorkerVideoCopySupport.builder()
            .plannedOutput(true)
            .bitstreamRewrites(
                Set.of(
                    BitstreamRewrite.STRIP_DOLBY_VISION,
                    BitstreamRewrite.STRIP_HDR10_PLUS,
                    BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
            .build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(hevc(singleTrackProfile7()))
                .capabilityDeclaration(Clients.dolbyVision(13))
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .videoCopySupport(List.of(strippingAndNormalizingWorker))
                .build());

    assertThat(decision.map(Outcome::of)).contains(ENCODE);
  }

  static Stream<Arguments> workersWithoutTheStripRewrite() {
    var normalizingWorker =
        WorkerVideoCopySupport.builder()
            .plannedOutput(true)
            .bitstreamRewrites(
                Set.of(
                    BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD,
                    BitstreamRewrite.STRIP_HDR10_PLUS))
            .build();
    var olderWorker =
        WorkerVideoCopySupport.builder()
            .plannedOutput(false)
            .bitstreamRewrites(Set.of(BitstreamRewrite.STRIP_DOLBY_VISION))
            .build();
    return Stream.of(
        Arguments.of("a worker that does not strip Dolby Vision", List.of(normalizingWorker)),
        Arguments.of(
            "a worker that lists the strip but not the planned output", List.of(olderWorker)));
  }

  static Stream<Arguments> workersWithoutTheNormalizeRewrite() {
    var strippingWorker =
        WorkerVideoCopySupport.builder()
            .plannedOutput(true)
            .bitstreamRewrites(
                Set.of(BitstreamRewrite.STRIP_DOLBY_VISION, BitstreamRewrite.STRIP_HDR10_PLUS))
            .build();
    var olderWorker =
        WorkerVideoCopySupport.builder()
            .plannedOutput(false)
            .bitstreamRewrites(Set.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
            .build();
    return Stream.of(
        Arguments.of("no worker read", List.of()),
        Arguments.of("a worker that only strips", List.of(strippingWorker)),
        Arguments.of(
            "a worker that lists the rewrite but not the planned output", List.of(olderWorker)));
  }

  // A copy that keeps Dolby Vision on a PQ or HLG base writes the record that its playlist entry
  // describes, so it lists the normalize rewrite (ADR 0036).
  private static Outcome keepingDolbyVision(DolbyVisionProfile profile, VideoRange range) {
    return Outcome.builder()
        .mode(TranscodeMode.REMUX)
        .rewrites(List.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
        .dolbyVisionOutput(Optional.of(new DolbyVisionOutput(profile, SOURCE_LEVEL)))
        .range(range)
        .build();
  }

  // ADR 0036: the copy plays its PQ or HLG base without RPUs or a record.
  private static Outcome strippingDolbyVision(VideoRange range) {
    return Outcome.builder()
        .mode(TranscodeMode.REMUX)
        .rewrites(List.of(BitstreamRewrite.STRIP_DOLBY_VISION))
        .dolbyVisionOutput(Optional.empty())
        .range(range)
        .build();
  }

  // ADR 0036 and streamarr/streamarr-server#651: the copy converts each picture's RPU to profile
  // 8.1 and writes the profile 8.1 record that its playlist entry describes, at the source's level.
  private static Outcome convertingProfile7() {
    return convertingProfile7(
        List.of(
            BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
            BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD));
  }

  private static Outcome convertingProfile7AndStrippingHdr10Plus() {
    return convertingProfile7(
        List.of(
            BitstreamRewrite.STRIP_HDR10_PLUS,
            BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
            BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD));
  }

  private static Outcome convertingProfile7(List<BitstreamRewrite> rewrites) {
    return Outcome.builder()
        .mode(TranscodeMode.REMUX)
        .rewrites(rewrites)
        .dolbyVisionOutput(
            Optional.of(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, SOURCE_LEVEL)))
        .range(VideoRange.PQ)
        .build();
  }

  private static VideoDynamicRange profile8(CompatibleBase base, int compatibilityId) {
    return dolbyVisionRecord(base, 8, compatibilityId);
  }

  private static VideoDynamicRange profile10(CompatibleBase base, int compatibilityId) {
    return dolbyVisionRecord(base, 10, compatibilityId);
  }

  private static VideoDynamicRange dolbyVisionRecord(
      CompatibleBase base, int profile, int compatibilityId) {
    return new VideoDynamicRange(
        base,
        Presence.ABSENT,
        new DolbyVision.Present(
            Optional.of(
                DolbyVisionConfiguration.builder()
                    .profile(OptionalInt.of(profile))
                    .level(OptionalInt.of(SOURCE_LEVEL))
                    .baseLayerCompatibilityId(OptionalInt.of(compatibilityId))
                    .baseLayer(true)
                    .rpu(true)
                    .build()),
            Presence.PRESENT));
  }

  private static VideoDynamicRange profile81WithoutRecordLevel() {
    return new VideoDynamicRange(
        CompatibleBase.PQ,
        Presence.ABSENT,
        new DolbyVision.Present(
            Optional.of(
                DolbyVisionConfiguration.builder()
                    .profile(OptionalInt.of(8))
                    .baseLayerCompatibilityId(OptionalInt.of(1))
                    .baseLayer(true)
                    .rpu(true)
                    .build()),
            Presence.PRESENT));
  }

  // A single-track profile 7 source: its record names profile 7 with an enhancement layer, and its
  // RPUs ride in the one video stream, each at the end of its own picture's sample.
  private static VideoDynamicRange singleTrackProfile7() {
    return singleTrackProfile7(OptionalInt.of(SOURCE_LEVEL));
  }

  private static VideoDynamicRange singleTrackProfile7WithoutRecordLevel() {
    return singleTrackProfile7(OptionalInt.empty());
  }

  private static VideoDynamicRange singleTrackProfile7(OptionalInt recordLevel) {
    return profile7WithRpusInTheStream(profile7RecordBuilder().level(recordLevel).build());
  }

  // The enhancement layer track of a dual-track source carries the RPUs beside a PQ transfer, and
  // its record names no base layer.
  private static VideoDynamicRange dualTrackEnhancementLayer() {
    return profile7WithRpusInTheStream(profile7RecordBuilder().baseLayer(false).build());
  }

  // A profile 7 record at the source level with every layer, which a caller narrows.
  private static DolbyVisionConfiguration.DolbyVisionConfigurationBuilder profile7RecordBuilder() {
    return DolbyVisionConfiguration.builder()
        .profile(OptionalInt.of(7))
        .level(OptionalInt.of(SOURCE_LEVEL))
        .baseLayerCompatibilityId(OptionalInt.of(6))
        .baseLayer(true)
        .enhancementLayer(true)
        .rpu(true);
  }

  private static VideoDynamicRange profile7WithRpusInTheStream(
      DolbyVisionConfiguration configuration) {
    return withRpusInTheStream(
        Optional.of(configuration),
        DolbyVisionRpuHeader.builder()
            .vdrRpuProfile(1)
            .enhancementLayerSpatialResamplingFilter(true)
            .vdrBitDepth(12)
            .build());
  }

  // Without a record, the RPU header of a single-layer stream at 12 bits on a PQ base names
  // profile 8.1.
  private static VideoDynamicRange profile81FromItsRpuHeader() {
    return withRpusInTheStream(
        Optional.empty(),
        DolbyVisionRpuHeader.builder()
            .vdrRpuProfile(1)
            .disableResidual(true)
            .vdrBitDepth(12)
            .build());
  }

  private static VideoDynamicRange withRpusInTheStream(
      Optional<DolbyVisionConfiguration> configuration, DolbyVisionRpuHeader firstRpuHeader) {
    return new VideoDynamicRange(
        CompatibleBase.PQ,
        Presence.ABSENT,
        new DolbyVision.Present(
            configuration,
            Presence.PRESENT,
            Optional.of(firstRpuHeader),
            DolbyVisionRpuPlacement.AFTER_OWN_PICTURE));
  }

  // The record of a base layer track names a base layer, and its RPUs sit in the other track.
  private static VideoDynamicRange dualTrackBaseLayer(int profile, int compatibilityId) {
    return new VideoDynamicRange(
        CompatibleBase.PQ,
        Presence.ABSENT,
        new DolbyVision.Present(
            Optional.of(
                DolbyVisionConfiguration.builder()
                    .profile(OptionalInt.of(profile))
                    .level(OptionalInt.of(SOURCE_LEVEL))
                    .baseLayerCompatibilityId(OptionalInt.of(compatibilityId))
                    .baseLayer(true)
                    .build()),
            Presence.ABSENT));
  }

  // Profile 5 has no base that a player could show, so the server never strips it (ADR 0036).
  private static VideoDynamicRange profile5FromTheRpuHeaderBesideAPqTransfer() {
    return new VideoDynamicRange(
        CompatibleBase.PQ,
        Presence.ABSENT,
        new DolbyVision.Present(
            Optional.empty(),
            Presence.PRESENT,
            Optional.of(
                DolbyVisionRpuHeader.builder()
                    .vdrRpuProfile(0)
                    .baseLayerFullRange(true)
                    .vdrBitDepth(10)
                    .build()),
            DolbyVisionRpuPlacement.AFTER_OWN_PICTURE));
  }

  // A probe can find Dolby Vision from the sample entry alone, without a record or an RPU header.
  private static VideoDynamicRange unnamedDolbyVisionOn(CompatibleBase base) {
    return new VideoDynamicRange(
        base, Presence.ABSENT, new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED));
  }

  // ADR 0036: a copy would hand players the misplaced RPUs, so no copy rule overrides this issue.
  private static VideoDynamicRange withRpusStartingTheirSamples(VideoDynamicRange range) {
    var present = (DolbyVision.Present) range.dolbyVision();
    return new VideoDynamicRange(
        range.compatibleBase(),
        range.hdr10Plus(),
        new DolbyVision.Present(
            present.declared(),
            Presence.PRESENT,
            Optional.empty(),
            DolbyVisionRpuPlacement.STARTS_SAMPLE));
  }

  private static VideoDynamicRange withHdr10Plus(VideoDynamicRange range) {
    return new VideoDynamicRange(range.compatibleBase(), Presence.PRESENT, range.dolbyVision());
  }

  private static MediaProbe hevc(VideoDynamicRange range) {
    return Source.builder()
        .codec("hevc")
        .codecString("hvc1.2.4.L150.90")
        .dynamicRange(range)
        .videoProperties(Optional.of(properties(VideoCodecProfile.HEVC_MAIN_10)))
        .build()
        .probe();
  }

  // 1920×1080, at 24 fps in the first 30 s, which the frame-rate rule scans.
  private static MediaProbe variableRateHevc(VideoDynamicRange range, Fraction maximumFrameRate) {
    return Source.builder()
        .codec("hevc")
        .codecString("hvc1.2.4.L150.90")
        .dynamicRange(range)
        .videoProperties(Optional.of(properties(VideoCodecProfile.HEVC_MAIN_10)))
        .frameRateRule(VideoPlaybackFactsFixture.packetScanRule(24, new Fraction(24, 1)))
        .maximumFrameRate(maximumFrameRate)
        .build()
        .probe();
  }

  // ADR 0036: profile 9 keeps its RPUs under an SDR base, so the server never strips it.
  private static MediaProbe h264(VideoDynamicRange range) {
    return Source.builder()
        .codec("h264")
        .codecString("avc1.640028")
        .bitDepth(8)
        .dynamicRange(range)
        .videoProperties(Optional.of(VideoPlaybackFactsFixture.h264HighPropertiesBuilder().build()))
        .build()
        .probe();
  }

  // A copy of HEVC from Matroska, MPEG-TS or a raw stream carries hev1 (ADR 0013).
  private static MediaProbe hevcUnderHev1(VideoDynamicRange range) {
    return Source.builder()
        .codec("hevc")
        .codecString("hev1.2.4.L150.90")
        .dynamicRange(range)
        .videoProperties(Optional.of(properties(VideoCodecProfile.HEVC_MAIN_10)))
        .build()
        .probe();
  }

  private static MediaProbe hevcFromProbeVersion4(VideoDynamicRange range) {
    return Source.builder()
        .codec("hevc")
        .codecString("hvc1.2.4.L150.90")
        .dynamicRange(range)
        .videoProperties(Optional.empty())
        .build()
        .probe();
  }

  private static MediaProbe av1(VideoDynamicRange range) {
    return Source.builder()
        .codec("av1")
        .codecString("av01.0.08M.10")
        .dynamicRange(range)
        .videoProperties(Optional.of(properties(VideoCodecProfile.AV1_MAIN)))
        .build()
        .probe();
  }

  private static VideoStreamProperties properties(VideoCodecProfile profile) {
    return VideoStreamProperties.builder()
        .codecProfile(Optional.of(profile))
        .codecLevel(OptionalInt.of(40))
        .codecTier(Optional.of(VideoCodecTier.MAIN))
        .firstFramesRead(true)
        .build();
  }

  /**
   * A 1080p 4:2:0 source, 10-bit unless it names its bit depth, whose keyframes open every 6 s
   * interval, beside stereo AAC.
   */
  @Builder
  record Source(
      @NonNull String codec,
      @NonNull String codecString,
      Integer bitDepth,
      @NonNull VideoDynamicRange dynamicRange,
      @NonNull Optional<VideoStreamProperties> videoProperties,
      FrameRateRule frameRateRule,
      Fraction maximumFrameRate) {

    MediaProbe probe() {
      var facts =
          VideoPlaybackFactsFixture.sdrH264FactsBuilder()
              .codecFamily(Optional.of(ProbedCodecFamily.fromProbedName(codec)))
              .dynamicRange(dynamicRange)
              .bitDepth(OptionalInt.of(bitDepth == null ? 10 : bitDepth))
              .codecString(Optional.of(codecString))
              .videoProperties(videoProperties)
              .maximumFrameRate(Optional.ofNullable(maximumFrameRate));
      if (frameRateRule != null) {
        facts.frameRateRule(frameRateRule);
      }

      return MediaProbe.builder()
          .duration(Duration.ofMinutes(10))
          .framerate(OptionalDouble.of(24))
          .width(1920)
          .height(1080)
          .videoCodec(codec)
          .audioCodec("aac")
          .streams(List.of(ProbeFixture.audioStreamBuilder("aac").index(2).build()))
          .bitrate(5_000_000)
          .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(2), Duration.ofMinutes(10)))
          .videoPlaybackFacts(Optional.of(facts.build()))
          .build();
    }
  }

  /** The declarations of a client, each beside H.264 SDR and stereo AAC. */
  private static final class Clients {

    // HEVC and AV1 entries that declare every base, profiles 8.1, 8.4, 10.1 and 10.4 up to the
    // maximum level, and HDR10+.
    static ClientCapabilityDeclaration dolbyVision(int maxLevel) {
      return defaultDeclarationBuilder()
          .video(
              List.of(
                  hevcEntryBuilder()
                      .compatibleBases(hdrBases())
                      .dolbyVisionProfiles(
                          profiles(
                              maxLevel,
                              DolbyVisionProfile.PROFILE_8_1,
                              DolbyVisionProfile.PROFILE_8_4))
                      .hdr10Plus(true)
                      .build(),
                  av1EntryBuilder()
                      .compatibleBases(hdrBases())
                      .dolbyVisionProfiles(
                          profiles(
                              maxLevel,
                              DolbyVisionProfile.PROFILE_10_1,
                              DolbyVisionProfile.PROFILE_10_4))
                      .hdr10Plus(true)
                      .build(),
                  h264EntryBuilder().build()))
          .build();
    }

    // HEVC and AV1 entries that declare every base and no Dolby Vision profile.
    static ClientCapabilityDeclaration baseOnly(boolean hdr10Plus) {
      return defaultDeclarationBuilder()
          .video(
              List.of(
                  hevcEntryBuilder().compatibleBases(hdrBases()).hdr10Plus(hdr10Plus).build(),
                  av1EntryBuilder().compatibleBases(hdrBases()).hdr10Plus(hdr10Plus).build(),
                  h264EntryBuilder().build()))
          .build();
    }

    // HEVC and AV1 entries that list profiles 5 and 10.0 up to the maximum level and no compatible
    // base, which a copy without a compatible base does not need (ADR 0049).
    static ClientCapabilityDeclaration baseless(int maxLevel) {
      return defaultDeclarationBuilder()
          .video(
              List.of(
                  hevcEntryBuilder()
                      .compatibleBases(List.of())
                      .dolbyVisionProfiles(profiles(maxLevel, DolbyVisionProfile.PROFILE_5))
                      .build(),
                  av1EntryBuilder()
                      .compatibleBases(List.of())
                      .dolbyVisionProfiles(profiles(maxLevel, DolbyVisionProfile.PROFILE_10_0))
                      .build(),
                  h264EntryBuilder().build()))
          .build();
    }

    static ClientCapabilityDeclaration h264Pq() {
      return defaultDeclarationBuilder()
          .video(List.of(h264EntryBuilder().compatibleBases(hdrBases()).build()))
          .build();
    }

    static ClientCapabilityDeclaration sdrOnly() {
      return declarationBuilderFor(
              ProbedCodecFamily.HEVC, ProbedCodecFamily.AV1, ProbedCodecFamily.H264)
          .build();
    }

    private static List<CompatibleBase> hdrBases() {
      return List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG);
    }

    private static List<DolbyVisionProfileCapability> profiles(
        int maxLevel, DolbyVisionProfile... profiles) {
      return Stream.of(profiles)
          .map(
              profile ->
                  DolbyVisionProfileCapability.builder()
                      .profile(profile)
                      .maxLevel(maxLevel)
                      .build())
          .toList();
    }
  }

  /** What a decision delivers: its mode, its copy's rewrites and Dolby Vision, and its range. */
  @Builder
  record Outcome(
      TranscodeMode mode,
      List<BitstreamRewrite> rewrites,
      Optional<DolbyVisionOutput> dolbyVisionOutput,
      VideoRange range) {

    static Outcome of(TranscodeDecision decision) {
      var dolbyVisionOutput =
          decision.plannedVideoOutput() instanceof PlannedVideoOutput.StreamCopy copy
              ? copy.dolbyVisionOutput()
              : Optional.<DolbyVisionOutput>empty();
      return Outcome.builder()
          .mode(decision.transcodeMode())
          .rewrites(decision.videoBitstreamRewrites())
          .dolbyVisionOutput(dolbyVisionOutput)
          .range(decision.videoRange())
          .build();
    }
  }
}
