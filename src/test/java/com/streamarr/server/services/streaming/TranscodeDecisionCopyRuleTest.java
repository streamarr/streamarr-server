package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.DynamicRangeChoice;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The copy rules of ADR 0013 and ADR 0036. The interim copy rule still holds for a probe version 4
 * outcome: the server copies the video only when every condition holds. The compatible base is SDR
 * or unspecified; no Dolby Vision compatibility identifier disagrees with the signaled transfer;
 * the source is not H.264, or is 8-bit 4:2:0 H.264; and the client names the source's codec. A copy
 * also needs the codec string that the probe stored, under a sample entry that a playlist can
 * describe: never hev1 or a Dolby Vision entry (ADR 0013). Since the third amendment, a PQ or HLG
 * source that probe version 5 described is copied to a client that declares its base
 * (streamarr/streamarr-server#396), and {@link TranscodeDecisionDolbyVisionCopyTest} covers Dolby
 * Vision on such a base and Dolby Vision without a compatible base.
 */
@Tag("UnitTest")
@DisplayName("Transcode Decision Copy Rule Tests")
class TranscodeDecisionCopyRuleTest {

  private static final Set<String> EVERY_VIDEO_CODEC_FAMILY = Set.of("h264", "hevc", "av1");

  private static final WorkerVideoCopySupport PLANNED_OUTPUT_WORKER =
      WorkerVideoCopySupport.builder().plannedOutput(true).bitstreamRewrites(Set.of()).build();

  private static final WorkerVideoCopySupport HDR10_PLUS_STRIPPING_WORKER =
      WorkerVideoCopySupport.builder()
          .plannedOutput(true)
          .bitstreamRewrites(Set.of(BitstreamRewrite.STRIP_HDR10_PLUS))
          .build();

  // What probe version 5 reports for an HEVC Main 10 stream at Main tier level 5.0.
  private static final VideoStreamProperties MAIN_10_PROPERTIES =
      VideoStreamProperties.builder()
          .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
          .codecLevel(OptionalInt.of(50))
          .codecTier(Optional.of(VideoCodecTier.MAIN))
          .firstFramesRead(true)
          .build();

  // The Dolby Vision configuration record's bl_signal_compatibility_id names the base format that
  // the stream claims: 0 none, 1 HDR10, 2 SDR, 4 HLG, and 6 the PQ base of profile 7.
  private static final Map<Integer, CompatibleBase> CLAIMED_BASE_BY_COMPATIBILITY_ID =
      Map.of(
          0, CompatibleBase.NONE,
          1, CompatibleBase.PQ,
          2, CompatibleBase.SDR,
          4, CompatibleBase.HLG,
          6, CompatibleBase.PQ);

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          new SimpleMeterRegistry());

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyCombination")
  @DisplayName("Should copy the video only when every condition of the interim copy rule holds")
  void shouldCopyTheVideoOnlyWhenEveryConditionOfTheInterimCopyRuleHolds(CopyCase copyCase) {
    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(copyCase.source().probe())
                    .capabilityDeclaration(
                        declarationBuilderFor(
                                copyCase.clientCodecs().toArray(ProbedCodecFamily[]::new))
                            .build())
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode().copiesVideo()).isEqualTo(copyCase.copyPermitted());
  }

  static Stream<CopyCase> everyCombination() {
    var cases = new ArrayList<CopyCase>();
    for (var sampleEntry : SampleEntry.values()) {
      for (var base : CompatibleBase.values()) {
        for (var dolbyVision : dolbyVisionClaims()) {
          if (!isRepresentable(base, dolbyVision)) {
            continue;
          }

          for (var bitDepth : List.of(OptionalInt.of(8), OptionalInt.of(10), OptionalInt.empty())) {
            for (var chroma : chromaSubsamplings()) {
              for (var clientCodecs : clientCodecLists()) {
                cases.add(
                    CopyCase.builder()
                        .source(
                            Source.builder()
                                .sampleEntry(sampleEntry)
                                .dynamicRange(
                                    new VideoDynamicRange(base, Presence.UNSPECIFIED, dolbyVision))
                                .bitDepth(bitDepth)
                                .chromaSubsampling(chroma)
                                .build())
                        .clientCodecs(clientCodecs)
                        .build());
              }
            }
          }
        }
      }
    }

    return cases.stream();
  }

  // ADR 0036 gives a compatible base of NONE only to a stream with Dolby Vision present.
  private static boolean isRepresentable(CompatibleBase base, DolbyVision dolbyVision) {
    return base != CompatibleBase.NONE || dolbyVision instanceof DolbyVision.Present;
  }

  private static List<DolbyVision> dolbyVisionClaims() {
    var claims = new ArrayList<DolbyVision>();
    claims.add(new DolbyVision.Unspecified());
    claims.add(new DolbyVision.Absent());
    claims.add(new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED));
    claims.add(dolbyVisionRecord(OptionalInt.empty()));
    for (var compatibilityId : List.of(0, 1, 2, 3, 4, 6)) {
      claims.add(dolbyVisionRecord(OptionalInt.of(compatibilityId)));
    }

    return claims;
  }

  private static List<Optional<ChromaSubsampling>> chromaSubsamplings() {
    return List.of(
        Optional.of(ChromaSubsampling.CHROMA_420),
        Optional.of(ChromaSubsampling.CHROMA_422),
        Optional.of(ChromaSubsampling.CHROMA_444),
        Optional.of(ChromaSubsampling.MONOCHROME),
        Optional.empty());
  }

  // The codec families of a web client, the Apple TV app and an AV1 client.
  private static List<List<ProbedCodecFamily>> clientCodecLists() {
    return List.of(
        List.of(ProbedCodecFamily.H264),
        List.of(ProbedCodecFamily.H264, ProbedCodecFamily.HEVC),
        List.of(ProbedCodecFamily.AV1, ProbedCodecFamily.H264));
  }

  @Test
  @DisplayName("Should copy a Dolby Vision 8.2 source under hvc1 when the client names HEVC")
  void shouldCopyADolbyVision82SourceUnderHvc1WhenTheClientNamesHevc() {
    var source =
        Source.builder()
            .sampleEntry(SampleEntry.HVC1)
            .dynamicRange(
                new VideoDynamicRange(
                    CompatibleBase.SDR, Presence.UNSPECIFIED, dolbyVisionRecord(OptionalInt.of(2))))
            .bitDepth(OptionalInt.of(10))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source.probe())
                    .capabilityDeclaration(
                        declarationBuilderFor(ProbedCodecFamily.H264, ProbedCodecFamily.HEVC)
                            .build())
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoSource().flatMap(VideoPlaybackFacts::codecString))
        .contains("hvc1.2.4.L120.90");
  }

  // ADR 0013's third amendment: the server copies PQ only to a client that declares it.
  @Test
  @DisplayName(
      "Should copy an HDR10 HEVC source when the Apple TV app declares HEVC with a PQ base")
  void shouldCopyAnHdr10HevcSourceWhenTheAppleTvAppDeclaresHevcWithAPqBase() {
    var decision =
        service
            .decide(
                hdrRequestBuilder(appleTvHdr10Source())
                    .capabilityDeclaration(
                        defaultDeclarationBuilder()
                            .video(
                                List.of(
                                    h264EntryBuilder().build(),
                                    hevcEntryBuilder()
                                        .compatibleBases(
                                            List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                                        .build()))
                            .build())
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoRange()).isEqualTo(VideoRange.PQ);
  }

  @Test
  @DisplayName(
      "Should encode an HDR10 HEVC source to H.264 when the Apple TV app declares HEVC with only an"
          + " SDR base")
  void shouldEncodeAnHdr10HevcSourceToH264WhenTheAppleTvAppDeclaresHevcWithOnlyAnSdrBase() {
    var decision =
        service
            .decide(
                hdrRequestBuilder(appleTvHdr10Source())
                    .capabilityDeclaration(
                        declarationBuilderFor(ProbedCodecFamily.H264, ProbedCodecFamily.HEVC)
                            .build())
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  // A probe version 5 outcome of an HDR10 stream whose HDR10+ and Dolby Vision the probe did not
  // check, as the Apple TV test of 2026-11-02 played it.
  private static Source appleTvHdr10Source() {
    return Source.builder()
        .sampleEntry(SampleEntry.HVC1)
        .dynamicRange(
            new VideoDynamicRange(
                CompatibleBase.PQ, Presence.UNSPECIFIED, new DolbyVision.Unspecified()))
        .bitDepth(OptionalInt.of(10))
        .videoProperties(Optional.of(MAIN_10_PROPERTIES))
        .build();
  }

  // Every connected worker can deliver a copy with a planned output, so only the declaration
  // decides.
  private static TranscodeDecisionRequest.TranscodeDecisionRequestBuilder hdrRequestBuilder(
      Source source) {
    return TranscodeDecisionRequest.builder()
        .source(source.probe())
        .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
        .videoCopySupport(List.of(PLANNED_OUTPUT_WORKER));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("hdrSourcesWithoutDolbyVision")
  @DisplayName("Should copy an HDR10 or HLG source when the client declares its base")
  void shouldCopyAnHdr10OrHlgSourceWhenTheClientDeclaresItsBase(
      String scenario, Source source, VideoRange planned) {
    var decision =
        service.decide(hdrRequestBuilder(source).capabilityDeclaration(hdrDeclaration()).build());

    assertThat(decision)
        .hasValueSatisfying(
            copy -> {
              assertThat(copy.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
              assertThat(copy.videoRange()).isEqualTo(planned);
            });
  }

  static Stream<Arguments> hdrSourcesWithoutDolbyVision() {
    return Stream.of(
        Arguments.of(
            "HDR10", hdrSource(CompatibleBase.PQ, new DolbyVision.Absent()), VideoRange.PQ),
        Arguments.of(
            "HLG", hdrSource(CompatibleBase.HLG, new DolbyVision.Absent()), VideoRange.HLG));
  }

  // ADR 0032: a file keeps its probe version 4 decision until its probe version 5 outcome is
  // stored, and probe version 4 reports no codec profile for the entry to check.
  @Test
  @DisplayName("Should encode a PQ source when its outcome is from probe version 4")
  void shouldEncodeAPqSourceWhenItsOutcomeIsFromProbeVersion4() {
    var version4 =
        Source.builder()
            .sampleEntry(SampleEntry.HVC1)
            .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
            .bitDepth(OptionalInt.of(10))
            .build();

    var decision =
        service
            .decide(hdrRequestBuilder(version4).capabilityDeclaration(hdrDeclaration()).build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  @Test
  @DisplayName("Should encode an HLG source when the client declares only a PQ base")
  void shouldEncodeAnHlgSourceWhenTheClientDeclaresOnlyAPqBase() {
    var pqOnly =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().build(),
                    hevcEntryBuilder()
                        .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                        .build()))
            .build();

    var decision =
        service
            .decide(
                hdrRequestBuilder(hdrSource(CompatibleBase.HLG, new DolbyVision.Absent()))
                    .capabilityDeclaration(pqOnly)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  // ADR 0036: a worker that predates the planned output would refuse the copy, so the client gets
  // the encode that it would get without the copy.
  @ParameterizedTest(name = "{0}")
  @MethodSource("workersThatCannotDeliverAPlannedOutput")
  @DisplayName("Should encode an HDR10 source when no connected worker lists the planned output")
  void shouldEncodeAnHdr10SourceWhenNoConnectedWorkerListsThePlannedOutput(
      String workers, List<WorkerVideoCopySupport> support) {
    var decision =
        service
            .decide(
                hdrRequestBuilder(hdrSource(CompatibleBase.PQ, new DolbyVision.Absent()))
                    .capabilityDeclaration(hdrDeclaration())
                    .videoCopySupport(support)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  static Stream<Arguments> workersThatCannotDeliverAPlannedOutput() {
    var olderWorker =
        WorkerVideoCopySupport.builder().plannedOutput(false).bitstreamRewrites(Set.of()).build();
    return Stream.of(
        Arguments.of("no worker read", List.of()),
        Arguments.of(
            "two workers that predate the planned output", List.of(olderWorker, olderWorker)));
  }

  // ADR 0036: a copy keeps HDR10+ for a client that declares it.
  @Test
  @DisplayName("Should copy an HDR10+ source with its HDR10+ when the client declares HDR10+")
  void shouldCopyAnHdr10PlusSourceWithItsHdr10PlusWhenTheClientDeclaresHdr10Plus() {
    var decision =
        service
            .decide(
                hdrRequestBuilder(hdr10PlusSource())
                    .capabilityDeclaration(hdrDeclaration())
                    .videoCopySupport(List.of(PLANNED_OUTPUT_WORKER))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoBitstreamRewrites()).isEmpty();
    assertThat(decision.plannedVideoOutput().dynamicRange().hdr10Plus())
        .isEqualTo(Presence.PRESENT);
  }

  // ADR 0036: the server lists the strip for a client that declares the base range but not HDR10+.
  @Test
  @DisplayName(
      "Should copy an HDR10+ source with HDR10+ stripped when the client declares PQ without"
          + " HDR10+ and a connected worker strips it")
  void
      shouldCopyAnHdr10PlusSourceWithHdr10PlusStrippedWhenTheClientDeclaresPqWithoutHdr10PlusAndAConnectedWorkerStripsIt() {
    var decision =
        service
            .decide(
                hdrRequestBuilder(hdr10PlusSource())
                    .capabilityDeclaration(pqDeclarationWithoutHdr10Plus())
                    .videoCopySupport(List.of(HDR10_PLUS_STRIPPING_WORKER))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoBitstreamRewrites())
        .containsExactly(BitstreamRewrite.STRIP_HDR10_PLUS);
    assertThat(decision.plannedVideoOutput().dynamicRange())
        .isEqualTo(VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ));
  }

  // ADR 0036: when no connected worker can strip HDR10+, the server does not plan that copy, and
  // the client receives an encode instead.
  @ParameterizedTest(name = "{0}")
  @MethodSource("workersThatCannotStripHdr10Plus")
  @DisplayName(
      "Should encode an HDR10+ source when the client declares PQ without HDR10+ and no connected"
          + " worker strips it")
  void
      shouldEncodeAnHdr10PlusSourceWhenTheClientDeclaresPqWithoutHdr10PlusAndNoConnectedWorkerStripsIt(
          String workers, List<WorkerVideoCopySupport> support) {
    var decision =
        service
            .decide(
                hdrRequestBuilder(hdr10PlusSource())
                    .capabilityDeclaration(pqDeclarationWithoutHdr10Plus())
                    .videoCopySupport(support)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoBitstreamRewrites()).isEmpty();
  }

  static Stream<Arguments> workersThatCannotStripHdr10Plus() {
    var stripsWithoutPlannedOutput =
        WorkerVideoCopySupport.builder()
            .plannedOutput(false)
            .bitstreamRewrites(Set.of(BitstreamRewrite.STRIP_HDR10_PLUS))
            .build();
    var stripsOnlyDolbyVision =
        WorkerVideoCopySupport.builder()
            .plannedOutput(true)
            .bitstreamRewrites(Set.of(BitstreamRewrite.STRIP_DOLBY_VISION))
            .build();
    return Stream.of(
        Arguments.of(
            "a worker that lists the planned output alone", List.of(PLANNED_OUTPUT_WORKER)),
        Arguments.of(
            "a worker that lists only the Dolby Vision strip", List.of(stripsOnlyDolbyVision)),
        Arguments.of(
            "a worker that lists the strip but not the planned output",
            List.of(stripsWithoutPlannedOutput)));
  }

  private static Source hdr10PlusSource() {
    return Source.builder()
        .sampleEntry(SampleEntry.HVC1)
        .dynamicRange(
            new VideoDynamicRange(CompatibleBase.PQ, Presence.PRESENT, new DolbyVision.Absent()))
        .bitDepth(OptionalInt.of(10))
        .videoProperties(Optional.of(MAIN_10_PROPERTIES))
        .build();
  }

  private static ClientCapabilityDeclaration pqDeclarationWithoutHdr10Plus() {
    return defaultDeclarationBuilder()
        .video(
            List.of(
                h264EntryBuilder().build(),
                hevcEntryBuilder()
                    .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                    .build()))
        .build();
  }

  // One HEVC entry that declares every HDR format of the sources above, beside H.264.
  private static ClientCapabilityDeclaration hdrDeclaration() {
    var hdrEntry =
        hevcEntryBuilder()
            .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
            .dolbyVisionProfiles(
                List.of(
                    DolbyVisionProfileCapability.builder()
                        .profile(DolbyVisionProfile.PROFILE_8_1)
                        .maxLevel(13)
                        .build(),
                    DolbyVisionProfileCapability.builder()
                        .profile(DolbyVisionProfile.PROFILE_8_4)
                        .maxLevel(13)
                        .build()))
            .hdr10Plus(true)
            .build();
    return defaultDeclarationBuilder().video(List.of(hdrEntry, h264EntryBuilder().build())).build();
  }

  private static Source hdrSource(CompatibleBase base, DolbyVision dolbyVision) {
    return Source.builder()
        .sampleEntry(SampleEntry.HVC1)
        .dynamicRange(new VideoDynamicRange(base, Presence.ABSENT, dolbyVision))
        .bitDepth(OptionalInt.of(10))
        .videoProperties(Optional.of(MAIN_10_PROPERTIES))
        .build();
  }

  // ADR 0013's HDR choice: SDR is a choice in a fixed quality only, and a copy keeps the source's
  // range, so SDR for an HDR source needs an encode.
  @ParameterizedTest(name = "{0}")
  @MethodSource("hdrSourcesThatACopyKeepsHdr")
  @DisplayName(
      "Should encode an HDR source and name the viewer's SDR choice when the viewer chooses SDR in"
          + " a fixed quality")
  void shouldEncodeAnHdrSourceAndNameTheViewersSdrChoiceWhenTheViewerChoosesSdrInAFixedQuality(
      Source source) {
    var decision =
        service
            .decide(
                hdrRequestBuilder(source)
                    .capabilityDeclaration(hdrDeclaration())
                    .viewerLimits(limits(VideoQuality.UHD_4K, DynamicRangeChoice.SDR))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoRange()).isEqualTo(VideoRange.SDR);
    assertThat(decision.dynamicRangeChoice()).contains(DynamicRangeChoice.SDR);
    assertThat(decision.reasons()).contains(DecisionReason.VIEWER_CHOSE_SDR);
  }

  // Original always plays the source's range, and Auto hides the HDR toggle (ADR 0013, ADR 0052).
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "ORIGINAL"})
  @DisplayName(
      "Should copy an HDR10 source and carry no HDR choice when the viewer chooses SDR in Auto or"
          + " Original")
  void shouldCopyAnHdr10SourceAndCarryNoHdrChoiceWhenTheViewerChoosesSdrInAutoOrOriginal(
      VideoQuality quality) {
    var decision =
        service
            .decide(
                hdrRequestBuilder(hdrSource(CompatibleBase.PQ, new DolbyVision.Absent()))
                    .capabilityDeclaration(hdrDeclaration())
                    .viewerLimits(limits(quality, DynamicRangeChoice.SDR))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoRange()).isEqualTo(VideoRange.PQ);
    assertThat(decision.dynamicRangeChoice()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should copy an HDR10 source and carry the viewer's HDR choice when the viewer chooses HDR in"
          + " a fixed quality")
  void shouldCopyAnHdr10SourceAndCarryTheViewersHdrChoiceWhenTheViewerChoosesHdrInAFixedQuality() {
    var decision =
        service
            .decide(
                hdrRequestBuilder(hdrSource(CompatibleBase.PQ, new DolbyVision.Absent()))
                    .capabilityDeclaration(hdrDeclaration())
                    .viewerLimits(limits(VideoQuality.UHD_4K, DynamicRangeChoice.HDR))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoRange()).isEqualTo(VideoRange.PQ);
    assertThat(decision.dynamicRangeChoice()).contains(DynamicRangeChoice.HDR);
  }

  @ParameterizedTest
  @EnumSource(
      value = CompatibleBase.class,
      names = {"SDR", "UNSPECIFIED"})
  @DisplayName("Should copy an SDR source when the viewer chooses SDR in a fixed quality")
  void shouldCopyAnSdrSourceWhenTheViewerChoosesSdrInAFixedQuality(CompatibleBase base) {
    var decision =
        service
            .decide(
                hdrRequestBuilder(hdrSource(base, new DolbyVision.Absent()))
                    .capabilityDeclaration(hdrDeclaration())
                    .viewerLimits(limits(VideoQuality.UHD_4K, DynamicRangeChoice.SDR))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.reasons()).doesNotContain(DecisionReason.VIEWER_CHOSE_SDR);
  }

  // ADR 0013: the server never replaces an explicit SDR choice with HDR, so without an SDR encode
  // the session finds no video format.
  @Test
  @DisplayName(
      "Should find no video format for an HDR10 source when the viewer chooses SDR in a fixed"
          + " quality and no encode can play it")
  void shouldFindNoVideoFormatForAnHdr10SourceWhenTheViewerChoosesSdrAndNoEncodeCanPlayIt() {
    var decision =
        service.decide(
            hdrRequestBuilder(hdrSource(CompatibleBase.PQ, new DolbyVision.Absent()))
                .encodableVideoCodecFamilies(Set.of())
                .capabilityDeclaration(hdrDeclaration())
                .viewerLimits(limits(VideoQuality.UHD_4K, DynamicRangeChoice.SDR))
                .build());

    assertThat(decision).isEmpty();
  }

  static Stream<Named<Source>> hdrSourcesThatACopyKeepsHdr() {
    return Stream.of(
        Named.of("HDR10", hdrSource(CompatibleBase.PQ, new DolbyVision.Absent())),
        Named.of("HLG", hdrSource(CompatibleBase.HLG, new DolbyVision.Absent())),
        Named.of(
            "Dolby Vision profile 8.1",
            hdrSource(CompatibleBase.PQ, dolbyVisionRecord(OptionalInt.of(1)))));
  }

  private static ViewerLimits limits(VideoQuality quality, DynamicRangeChoice choice) {
    return ViewerLimits.of(
        StreamingOptions.builder().quality(quality).dynamicRange(choice).build());
  }

  @Test
  @DisplayName("Should encode a Dolby Vision 7 source when it is tagged as SDR")
  void shouldEncodeADolbyVision7SourceWhenItIsTaggedAsSdr() {
    var source =
        Source.builder()
            .sampleEntry(SampleEntry.HVC1)
            .dynamicRange(
                new VideoDynamicRange(
                    CompatibleBase.SDR, Presence.UNSPECIFIED, dolbyVisionRecord(OptionalInt.of(6))))
            .bitDepth(OptionalInt.of(10))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source.probe())
                    .capabilityDeclaration(
                        declarationBuilderFor(ProbedCodecFamily.H264, ProbedCodecFamily.HEVC)
                            .build())
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  @Test
  @DisplayName("Should encode a 10-bit H.264 source when every client names H.264")
  void shouldEncodeA10BitH264SourceWhenEveryClientNamesH264() {
    var source =
        Source.builder().sampleEntry(SampleEntry.AVC1).bitDepth(OptionalInt.of(10)).build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source.probe())
                    .capabilityDeclaration(declarationBuilderFor(ProbedCodecFamily.H264).build())
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  @Test
  @DisplayName("Should describe the chosen video stream when the decision copies its video")
  void shouldDescribeTheChosenVideoStreamWhenTheDecisionCopiesItsVideo() {
    var range =
        new VideoDynamicRange(
            CompatibleBase.SDR, Presence.UNSPECIFIED, new DolbyVision.Unspecified());
    var source = Source.builder().sampleEntry(SampleEntry.AVC1).dynamicRange(range).build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source.probe())
                    .capabilityDeclaration(declarationBuilderFor(ProbedCodecFamily.H264).build())
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoSource()).isEqualTo(source.probe().videoPlaybackFacts());
  }

  @Test
  @DisplayName("Should describe the chosen video stream when the decision encodes its video")
  void shouldDescribeTheChosenVideoStreamWhenTheDecisionEncodesItsVideo() {
    var range =
        new VideoDynamicRange(
            CompatibleBase.HLG, Presence.UNSPECIFIED, new DolbyVision.Unspecified());
    var source = Source.builder().sampleEntry(SampleEntry.HVC1).dynamicRange(range).build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source.probe())
                    .capabilityDeclaration(declarationBuilderFor(ProbedCodecFamily.H264).build())
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoSource()).isEqualTo(source.probe().videoPlaybackFacts());
  }

  @Test
  @DisplayName("Should encode the video when the probe reported no playback facts")
  void shouldEncodeTheVideoWhenTheProbeReportedNoPlaybackFacts() {
    var probe =
        Source.builder()
            .sampleEntry(SampleEntry.AVC1)
            .build()
            .probeBuilder()
            .videoPlaybackFacts(Optional.empty())
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe)
                    .capabilityDeclaration(declarationBuilderFor(ProbedCodecFamily.H264).build())
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoSource()).isEmpty();
  }

  private static DolbyVision dolbyVisionRecord(OptionalInt compatibilityId) {
    return new DolbyVision.Present(
        Optional.of(
            DolbyVisionConfiguration.builder()
                .profile(OptionalInt.of(8))
                .baseLayerCompatibilityId(compatibilityId)
                .baseLayer(true)
                .rpu(true)
                .build()),
        Presence.UNSPECIFIED);
  }

  /**
   * The sample entry that a copy of the source would carry, which the probe names at the start of
   * the stored codec string. NONE stands for a source whose probe stored no codec string.
   */
  enum SampleEntry {
    AVC1("h264", Optional.of("avc1.640028")),
    HVC1("hevc", Optional.of("hvc1.2.4.L120.90")),
    HEV1("hevc", Optional.of("hev1.2.4.L120.90")),
    DVH1("hevc", Optional.of("dvh1.08.06")),
    AV01("av1", Optional.of("av01.0.08M.08")),
    NONE("hevc", Optional.empty());

    private final String codec;
    private final Optional<String> codecString;

    SampleEntry(String codec, Optional<String> codecString) {
      this.codec = codec;
      this.codecString = codecString;
    }

    boolean isDescribableInAPlaylist() {
      return this == AVC1 || this == HVC1 || this == AV01;
    }
  }

  @Builder
  record Source(
      @NonNull SampleEntry sampleEntry,
      VideoDynamicRange dynamicRange,
      OptionalInt bitDepth,
      Optional<ChromaSubsampling> chromaSubsampling,
      Optional<VideoStreamProperties> videoProperties) {

    Source {
      dynamicRange =
          dynamicRange == null
              ? new VideoDynamicRange(
                  CompatibleBase.SDR, Presence.UNSPECIFIED, new DolbyVision.Unspecified())
              : dynamicRange;
      bitDepth = bitDepth == null ? OptionalInt.of(8) : bitDepth;
      chromaSubsampling =
          chromaSubsampling == null ? Optional.of(ChromaSubsampling.CHROMA_420) : chromaSubsampling;
      videoProperties = videoProperties == null ? Optional.empty() : videoProperties;
    }

    // The chosen video stream is stream 1, after cover art. Keyframes open every 6 s grid
    // interval, so the keyframes never rule out a copy.
    MediaProbe probe() {
      return probeBuilder().build();
    }

    MediaProbe.MediaProbeBuilder probeBuilder() {
      return MediaProbe.builder()
          .duration(Duration.ofMinutes(10))
          .framerate(OptionalDouble.of(24))
          .width(1920)
          .height(1080)
          .videoCodec(sampleEntry.codec)
          .audioCodec("aac")
          .streams(List.of(ProbeFixture.audioStreamBuilder("aac").index(2).build()))
          .bitrate(5_000_000)
          .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(2), Duration.ofMinutes(10)))
          .videoPlaybackFacts(
              Optional.of(
                  VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                      .streamIndex(1)
                      .dynamicRange(dynamicRange)
                      .bitDepth(bitDepth)
                      .chromaSubsampling(chromaSubsampling)
                      .codecString(sampleEntry.codecString)
                      .videoProperties(videoProperties)
                      .build()));
    }

    boolean isEightBitFourTwoZero() {
      return bitDepth.equals(OptionalInt.of(8))
          && chromaSubsampling.equals(Optional.of(ChromaSubsampling.CHROMA_420));
    }

    boolean hasDisagreeingDolbyVisionCompatibility() {
      if (!(dynamicRange.dolbyVision() instanceof DolbyVision.Present(var declared, _, _, _, _, _))
          || declared.isEmpty()
          || declared.get().baseLayerCompatibilityId().isEmpty()) {
        return false;
      }

      var claimed =
          CLAIMED_BASE_BY_COMPATIBILITY_ID.get(
              declared.get().baseLayerCompatibilityId().getAsInt());
      return claimed != dynamicRange.compatibleBase();
    }
  }

  @Builder
  record CopyCase(@NonNull Source source, @NonNull List<ProbedCodecFamily> clientCodecs) {

    boolean copyPermitted() {
      var base = source.dynamicRange().compatibleBase();
      return (base == CompatibleBase.SDR || base == CompatibleBase.UNSPECIFIED)
          && !source.hasDisagreeingDolbyVisionCompatibility()
          && (!"h264".equals(source.sampleEntry().codec) || source.isEightBitFourTwoZero())
          && clientCodecs.contains(ProbedCodecFamily.fromProbedName(source.sampleEntry().codec))
          && source.sampleEntry().isDescribableInAPlaylist();
    }

    @Override
    public String toString() {
      return String.join(
          " ",
          source.sampleEntry().name(),
          source.dynamicRange().compatibleBase().name(),
          source.dynamicRange().dolbyVision().toString(),
          source.bitDepth().toString(),
          source.chromaSubsampling().toString(),
          clientCodecs.toString());
    }
  }
}
