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
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The decision for Dolby Vision on a PQ or HLG base (ADR 0013, ADR 0036): the server copies the
 * best picture that the client declares, one output for each client and format attempt.
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
      new Outcome(TranscodeMode.VIDEO_TRANSCODE, List.of(), Optional.empty(), VideoRange.SDR);

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          new SimpleMeterRegistry());

  @ParameterizedTest(name = "{0}")
  @MethodSource("decisionMatrix")
  @DisplayName(
      "Should copy the best picture that the client declares when the source carries Dolby Vision"
          + " on a PQ or HLG base")
  void shouldCopyTheBestPictureThatTheClientDeclaresWhenTheSourceCarriesDolbyVisionOnAPqOrHlgBase(
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
            new Outcome(
                TranscodeMode.REMUX,
                List.of(
                    BitstreamRewrite.STRIP_HDR10_PLUS,
                    BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD),
                Optional.of(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, SOURCE_LEVEL)),
                VideoRange.PQ)),
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
    return new Outcome(
        TranscodeMode.REMUX,
        List.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD),
        Optional.of(new DolbyVisionOutput(profile, SOURCE_LEVEL)),
        range);
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

  /** A 10-bit 1080p source whose keyframes open every 6 s interval, beside stereo AAC. */
  @Builder
  record Source(
      @NonNull String codec,
      @NonNull String codecString,
      @NonNull VideoDynamicRange dynamicRange,
      @NonNull Optional<VideoStreamProperties> videoProperties) {

    MediaProbe probe() {
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
          .videoPlaybackFacts(
              Optional.of(
                  VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                      .codecFamily(Optional.of(ProbedCodecFamily.fromProbedName(codec)))
                      .dynamicRange(dynamicRange)
                      .bitDepth(OptionalInt.of(10))
                      .codecString(Optional.of(codecString))
                      .videoProperties(videoProperties)
                      .build()))
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
      return new Outcome(
          decision.transcodeMode(),
          decision.videoBitstreamRewrites(),
          dolbyVisionOutput,
          decision.videoRange());
    }
  }
}
