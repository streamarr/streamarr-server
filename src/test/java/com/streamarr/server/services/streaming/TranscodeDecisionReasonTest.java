package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.FieldOrder;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
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
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Transcode Decision Reason Tests")
class TranscodeDecisionReasonTest {

  private static final Set<String> EVERY_VIDEO_CODEC_FAMILY = Set.of("h264", "hevc", "av1");
  private static final KeyframeKnowledge KEYFRAMES_EVERY_TWO_SECONDS =
      ProbeFixture.keyframesEvery(Duration.ofSeconds(2), Duration.ofMinutes(10));

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          new SimpleMeterRegistry());

  @Test
  @DisplayName(
      "Should give the codec profile as the reason when the client's entry does not accept the"
          + " source's codec profile")
  void
      shouldGiveTheCodecProfileAsTheReasonWhenTheClientsEntryDoesNotAcceptTheSourcesCodecProfile() {
    var mainOnly =
        client(
            h264EntryBuilder()
                .codecProfiles(List.of(h264(VideoCodecProfile.H264_MAIN, 52)))
                .build());

    var decision = decide(Source.builder().build().probe(), mainOnly);

    assertThat(decision.transcodeMode().copiesVideo()).isFalse();
    assertThat(decision.reasons()).containsExactly(DecisionReason.VIDEO_CODEC_PROFILE_NOT_DECLARED);
  }

  @Test
  @DisplayName("Should give no reason when the client accepts a copy of the video and the audio")
  void shouldGiveNoReasonWhenTheClientAcceptsACopyOfTheVideoAndTheAudio() {
    var decision = decide(Source.builder().build().probe(), defaultDeclarationBuilder().build());

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.reasons()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refusedProperties")
  @DisplayName("Should name each property that the closest entry refuses when the video is encoded")
  void shouldNameEachPropertyThatTheClosestEntryRefusesWhenTheVideoIsEncoded(
      MediaProbe source, ClientCapabilityDeclaration client, List<DecisionReason> expected) {
    var decision = decide(source, client);

    assertThat(decision.transcodeMode().copiesVideo()).isFalse();
    assertThat(decision.reasons()).containsExactlyElementsOf(expected);
  }

  static Stream<Arguments> refusedProperties() {
    return Stream.of(
        Arguments.of(
            Named.of(
                "an HEVC source to a client that declares only H.264",
                hevcMain10().build().probe()),
            defaultDeclarationBuilder().build(),
            List.of(DecisionReason.VIDEO_CODEC_NOT_DECLARED)),
        Arguments.of(
            Named.of(
                "an H.264 source at level 4.0 to an entry up to level 3.1",
                Source.builder().build().probe()),
            client(
                h264EntryBuilder()
                    .codecProfiles(List.of(h264(VideoCodecProfile.H264_HIGH, 31)))
                    .build()),
            List.of(DecisionReason.VIDEO_CODEC_LEVEL_NOT_DECLARED)),
        Arguments.of(
            Named.of("a 10-bit HEVC source to an 8-bit entry", hevcMain10().build().probe()),
            client(
                hevcEntryBuilder()
                    .maxBitDepth(8)
                    .codecProfiles(List.of(mainTier(VideoCodecProfile.HEVC_MAIN_10)))
                    .build()),
            List.of(DecisionReason.VIDEO_BIT_DEPTH_NOT_DECLARED)),
        Arguments.of(
            Named.of(
                "an HEVC source whose bit depth the probe did not report",
                hevcMain10().bitDepthUnreported(true).build().probe()),
            client(
                hevcEntryBuilder()
                    .codecProfiles(List.of(mainTier(VideoCodecProfile.HEVC_MAIN_10)))
                    .build()),
            List.of(DecisionReason.VIDEO_BIT_DEPTH_UNKNOWN)),
        Arguments.of(
            Named.of("a 1080p source to a 720p entry", Source.builder().build().probe()),
            client(h264EntryBuilder().maxWidth(1280).maxHeight(720).build()),
            List.of(DecisionReason.VIDEO_PICTURE_SIZE_NOT_DECLARED)),
        Arguments.of(
            Named.of("a 24 fps source to a 15 fps entry", Source.builder().build().probe()),
            client(h264EntryBuilder().maxFrameRate(15).build()),
            List.of(DecisionReason.VIDEO_FRAME_RATE_NOT_DECLARED)),
        Arguments.of(
            Named.of(
                "a PQ source to an SDR entry",
                hevcMain10().dynamicRange(range(CompatibleBase.PQ)).build().probe()),
            client(
                hevcEntryBuilder()
                    .codecProfiles(List.of(mainTier(VideoCodecProfile.HEVC_MAIN_10)))
                    .build()),
            List.of(DecisionReason.DYNAMIC_RANGE_NOT_DECLARED)),
        Arguments.of(
            Named.of(
                "a Dolby Vision 8.1 source to an SDR entry",
                hevcMain10().dynamicRange(profile81()).build().probe()),
            client(
                hevcEntryBuilder()
                    .codecProfiles(List.of(mainTier(VideoCodecProfile.HEVC_MAIN_10)))
                    .build()),
            List.of(
                DecisionReason.DOLBY_VISION_NOT_DECLARED,
                DecisionReason.DYNAMIC_RANGE_NOT_DECLARED)),
        Arguments.of(
            Named.of(
                "a source that one entry refuses for two properties and another for one",
                Source.builder().build().probe()),
            client(
                h264EntryBuilder().maxWidth(1280).maxHeight(720).maxFrameRate(15).build(),
                h264EntryBuilder().maxFrameRate(15).build()),
            List.of(DecisionReason.VIDEO_FRAME_RATE_NOT_DECLARED)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesWithoutPartOfTheSource")
  @DisplayName(
      "Should name the dynamic-range information that the client does not declare when the copy"
          + " strips it")
  void shouldNameTheDynamicRangeInformationThatTheClientDoesNotDeclareWhenTheCopyStripsIt(
      VideoDynamicRange range, DecisionReason expected) {
    var source = hevcMain10().dynamicRange(range).build().probe();
    var pqClient =
        client(
            hevcEntryBuilder()
                .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                .codecProfiles(List.of(mainTier(VideoCodecProfile.HEVC_MAIN_10)))
                .build());

    var decision = decide(source, pqClient);

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.reasons()).containsExactly(expected);
  }

  static Stream<Arguments> copiesWithoutPartOfTheSource() {
    return Stream.of(
        Arguments.of(
            Named.of("Dolby Vision 8.1", profile81()), DecisionReason.DOLBY_VISION_NOT_DECLARED),
        Arguments.of(
            Named.of(
                "HDR10+",
                new VideoDynamicRange(
                    CompatibleBase.PQ, Presence.PRESENT, new DolbyVision.Absent())),
            DecisionReason.HDR10_PLUS_NOT_DECLARED));
  }

  @Test
  @DisplayName(
      "Should name only what the accepting entry refused when a later copy plan strips Dolby Vision")
  void shouldNameOnlyWhatTheAcceptingEntryRefusedWhenALaterCopyPlanStripsDolbyVision() {
    var source = hevcMain10().dynamicRange(profile81()).build().probe();
    var pqBases = List.of(CompatibleBase.SDR, CompatibleBase.PQ);
    var smallDolbyVision =
        hevcEntryBuilder()
            .maxWidth(1280)
            .maxHeight(720)
            .compatibleBases(pqBases)
            .codecProfiles(List.of(mainTier(VideoCodecProfile.HEVC_MAIN_10)))
            .dolbyVisionProfiles(List.of(profile81Capability()))
            .build();
    var fullSizePq =
        hevcEntryBuilder()
            .compatibleBases(pqBases)
            .codecProfiles(List.of(mainTier(VideoCodecProfile.HEVC_MAIN_10)))
            .build();

    var decision = decide(source, client(smallDolbyVision, fullSizePq));

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.reasons()).containsExactly(DecisionReason.DOLBY_VISION_NOT_DECLARED);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("encodeOnlyDecisions")
  @DisplayName(
      "Should name why the copy was refused, and count no refusal, when the decision may only encode")
  void shouldNameWhyTheCopyWasRefusedAndCountNoRefusalWhenTheDecisionMayOnlyEncode(
      MediaProbe source, List<DecisionReason> expected) {
    var meters = new SimpleMeterRegistry();
    var encodeOnly =
        new TranscodeDecisionService(
                StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
                meters)
            .decide(
                request(source, defaultDeclarationBuilder().build())
                    .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                    .build())
            .orElseThrow();

    assertThat(encodeOnly.transcodeMode().copiesVideo()).isFalse();
    assertThat(encodeOnly.reasons()).containsExactlyElementsOf(expected);
    assertThat(meters.find("streamarr.streaming.stream_copy_refusals").counter()).isNull();
  }

  static Stream<Arguments> encodeOnlyDecisions() {
    return Stream.of(
        Arguments.of(Named.of("a copyable source", Source.builder().build().probe()), List.of()),
        Arguments.of(
            Named.of("an HEVC source to an H.264 client", hevcMain10().build().probe()),
            List.of(DecisionReason.VIDEO_CODEC_NOT_DECLARED)),
        Arguments.of(
            Named.of(
                "a source whose keyframes leave an interval empty",
                Source.builder()
                    .keyframes(
                        ProbeFixture.keyframesEvery(
                            Duration.ofMillis(10_010), Duration.ofMinutes(10)))
                    .build()
                    .probe()),
            List.of(DecisionReason.KEYFRAMES_TOO_SPARSE)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesThatTheCopyRulesRefuse")
  @DisplayName("Should name the source property that rules out a copy when the video is encoded")
  void shouldNameTheSourcePropertyThatRulesOutACopyWhenTheVideoIsEncoded(
      MediaProbe source, DecisionReason expected) {
    var decision = decide(source, everyRangeClient());

    assertThat(decision.transcodeMode().copiesVideo()).isFalse();
    assertThat(decision.reasons()).containsExactly(expected);
  }

  static Stream<Arguments> sourcesThatTheCopyRulesRefuse() {
    return Stream.of(
        Arguments.of(
            Named.of(
                "an interlaced source",
                Source.builder()
                    .frameRateRule(
                        VideoPlaybackFactsFixture.interlacedRateRule(
                            new Fraction(25, 1), FieldOrder.TT))
                    .build()
                    .probe()),
            DecisionReason.SOURCE_INTERLACED),
        Arguments.of(
            Named.of(
                "Dolby Vision whose RPUs start their samples",
                hevcMain10().dynamicRange(rpusStartingTheirSamples()).build().probe()),
            DecisionReason.DOLBY_VISION_RPU_STARTS_SAMPLE),
        Arguments.of(
            Named.of(
                "Dolby Vision without a compatible base",
                hevcMain10()
                    .dynamicRange(VideoPlaybackFactsFixture.dolbyVisionProfile5())
                    .build()
                    .probe()),
            DecisionReason.DOLBY_VISION_WITHOUT_COMPATIBLE_BASE),
        Arguments.of(
            Named.of(
                "a PQ source that probe version 4 described",
                hevcMain10()
                    .videoProperties(Optional.empty())
                    .dynamicRange(range(CompatibleBase.PQ))
                    .build()
                    .probe()),
            DecisionReason.VIDEO_PROPERTIES_NOT_PROBED),
        Arguments.of(
            Named.of("a 10-bit H.264 source", Source.builder().bitDepth(10).build().probe()),
            DecisionReason.H264_NOT_8_BIT_4_2_0),
        Arguments.of(
            Named.of(
                "an H.264 source whose bit depth the probe did not report",
                Source.builder().bitDepthUnreported(true).build().probe()),
            DecisionReason.VIDEO_BIT_DEPTH_UNKNOWN),
        Arguments.of(
            Named.of(
                "an H.264 source whose chroma subsampling the probe did not report",
                Source.builder().chromaSubsampling(Optional.empty()).build().probe()),
            DecisionReason.VIDEO_CHROMA_SUBSAMPLING_UNKNOWN),
        Arguments.of(
            Named.of(
                "a 4:2:2 H.264 source",
                Source.builder()
                    .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_422))
                    .build()
                    .probe()),
            DecisionReason.H264_NOT_8_BIT_4_2_0),
        Arguments.of(
            Named.of(
                "an HEVC source under the hev1 sample entry",
                hevcMain10().codecString("hev1.2.4.L120.90").build().probe()),
            DecisionReason.SAMPLE_ENTRY_NOT_COPYABLE),
        Arguments.of(
            Named.of(
                "a source whose codec profile the probe could not name",
                hevcMain10()
                    .videoProperties(
                        Optional.of(
                            VideoStreamProperties.builder()
                                .codecProfile(Optional.empty())
                                .firstFramesRead(true)
                                .build()))
                    .build()
                    .probe()),
            DecisionReason.VIDEO_CODEC_PROFILE_UNKNOWN),
        Arguments.of(
            Named.of(
                "a source whose keyframes the probe did not report",
                Source.builder().keyframes(new KeyframeKnowledge.NotReported()).build().probe()),
            DecisionReason.KEYFRAMES_UNKNOWN),
        Arguments.of(
            Named.of(
                "a source whose keyframes leave an interval empty",
                Source.builder()
                    .keyframes(
                        ProbeFixture.keyframesEvery(
                            Duration.ofMillis(10_010), Duration.ofMinutes(10)))
                    .build()
                    .probe()),
            DecisionReason.KEYFRAMES_TOO_SPARSE));
  }

  @Test
  @DisplayName(
      "Should name the viewer's picture limit when a fixed quality is smaller than the source")
  void shouldNameTheViewersPictureLimitWhenAFixedQualityIsSmallerThanTheSource() {
    var decision =
        decide(
            request(Source.builder().build().probe(), defaultDeclarationBuilder().build())
                .viewerLimits(
                    ViewerLimits.of(
                        StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build()))
                .build());

    assertThat(decision.transcodeMode().copiesVideo()).isFalse();
    assertThat(decision.reasons()).containsExactly(DecisionReason.PICTURE_ABOVE_VIEWER_LIMIT);
  }

  @Test
  @DisplayName(
      "Should name the viewer's bitrate limit when no audio step brings a copy within the maximum")
  void shouldNameTheViewersBitrateLimitWhenNoAudioStepBringsACopyWithinTheMaximum() {
    var decision =
        decide(
            request(Source.builder().build().probe(), defaultDeclarationBuilder().build())
                .viewerLimits(ViewerLimits.builder().maxBitrate(OptionalLong.of(1_000_000)).build())
                .build());

    assertThat(decision.transcodeMode().copiesVideo()).isFalse();
    assertThat(decision.reasons()).containsExactly(DecisionReason.BITRATE_ABOVE_VIEWER_LIMIT);
  }

  @Test
  @DisplayName(
      "Should name both viewer limits when a fixed quality and the maximum bitrate both refuse a"
          + " copy")
  void shouldNameBothViewerLimitsWhenAFixedQualityAndTheMaximumBitrateBothRefuseACopy() {
    var limits =
        ViewerLimits.builder()
            .quality(VideoQuality.HIGH_720P)
            .maxBitrate(OptionalLong.of(1_000_000))
            .build();
    var decision =
        decide(
            request(Source.builder().build().probe(), defaultDeclarationBuilder().build())
                .viewerLimits(limits)
                .build());

    assertThat(decision.transcodeMode().copiesVideo()).isFalse();
    assertThat(decision.reasons())
        .containsExactly(
            DecisionReason.PICTURE_ABOVE_VIEWER_LIMIT, DecisionReason.BITRATE_ABOVE_VIEWER_LIMIT);
  }

  @Test
  @DisplayName(
      "Should name the HDR10+ that a Dolby Vision copy strips when the client declares both")
  void shouldNameTheHdr10PlusThatADolbyVisionCopyStripsWhenTheClientDeclaresBoth() {
    var dolbyVision = profile81();
    var withHdr10Plus =
        new VideoDynamicRange(
            dolbyVision.compatibleBase(), Presence.PRESENT, dolbyVision.dolbyVision());
    var source = hevcMain10().dynamicRange(withHdr10Plus).build().probe();

    var decision = decide(source, everyRangeClient());

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.videoBitstreamRewrites()).contains(BitstreamRewrite.STRIP_HDR10_PLUS);
    assertThat(decision.reasons())
        .containsExactly(DecisionReason.HDR10_PLUS_STRIPPED_BESIDE_DOLBY_VISION);
  }

  @Test
  @DisplayName("Should name the missing worker when no connected worker delivers the copy")
  void shouldNameTheMissingWorkerWhenNoConnectedWorkerDeliversTheCopy() {
    var source = hevcMain10().dynamicRange(profile81()).build().probe();
    var dolbyVisionClient = everyRangeClient();

    var decision =
        decide(
            request(source, dolbyVisionClient)
                .videoCopySupport(
                    List.of(
                        WorkerVideoCopySupport.builder()
                            .plannedOutput(false)
                            .bitstreamRewrites(Set.of())
                            .build()))
                .build());

    assertThat(decision.transcodeMode().copiesVideo()).isFalse();
    assertThat(decision.reasons()).containsExactly(DecisionReason.NO_WORKER_DELIVERS_COPY);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("encodedAudio")
  @DisplayName("Should name why the audio is encoded beside a copy of the video")
  void shouldNameWhyTheAudioIsEncodedBesideACopyOfTheVideo(
      MediaProbe source, ClientCapabilityDeclaration client, DecisionReason expected) {
    var decision = decide(source, client);

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.reasons()).containsExactly(expected);
  }

  static Stream<Arguments> encodedAudio() {
    return Stream.of(
        Arguments.of(
            Named.of(
                "FLAC to a client that declares only AAC",
                Source.builder().audioCodec("flac").build().probe()),
            defaultDeclarationBuilder().build(),
            DecisionReason.AUDIO_CODEC_NOT_DECLARED),
        Arguments.of(
            Named.of(
                "5.1 AC-3 to a stereo AC-3 entry",
                Source.builder().audioCodec("ac3").audioChannels(6).build().probe()),
            defaultDeclarationBuilder()
                .audio(
                    List.of(
                        audioEntryBuilder(AudioCodec.AC3).build(),
                        audioEntryBuilder(AudioCodec.AAC).build()))
                .build(),
            DecisionReason.AUDIO_CHANNELS_NOT_DECLARED),
        Arguments.of(
            Named.of("AAC Main", Source.builder().audioCodecString("mp4a.40.1").build().probe()),
            defaultDeclarationBuilder().build(),
            DecisionReason.AUDIO_SOURCE_NOT_COPYABLE));
  }

  @Test
  @DisplayName(
      "Should name the viewer's bitrate limit for the audio when a lower audio step brings the copy"
          + " within the maximum")
  void
      shouldNameTheViewersBitrateLimitForTheAudioWhenALowerAudioStepBringsTheCopyWithinTheMaximum() {
    var source =
        Source.builder()
            .audioCodec("ac3")
            .audioChannels(6)
            .audioBitrate(640_000)
            .videoBitrate(4_000_000L)
            .build()
            .probe();
    var client =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build(),
                    audioEntryBuilder(AudioCodec.AAC).build()))
            .build();

    var decision =
        decide(
            request(source, client)
                .viewerLimits(ViewerLimits.builder().maxBitrate(OptionalLong.of(4_500_000)).build())
                .build());

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.reasons()).containsExactly(DecisionReason.AUDIO_LOWERED_FOR_BITRATE_LIMIT);
  }

  @Test
  @DisplayName("Should name the lower audio step when an encode's ladder takes one")
  void shouldNameTheLowerAudioStepWhenAnEncodesLadderTakesOne() {
    var decision =
        decide(
            Source.builder().audioCodec("flac").build().probe(),
            defaultDeclarationBuilder().build());
    var lowerStep = decision.audioDecision().toBuilder().bitrate(64_000).build();

    var stepped = decision.withAudioDecision(lowerStep);

    assertThat(stepped.reasons())
        .containsExactly(
            DecisionReason.AUDIO_CODEC_NOT_DECLARED,
            DecisionReason.AUDIO_LOWERED_FOR_BITRATE_LIMIT);
    assertThat(decision.withAudioDecision(decision.audioDecision()).reasons())
        .containsExactly(DecisionReason.AUDIO_CODEC_NOT_DECLARED);
  }

  @Test
  @DisplayName(
      "Should name the direct-play refusals before the copy's reasons when the file plays over HLS")
  void shouldNameTheDirectPlayRefusalsBeforeTheCopysReasonsWhenTheFilePlaysOverHls() {
    var source = Source.builder().audioCodec("flac").build().probe();
    var request =
        request(source, defaultDeclarationBuilder().build())
            .directPlayRefusals(List.of(DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED))
            .build();

    var decision = decide(request);

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.reasons())
        .containsExactly(
            DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED,
            DecisionReason.AUDIO_CODEC_NOT_DECLARED);
  }

  @Test
  @DisplayName("Should name a reason once when direct play and the copy both refuse for it")
  void shouldNameAReasonOnceWhenDirectPlayAndTheCopyBothRefuseForIt() {
    var interlaced =
        Source.builder()
            .frameRateRule(
                VideoPlaybackFactsFixture.interlacedRateRule(new Fraction(25, 1), FieldOrder.TT))
            .build()
            .probe();
    var request =
        request(interlaced, everyRangeClient())
            .directPlayRefusals(
                List.of(
                    DecisionReason.SOURCE_INTERLACED,
                    DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED))
            .build();

    var decision = decide(request);

    assertThat(decision.transcodeMode().copiesVideo()).isFalse();
    assertThat(decision.reasons())
        .containsExactly(
            DecisionReason.SOURCE_INTERLACED, DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED);
  }

  private TranscodeDecision decide(MediaProbe source, ClientCapabilityDeclaration client) {
    return decide(request(source, client).build());
  }

  private TranscodeDecision decide(TranscodeDecisionRequest request) {
    return service.decide(request).orElseThrow();
  }

  private static TranscodeDecisionRequest.TranscodeDecisionRequestBuilder request(
      MediaProbe source, ClientCapabilityDeclaration client) {
    return TranscodeDecisionRequest.builder()
        .source(source)
        .capabilityDeclaration(client)
        .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
        .verifiedVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
        .videoCopySupport(
            List.of(
                WorkerVideoCopySupport.builder()
                    .plannedOutput(true)
                    .bitstreamRewrites(Set.of(BitstreamRewrite.values()))
                    .build()));
  }

  // A 10-bit HEVC Main 10 source at level 4.0 under the hvc1 sample entry.
  private static Source.SourceBuilder hevcMain10() {
    return Source.builder()
        .codec("hevc")
        .codecString("hvc1.2.4.L120.90")
        .bitDepth(10)
        .videoProperties(Optional.of(properties(VideoCodecProfile.HEVC_MAIN_10)));
  }

  // The entries, and an AV1 entry for the encodes that they leave the client.
  private static ClientCapabilityDeclaration client(VideoCapabilityEntry... entries) {
    var video = new ArrayList<>(List.of(entries));
    video.add(av1EntryBuilder().build());
    return defaultDeclarationBuilder().video(video).build();
  }

  // HEVC and H.264 entries that declare every base, Dolby Vision 8.1 and HDR10+.
  private static ClientCapabilityDeclaration everyRangeClient() {
    var everyBase = List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG);
    return client(
        hevcEntryBuilder()
            .compatibleBases(everyBase)
            .codecProfiles(List.of(mainTier(VideoCodecProfile.HEVC_MAIN_10)))
            .dolbyVisionProfiles(List.of(profile81Capability()))
            .hdr10Plus(true)
            .build(),
        h264EntryBuilder().compatibleBases(everyBase).maxBitDepth(10).build());
  }

  private static DolbyVisionProfileCapability profile81Capability() {
    return DolbyVisionProfileCapability.builder()
        .profile(DolbyVisionProfile.PROFILE_8_1)
        .maxLevel(13)
        .build();
  }

  private static VideoCodecProfileCapability h264(VideoCodecProfile profile, int maxLevel) {
    return VideoCodecProfileCapability.builder().profile(profile).maxLevel(maxLevel).build();
  }

  private static VideoCodecProfileCapability mainTier(VideoCodecProfile profile) {
    return VideoCodecProfileCapability.builder()
        .profile(profile)
        .maxLevel(51)
        .maxTier(Optional.of(VideoCodecTier.MAIN))
        .build();
  }

  private static VideoStreamProperties properties(VideoCodecProfile profile) {
    return VideoStreamProperties.builder()
        .codecProfile(Optional.of(profile))
        .codecLevel(OptionalInt.of(40))
        .codecTier(Optional.of(VideoCodecTier.MAIN))
        .firstFramesRead(true)
        .build();
  }

  private static VideoDynamicRange range(CompatibleBase base) {
    return VideoPlaybackFactsFixture.rangeWithoutEnhancement(base);
  }

  private static VideoDynamicRange profile81() {
    return new VideoDynamicRange(
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
            Presence.PRESENT));
  }

  private static VideoDynamicRange rpusStartingTheirSamples() {
    var present = (DolbyVision.Present) profile81().dolbyVision();
    return new VideoDynamicRange(
        CompatibleBase.PQ,
        Presence.ABSENT,
        new DolbyVision.Present(
            present.declared(),
            Presence.PRESENT,
            Optional.empty(),
            DolbyVisionRpuPlacement.STARTS_SAMPLE));
  }

  /**
   * A 1080p 4:2:0 source at 24000/1001 fps, 8-bit H.264 High at level 4.0 unless it names others,
   * whose keyframes open every 6 s interval, beside stereo AAC LC at 128 kb/s.
   */
  @Builder
  record Source(
      String codec,
      String codecString,
      Integer bitDepth,
      boolean bitDepthUnreported,
      Optional<ChromaSubsampling> chromaSubsampling,
      VideoDynamicRange dynamicRange,
      Optional<VideoStreamProperties> videoProperties,
      FrameRateRule frameRateRule,
      KeyframeKnowledge keyframes,
      String audioCodec,
      String audioCodecString,
      Integer audioChannels,
      Integer audioBitrate,
      Long videoBitrate) {

    private OptionalInt reportedBitDepth() {
      if (bitDepthUnreported) {
        return OptionalInt.empty();
      }

      return OptionalInt.of(bitDepth == null ? 8 : bitDepth);
    }

    MediaProbe probe() {
      var videoCodec = codec == null ? "h264" : codec;
      var audio = audioCodec == null ? "aac" : audioCodec;
      var facts =
          VideoPlaybackFactsFixture.sdrH264FactsBuilder()
              .codecFamily(Optional.of(ProbedCodecFamily.fromProbedName(videoCodec)))
              .bitDepth(reportedBitDepth())
              .chromaSubsampling(
                  chromaSubsampling == null
                      ? Optional.of(ChromaSubsampling.CHROMA_420)
                      : chromaSubsampling)
              .codecString(Optional.of(codecString == null ? "avc1.640028" : codecString))
              .videoProperties(
                  videoProperties == null
                      ? Optional.of(VideoPlaybackFactsFixture.h264HighPropertiesBuilder().build())
                      : videoProperties);
      if (dynamicRange != null) {
        facts.dynamicRange(dynamicRange);
      }

      if (frameRateRule != null) {
        facts.frameRateRule(frameRateRule);
      }

      var probe =
          MediaProbe.builder()
              .duration(Duration.ofMinutes(10))
              .framerate(OptionalDouble.of(23.976))
              .width(1920)
              .height(1080)
              .videoCodec(videoCodec)
              .audioCodec(audio)
              .bitrate(5_000_000)
              .audioChannels(OptionalInt.of(audioChannels == null ? 2 : audioChannels))
              .audioBitrate(OptionalLong.of(audioBitrate == null ? 128_000 : audioBitrate))
              .keyframes(keyframes == null ? KEYFRAMES_EVERY_TWO_SECONDS : keyframes)
              .videoPlaybackFacts(Optional.of(facts.build()));
      var streams = new ArrayList<StreamInfo>();
      streams.add(
          ProbeFixture.audioStreamBuilder(audio)
              .index(2)
              .codecString(Optional.ofNullable(audioCodecString(audio)))
              .build());
      if (videoBitrate != null) {
        streams.add(
            ProbeFixture.videoStreamBuilder(probe.build())
                .bitrate(OptionalLong.of(videoBitrate))
                .build());
      }

      return probe.streams(streams).build();
    }

    private String audioCodecString(@NonNull String audio) {
      if (audioCodecString != null) {
        return audioCodecString;
      }

      return switch (audio) {
        case "aac" -> "mp4a.40.2";
        case "ac3" -> "ac-3";
        default -> null;
      };
    }
  }
}
