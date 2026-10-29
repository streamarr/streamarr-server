package com.streamarr.server.services.streaming;

import static com.streamarr.server.domain.streaming.AudioCodec.AAC;
import static com.streamarr.server.domain.streaming.AudioCodec.AC3;
import static com.streamarr.server.domain.streaming.AudioCodec.EAC3;
import static com.streamarr.server.domain.streaming.AudioCodec.FLAC;
import static com.streamarr.server.domain.streaming.AudioCodec.OPUS;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.AV1;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.H264;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.HEVC;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioCapabilityEntry;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Transcode Decision Service Tests")
class TranscodeDecisionServiceTest {

  private static final KeyframeKnowledge KEYFRAMES_EVERY_TWO_SECONDS =
      ProbeFixture.keyframesEvery(Duration.ofSeconds(2), Duration.ofMinutes(120));

  // At the 6 s period, keyframes 10.01 s apart leave the interval [12 s, 18 s) without one.
  private static final KeyframeKnowledge KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY =
      ProbeFixture.keyframesEvery(Duration.ofMillis(10_010), Duration.ofMinutes(120));

  private static final Set<String> EVERY_VIDEO_CODEC_FAMILY = Set.of("h264", "hevc", "av1");

  private static final String STREAM_COPY_REFUSALS = "streamarr.streaming.stream_copy_refusals";

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          meterRegistry);

  @Test
  @DisplayName("Should encode a copyable video when the format attempt requires encoding")
  void shouldEncodeACopyableVideoWhenTheFormatAttemptRequiresEncoding() {
    var source = probe("h264", "aac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(Set.of("h264"))
                    .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.videoSource()).isEqualTo(source.videoPlaybackFacts());
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
  }

  @Test
  @DisplayName(
      "Should return no decision when copy-only video has no usable copy segment boundaries")
  void shouldReturnNoDecisionWhenCopyOnlyVideoHasNoUsableCopySegmentBoundaries() {
    var source = h264Probe(KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY, "aac");

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaring(H264))
                .encodableVideoCodecFamilies(Set.of("h264"))
                .videoFormatConstraint(VideoFormatConstraint.COPY_ONLY)
                .build());

    assertThat(decision).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoFormatConstraint.class,
      names = {"COPY_OR_ENCODE", "COPY_ONLY"})
  @DisplayName("Should copy video and negotiate audio when no video encoder is available")
  void shouldCopyVideoAndNegotiateAudioWhenNoVideoEncoderIsAvailable(
      VideoFormatConstraint constraint) {
    var source = probe("h264", "flac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(Set.of())
                    .excludedEncodeCodecFamilies(Set.of("h264"))
                    .videoFormatConstraint(constraint)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.videoSource()).isEqualTo(source.videoPlaybackFacts());
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should refuse a decision request when it carries no capability declaration")
  void shouldRefuseADecisionRequestWhenItCarriesNoCapabilityDeclaration() {
    var request =
        TranscodeDecisionRequest.builder()
            .source(probe("h264", "aac"))
            .encodableVideoCodecFamilies(Set.of("h264"));

    assertThatThrownBy(request::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("capabilityDeclaration");
  }

  @ParameterizedTest
  @CsvSource({"false,h264", "true,"})
  @DisplayName("Should choose only untried encodes when previous codec families are excluded")
  void shouldChooseOnlyUntriedEncodesWhenPreviousCodecFamiliesAreExcluded(
      boolean excludeH264, String expectedFamily) {
    var excludedFamilies = new HashSet<>(Set.of("av1"));
    if (excludeH264) {
      excludedFamilies.add("h264");
    }

    var request =
        TranscodeDecisionRequest.builder()
            .source(probe("hevc", "aac"))
            .capabilityDeclaration(declaring(AV1, H264))
            .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
            .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
            .excludedEncodeCodecFamilies(excludedFamilies)
            .build();
    excludedFamilies.clear();

    var decision = service.decide(request);

    assertThat(decision.map(TranscodeDecision::videoCodecFamily))
        .isEqualTo(Optional.ofNullable(expectedFamily));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("keyframesThatRuleOutAStreamCopy")
  @DisplayName("Should count a refused stream copy by why the keyframes rule it out")
  void shouldCountARefusedStreamCopyByWhyTheKeyframesRuleItOut(
      KeyframeKnowledge keyframes, String reason) {
    var source =
        probeBuilder("h264", "aac").duration(Duration.ofSeconds(20)).keyframes(keyframes).build();

    service
        .decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaring(H264))
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build())
        .orElseThrow();

    assertThat(meterRegistry.get(STREAM_COPY_REFUSALS).counters())
        .singleElement()
        .satisfies(
            counter -> {
              assertThat(counter.getId().getTag("reason")).isEqualTo(reason);
              assertThat(counter.count()).isEqualTo(1.0);
            });
  }

  // Each source lasts 20 s, so the playlist advertises segments at 0, 6, 12 and 18 s.
  static Stream<Arguments> keyframesThatRuleOutAStreamCopy() {
    return Stream.of(
        Arguments.of(new KeyframeKnowledge.NotReported(), "keyframes_not_reported"),
        Arguments.of(
            new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX),
            "packet_scan_required"),
        Arguments.of(
            new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.NO_KEYFRAMES),
            "keyframes_unavailable"),
        Arguments.of(millisecondKeyframes(0, 13_000, 18_000), "interval_without_keyframe"),
        Arguments.of(millisecondKeyframes(0, 6_000), "interval_without_keyframe"),
        Arguments.of(
            millisecondKeyframes(0, 7_000, 6_500, 12_000, 18_000), "presentation_time_regressed"),
        Arguments.of(
            new KeyframeKnowledge.Known(
                KeyframePositions.builder()
                    .streamIndex(0)
                    .timeBaseNumerator(1001)
                    .timeBaseDenominator(24_000)
                    .source(KeyframePositionSource.PACKET_SCAN)
                    .keyframes(List.of(new KeyframePosition(Long.MAX_VALUE / 1000, 0)))
                    .build()),
            "presentation_time_out_of_range"),
        Arguments.of(
            new KeyframeKnowledge.Known(
                KeyframePositions.builder()
                    .streamIndex(0)
                    .timeBaseNumerator(1001)
                    .timeBaseDenominator(24_000)
                    .source(KeyframePositionSource.PACKET_SCAN)
                    .keyframes(List.of(new KeyframePosition(0, Long.MIN_VALUE / 1000)))
                    .build()),
            "decode_time_out_of_range"));
  }

  @Test
  @DisplayName("Should count no refusal when the keyframes allow a stream copy")
  void shouldCountNoRefusalWhenTheKeyframesAllowAStreamCopy() {
    var source =
        probeBuilder("h264", "aac")
            .duration(Duration.ofSeconds(20))
            .keyframes(millisecondKeyframes(0, 6_000, 12_000, 18_000))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(meterRegistry.find(STREAM_COPY_REFUSALS).counters()).isEmpty();
  }

  @Test
  @DisplayName("Should count no refusal when the client cannot play the source's video codec")
  void shouldCountNoRefusalWhenTheClientCannotPlayTheSourcesVideoCodec() {
    var source = h264Probe(new KeyframeKnowledge.NotReported(), "aac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(AV1))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(meterRegistry.find(STREAM_COPY_REFUSALS).counters()).isEmpty();
  }

  @Test
  @DisplayName("Should encode the video when a segment interval of the source holds no keyframe")
  void shouldEncodeTheVideoWhenASegmentIntervalOfTheSourceHoldsNoKeyframe() {
    var source = h264Probe(KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY, "aac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
  }

  @Test
  @DisplayName(
      "Should encode both streams when the audio needs encoding and an interval holds no keyframe")
  void shouldEncodeBothStreamsWhenTheAudioNeedsEncodingAndAnIntervalHoldsNoKeyframe() {
    var source = h264Probe(KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY, "flac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
  }

  @Test
  @DisplayName(
      "Should encode with the preferred codec when the source's keyframes rule out a stream copy")
  void shouldEncodeWithThePreferredCodecWhenTheSourcesKeyframesRuleOutAStreamCopy() {
    var source = h264Probe(KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY, "aac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(AV1, H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @Test
  @DisplayName("Should copy the video when no keyframe opens the final partial segment")
  void shouldCopyTheVideoWhenNoKeyframeOpensTheFinalPartialSegment() {
    // The grid interval [18 s, 18.5 s) holds no keyframe. It joins the copy segment that the
    // keyframe at 12 s opens, which runs to 18.5 s.
    var source =
        probeBuilder("h264", "aac")
            .duration(Duration.ofMillis(18_500))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(6), Duration.ofSeconds(12)))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(meterRegistry.find(STREAM_COPY_REFUSALS).counters()).isEmpty();
  }

  @Test
  @DisplayName("Should encode the video when the two final grid intervals hold no keyframe")
  void shouldEncodeTheVideoWhenTheTwoFinalGridIntervalsHoldNoKeyframe() {
    // Over 20 s, neither [12 s, 18 s) nor [18 s, 20 s) holds a keyframe. A copy segment opened at
    // 6 s would run 14 s, more than twice the 6 s period.
    var source =
        probeBuilder("h264", "aac")
            .duration(Duration.ofSeconds(20))
            .keyframes(millisecondKeyframes(0, 6_000))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  @Test
  @DisplayName("Should encode the video when no keyframe presents before the media end")
  void shouldEncodeTheVideoWhenNoKeyframePresentsBeforeTheMediaEnd() {
    var source =
        probeBuilder("h264", "aac")
            .duration(Duration.ofMillis(400))
            .keyframes(millisecondKeyframes(500))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(
            meterRegistry
                .get(STREAM_COPY_REFUSALS)
                .tag("reason", "interval_without_keyframe")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should copy the video when a keyframe opens the final partial segment")
  void shouldCopyTheVideoWhenAKeyframeOpensTheFinalPartialSegment() {
    var source =
        probeBuilder("h264", "aac")
            .duration(Duration.ofMillis(18_500))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(6), Duration.ofSeconds(18)))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @ParameterizedTest
  @MethodSource("keyframesThatAreNotKnown")
  @DisplayName("Should encode the video when the source's keyframe positions are not known")
  void shouldEncodeTheVideoWhenTheSourcesKeyframePositionsAreNotKnown(KeyframeKnowledge keyframes) {
    var source = h264Probe(keyframes, "aac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  static Stream<KeyframeKnowledge> keyframesThatAreNotKnown() {
    return ProbeFixture.keyframesThatAreNotKnown();
  }

  @Test
  @DisplayName("Should copy the video when every segment interval holds a keyframe")
  void shouldCopyTheVideoWhenEverySegmentIntervalHoldsAKeyframe() {
    var source =
        h264Probe(
            ProbeFixture.keyframesEvery(Duration.ofMillis(5_005), Duration.ofMinutes(120)), "aac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  // Positions in a millisecond time base, in decode order, each decoded 1 ms after the one before.
  private static KeyframeKnowledge millisecondKeyframes(long... presentationTimes) {
    var keyframes = new ArrayList<KeyframePosition>();
    for (var index = 0; index < presentationTimes.length; index++) {
      keyframes.add(new KeyframePosition(presentationTimes[index], index));
    }

    return new KeyframeKnowledge.Known(
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(1_000)
            .source(KeyframePositionSource.PACKET_SCAN)
            .keyframes(keyframes)
            .build());
  }

  private MediaProbe h264Probe(KeyframeKnowledge keyframes, String audioCodec) {
    return probeBuilder("h264", audioCodec).keyframes(keyframes).build();
  }

  private MediaProbe probe(String videoCodec, String audioCodec) {
    return probeBuilder(videoCodec, audioCodec).build();
  }

  private MediaProbe probe(
      String videoCodec, String audioCodec, int audioChannels, long audioBitrate) {
    return probeBuilder(videoCodec, audioCodec)
        .audioChannels(OptionalInt.of(audioChannels))
        .audioBitrate(OptionalLong.of(audioBitrate))
        .build();
  }

  private MediaProbe.MediaProbeBuilder probeBuilder(String videoCodec, String audioCodec) {
    return MediaProbe.builder()
        .keyframes(KEYFRAMES_EVERY_TWO_SECONDS)
        .duration(Duration.ofMinutes(120))
        .framerate(OptionalDouble.of(23.976))
        .width(1920)
        .height(1080)
        .videoCodec(videoCodec)
        .audioCodec(audioCodec)
        .streams(
            audioCodec == null
                ? List.of()
                : List.of(ProbeFixture.audioStreamBuilder(audioCodec).index(1).build()))
        .bitrate(5_000_000L)
        .audioChannels(OptionalInt.of(2))
        .audioBitrate(OptionalLong.of(128_000L))
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .codecString(Optional.ofNullable(SDR_CODEC_STRINGS.get(videoCodec)))
                    .build()));
  }

  // The codec string that the probe stores for an 8-bit SDR source of each codec.
  private static final Map<String, String> SDR_CODEC_STRINGS =
      Map.of("h264", "avc1.640028", "hevc", "hvc1.1.6.L120.90", "av1", "av01.0.08M.08");

  private static ClientCapabilityDeclaration declaring(ProbedCodecFamily... codecFamilies) {
    return declarationBuilderFor(codecFamilies).build();
  }

  private static ClientCapabilityDeclaration declaringAudio(AudioCapabilityEntry... entries) {
    return defaultDeclarationBuilder().audio(List.of(entries)).build();
  }

  private static AudioCapabilityEntry audio(AudioCodec codec, int maxChannels) {
    return audioEntryBuilder(codec).maxChannels(maxChannels).build();
  }

  // --- Existing tests (adapted) ---

  @Test
  @DisplayName("Should remux when video codec matches and audio is AAC")
  void shouldRemuxWhenVideoCodecMatchesAndAudioIsAac() {
    var source = probe("h264", "aac");
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should audio transcode when video matches but audio needs conversion")
  void shouldAudioTranscodeWhenVideoMatchesButAudioNeedsConversion() {
    var source = probe("h264", "flac", 2, 0);
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should video transcode when video incompatible but audio is compatible")
  void shouldVideoTranscodeWhenVideoIncompatibleButAudioIsCompatible() {
    var source = probe("hevc", "aac");
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should prefer AV1 over H264 when client supports both")
  void shouldPreferAv1OverH264WhenClientSupportsBoth() {
    var source = probe("hevc", "aac");
    var declaration = declaring(AV1, H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @Test
  @DisplayName(
      "Should encode H.264 when the client prefers AV1 and no worker-verified encoder encodes AV1")
  void shouldEncodeH264WhenClientPrefersAv1AndNoWorkerVerifiedEncoderEncodesAv1() {
    var source = probe("hevc", "aac");
    var declaration = declaring(AV1, H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(Set.of("h264", "hevc"))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName(
      "Should return no decision when no worker-verified encoder supports the client codecs")
  void shouldReturnNoDecisionWhenNoWorkerVerifiedEncoderSupportsTheClientCodecs() {
    var source = probe("hevc", "aac");
    var declaration = declaring(AV1);

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(Set.of())
                .build());

    assertThat(decision).isEmpty();
  }

  @Test
  @DisplayName(
      "Should copy an AV1 source when the client declares AV1 and no worker-verified encoder encodes AV1")
  void shouldCopyAv1SourceWhenClientDeclaresAv1AndNoWorkerVerifiedEncoderEncodesAv1() {
    var source = probe("av1", "aac");
    var declaration = declaring(AV1, H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(Set.of("h264"))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @Test
  @DisplayName("Should remux AV1 source when client supports AV1 and audio is AAC")
  void shouldRemuxAv1SourceWhenClientSupportsAv1AndAudioIsAac() {
    var source = probe("av1", "aac");
    var declaration = declaring(AV1, H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @Test
  @DisplayName("Should audio transcode AV1 source when audio is not AAC")
  void shouldAudioTranscodeAv1SourceWhenAudioIsNotAac() {
    var source = probe("av1", "dts", 6, 1_500_000L);
    var declaration = declaring(AV1);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should return no decision when the client supports no eligible encoding codec")
  void shouldReturnNoDecisionWhenTheClientSupportsNoEligibleEncodingCodec() {
    var source = probe("h264", "aac");
    var declaration = declaring(HEVC);

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build());

    assertThat(decision).isEmpty();
  }

  // --- Video-only (no audio stream) ---

  @Test
  @DisplayName("Should decide audio mode none when source has no audio stream")
  void shouldDecideAudioModeNoneWhenSourceHasNoAudioStream() {
    var source =
        probeBuilder("h264", null)
            .audioChannels(OptionalInt.empty())
            .audioBitrate(OptionalLong.empty())
            .build();
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.NONE);
    assertThat(decision.audioDecision().codec()).isNull();
    assertThat(decision.audioDecision().channels()).isZero();
  }

  // --- Surround sound test scenarios ---

  @Test
  @DisplayName("Should copy AC-3 audio when client supports it")
  void shouldCopyAc3AudioWhenClientSupportsIt() {
    var source = probe("h264", "ac3", 6, 384_000L);
    var declaration = declaringAudio(audio(AAC, 6), audio(AC3, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should copy E-AC-3 audio when client supports it")
  void shouldCopyEac3AudioWhenClientSupportsIt() {
    var source = probe("h264", "eac3", 6, 640_000L);
    var declaration = declaringAudio(audio(AAC, 8), audio(EAC3, 8));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("eac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should fall back to stereo AAC when the client declares only AAC audio")
  void shouldFallBackToStereoAacWhenTheClientDeclaresOnlyAacAudio() {
    var source = probe("h264", "flac", 2, 0);
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should transcode to AC-3 when channel cap forces transcode")
  void shouldTranscodeToAc3WhenChannelCapForcesTranscode() {
    var source = probe("h264", "ac3", 8, 640_000L);
    var declaration = declaringAudio(audio(AAC, 2), audio(AC3, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should copy FLAC audio when remuxing H264 and the client supports FLAC")
  void shouldCopyFlacAudioWhenRemuxingH264AndClientSupportsFlac() {
    var source = probe("h264", "flac", 6, 0);
    var declaration = declaringAudio(audio(AAC, 6), audio(FLAC, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("flac");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should transcode MP3 audio to AAC when the client declares AAC")
  void shouldTranscodeMp3AudioToAacWhenTheClientDeclaresAac() {
    var source = probe("h264", "mp3", 2, 128_000L);
    var declaration = declaringAudio(audio(AAC, 2));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName(
      "Should video transcode with audio copy when video incompatible but audio compatible")
  void shouldVideoTranscodeWithAudioCopyWhenVideoIncompatibleButAudioCompatible() {
    var source = probe("hevc", "ac3", 6, 384_000L);
    var declaration = declaringAudio(audio(AAC, 2), audio(AC3, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should full transcode when both video and audio need transcoding")
  void shouldFullTranscodeWhenBothVideoAndAudioNeedTranscoding() {
    var source = probe("hevc", "flac", 6, 0);
    var declaration = declaringAudio(audio(AAC, 2));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should copy multichannel AAC when remuxing H264")
  void shouldCopyMultichannelAacWhenRemuxingH264() {
    var source = probe("h264", "aac", 6, 384_000L);
    var declaration = declaringAudio(audio(AAC, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should transcode to 5.1 E-AC-3 when the surround source has more channels")
  void shouldTranscodeTo51Eac3WhenTheSurroundSourceHasMoreChannels() {
    var source = probe("h264", "dts", 8, 640_000L);
    var declaration = declaringAudio(audio(EAC3, 8), audio(AAC, 8));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("eac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(384_000L);
  }

  @Test
  @DisplayName("Should transcode to multichannel AAC when only AAC is supported")
  void shouldTranscodeToMultichannelAacWhenOnlyAacIsSupported() {
    var source = probe("h264", "dts", 6, 1_500_000L);
    var declaration = declaringAudio(audio(AAC, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @ParameterizedTest(name = "{0} source channels")
  @ValueSource(ints = {3, 4})
  @DisplayName("Should encode stereo AAC when the source has more channels than the client plays")
  void shouldEncodeStereoAacWhenTheSourceHasMoreChannelsThanTheClientPlays(int sourceChannels) {
    var source = probe("h264", "aac", sourceChannels, 256_000L);
    var declaration = declaringAudio(audio(AAC, 2));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @ParameterizedTest(name = "{0} source channels")
  @ValueSource(ints = {3, 4, 5, 7})
  @DisplayName("Should announce the source's channel count when the client plays every channel")
  void shouldAnnounceTheSourcesChannelCountWhenTheClientPlaysEveryChannel(int sourceChannels) {
    var source = probe("h264", "aac", sourceChannels, 256_000L);
    var declaration = declaringAudio(audio(AAC, 8));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().channels()).isEqualTo(sourceChannels);
  }

  @Test
  @DisplayName("Should copy stereo AAC passthrough")
  void shouldCopyStereoAacPassthrough() {
    var source = probe("h264", "aac", 2, 128_000L);
    var declaration = declaringAudio(audio(AAC, 2));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  // --- Capability entries ---

  @Test
  @DisplayName(
      "Should encode the video when the only entry for the source's codec family does not apply to HLS")
  void shouldEncodeTheVideoWhenTheOnlyEntryForTheSourcesCodecFamilyDoesNotApplyToHls() {
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().hls(false).build(), av1EntryBuilder().build()))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe("h264", "aac"))
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @Test
  @DisplayName("Should encode the video when the probe named no video codec")
  void shouldEncodeTheVideoWhenTheProbeNamedNoVideoCodec() {
    var source = probeBuilder("h264", "aac").videoCodec(null).build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName("Should encode with no codec family when its entry does not apply to HLS")
  void shouldEncodeWithNoCodecFamilyWhenItsEntryDoesNotApplyToHls() {
    var declaration =
        defaultDeclarationBuilder().video(List.of(h264EntryBuilder().hls(false).build())).build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(probe("hevc", "aac"))
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build());

    assertThat(decision).isEmpty();
  }

  // Auto and a fixed quality may copy or encode. Original only copies.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesAgainstTwoEntries")
  @DisplayName(
      "Should copy the video only when one entry accepts every property of the copy, never two"
          + " entries together")
  void shouldCopyTheVideoOnlyWhenOneEntryAcceptsEveryPropertyOfTheCopyNeverTwoEntriesTogether(
      String scenario,
      VideoFormatConstraint constraint,
      MediaProbe source,
      Optional<TranscodeMode> expected) {
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().maxWidth(3840).maxHeight(2160).maxFrameRate(30).build(),
                    h264EntryBuilder().maxWidth(1920).maxHeight(1080).maxFrameRate(60).build()))
            .build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .videoFormatConstraint(constraint)
                .build());

    assertThat(decision.map(TranscodeDecision::transcodeMode)).isEqualTo(expected);
  }

  static Stream<Arguments> copiesAgainstTwoEntries() {
    var uhdAt30 = probeVersion5H264(3840, 2160, 30);
    var hdAt60 = probeVersion5H264(1920, 1080, 60);
    var uhdAt60 = probeVersion5H264(3840, 2160, 60);
    var copy = Optional.of(TranscodeMode.REMUX);
    var encode = Optional.of(TranscodeMode.VIDEO_TRANSCODE);
    return Stream.of(
        Arguments.of("Auto, 2160p30", VideoFormatConstraint.COPY_OR_ENCODE, uhdAt30, copy),
        Arguments.of("Auto, 1080p60", VideoFormatConstraint.COPY_OR_ENCODE, hdAt60, copy),
        Arguments.of("Auto, 2160p60", VideoFormatConstraint.COPY_OR_ENCODE, uhdAt60, encode),
        Arguments.of("Original, 2160p30", VideoFormatConstraint.COPY_ONLY, uhdAt30, copy),
        Arguments.of(
            "Original, 2160p60", VideoFormatConstraint.COPY_ONLY, uhdAt60, Optional.empty()));
  }

  @Test
  @DisplayName(
      "Should copy a probe version 4 outcome when an HLS entry names its codec family whatever its"
          + " maximums")
  void shouldCopyAProbeVersion4OutcomeWhenAnHlsEntryNamesItsCodecFamilyWhateverItsMaximums() {
    var version4 = probeBuilder("h264", "aac").width(7680).height(4320).build();
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(1280).maxHeight(720).build()))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(version4)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName(
      "Should encode a probe version 5 outcome when its picture exceeds the entry's maximum size")
  void shouldEncodeAProbeVersion5OutcomeWhenItsPictureExceedsTheEntrysMaximumSize() {
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(1280).maxHeight(720).build()))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probeVersion5H264(1920, 1080, 24))
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  // An 8-bit 4:2:0 SDR H.264 High profile source at level 5.1 that probe version 5 described.
  private static MediaProbe probeVersion5H264(int width, int height, int frameRate) {
    return MediaProbe.builder()
        .keyframes(KEYFRAMES_EVERY_TWO_SECONDS)
        .duration(Duration.ofMinutes(120))
        .framerate(OptionalDouble.of(frameRate))
        .width(width)
        .height(height)
        .videoCodec("h264")
        .audioCodec("aac")
        .streams(List.of(ProbeFixture.audioStreamBuilder("aac").index(1).build()))
        .bitrate(5_000_000L)
        .audioChannels(OptionalInt.of(2))
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .frameRateRule(
                        VideoPlaybackFactsFixture.declaredRateRule(new Fraction(frameRate, 1)))
                    .videoProperties(
                        Optional.of(
                            VideoPlaybackFactsFixture.h264HighPropertiesBuilder()
                                .codecLevel(OptionalInt.of(51))
                                .build()))
                    .build()))
        .build();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("entriesWithoutTheEncodeProfile")
  @DisplayName(
      "Should skip an encode codec when its entry does not list the encode's codec profile")
  void shouldSkipAnEncodeCodecWhenItsEntryDoesNotListTheEncodesCodecProfile(
      String skippedFamily, VideoCapabilityEntry withoutEncodeProfile, String expectedFamily) {
    var otherEntry =
        "av1".equals(skippedFamily) ? h264EntryBuilder().build() : av1EntryBuilder().build();
    var declaration =
        defaultDeclarationBuilder().video(List.of(withoutEncodeProfile, otherEntry)).build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe("hevc", "aac"))
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.videoCodecFamily()).isEqualTo(expectedFamily);
  }

  static Stream<Arguments> entriesWithoutTheEncodeProfile() {
    return Stream.of(
        Arguments.of(
            "av1",
            av1EntryBuilder()
                .codecProfiles(
                    List.of(
                        VideoCodecProfileCapability.builder()
                            .profile(VideoCodecProfile.AV1_HIGH)
                            .maxLevel(51)
                            .maxTier(Optional.of(VideoCodecTier.MAIN))
                            .build()))
                .build(),
            "h264"),
        Arguments.of(
            "h264",
            h264EntryBuilder()
                .codecProfiles(
                    List.of(
                        VideoCodecProfileCapability.builder()
                            .profile(VideoCodecProfile.H264_MAIN)
                            .maxLevel(52)
                            .build()))
                .build(),
            "av1"));
  }

  @Test
  @DisplayName("Should encode a 5.1 Opus track when the client declares Opus at two channels")
  void shouldEncodeA51OpusTrackWhenTheClientDeclaresOpusAtTwoChannels() {
    var source = probe("h264", "opus", 6, 384_000L);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaringAudio(audio(AAC, 2), audio(OPUS, 2)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should encode the audio when its codec's only entry does not apply to HLS")
  void shouldEncodeTheAudioWhenItsCodecsOnlyEntryDoesNotApplyToHls() {
    var source = probe("h264", "ac3", 6, 384_000L);
    var declaration =
        declaringAudio(audio(AAC, 2), audioEntryBuilder(AC3).maxChannels(6).hls(false).build());

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should copy the audio when any of several entries for its codec takes every channel")
  void shouldCopyTheAudioWhenAnyOfSeveralEntriesForItsCodecTakesEveryChannel() {
    var source = probe("h264", "eac3", 6, 640_000L);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(
                        declaringAudio(audio(AAC, 2), audio(EAC3, 6), audio(EAC3, 2)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName(
      "Should encode multichannel audio with the next codec when the preferred codec's entry is stereo")
  void shouldEncodeMultichannelAudioWithTheNextCodecWhenThePreferredCodecsEntryIsStereo() {
    var source = probe("h264", "dts", 8, 1_500_000L);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(
                        declaringAudio(audio(AAC, 2), audio(EAC3, 2), audio(AC3, 6)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should encode mono AAC when the AAC entry accepts one channel")
  void shouldEncodeMonoAacWhenTheAacEntryAcceptsOneChannel() {
    var source = probe("h264", "dts", 6, 1_500_000L);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaringAudio(audio(AAC, 1)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should default to stereo when source audio channels are empty")
  void shouldDefaultToStereoWhenSourceAudioChannelsAreEmpty() {
    var source =
        probeBuilder("h264", "aac")
            .audioChannels(OptionalInt.empty())
            .audioBitrate(OptionalLong.empty())
            .build();
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(128_000L);
  }
}
