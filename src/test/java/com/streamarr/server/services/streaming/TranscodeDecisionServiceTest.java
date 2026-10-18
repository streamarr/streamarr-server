package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
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
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

  @ParameterizedTest(name = "{1}")
  @MethodSource("keyframesThatRuleOutAStreamCopy")
  @DisplayName("Should count a refused stream copy by why the keyframes rule it out")
  void shouldCountARefusedStreamCopyByWhyTheKeyframesRuleItOut(
      KeyframeKnowledge keyframes, String reason) {
    var source =
        probeBuilder("h264", "aac").duration(Duration.ofSeconds(20)).keyframes(keyframes).build();

    service.decide(source, options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

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

    var decision = service.decide(source, options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(meterRegistry.find(STREAM_COPY_REFUSALS).counters()).isEmpty();
  }

  @Test
  @DisplayName("Should count no refusal when the client cannot play the source's video codec")
  void shouldCountNoRefusalWhenTheClientCannotPlayTheSourcesVideoCodec() {
    var source = h264Probe(new KeyframeKnowledge.NotReported(), "aac");

    var decision = service.decide(source, options(List.of("av1")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(meterRegistry.find(STREAM_COPY_REFUSALS).counters()).isEmpty();
  }

  @Test
  @DisplayName("Should encode the video when a segment interval of the source holds no keyframe")
  void shouldEncodeTheVideoWhenASegmentIntervalOfTheSourceHoldsNoKeyframe() {
    var source = h264Probe(KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY, "aac");

    var decision = service.decide(source, options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
  }

  @Test
  @DisplayName(
      "Should encode both streams when the audio needs encoding and an interval holds no keyframe")
  void shouldEncodeBothStreamsWhenTheAudioNeedsEncodingAndAnIntervalHoldsNoKeyframe() {
    var source = h264Probe(KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY, "flac");

    var decision = service.decide(source, options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
  }

  @Test
  @DisplayName(
      "Should encode with the preferred codec when the source's keyframes rule out a stream copy")
  void shouldEncodeWithThePreferredCodecWhenTheSourcesKeyframesRuleOutAStreamCopy() {
    var source = h264Probe(KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY, "aac");

    var decision =
        service.decide(source, options(List.of("av1", "h264")), EVERY_VIDEO_CODEC_FAMILY);

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

    var decision = service.decide(source, options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

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

    var decision = service.decide(source, options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

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

    var decision = service.decide(source, options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

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

    var decision = service.decide(source, options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @ParameterizedTest
  @MethodSource("keyframesThatAreNotKnown")
  @DisplayName("Should encode the video when the source's keyframe positions are not known")
  void shouldEncodeTheVideoWhenTheSourcesKeyframePositionsAreNotKnown(KeyframeKnowledge keyframes) {
    var source = h264Probe(keyframes, "aac");

    var decision = service.decide(source, options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

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

    var decision = service.decide(source, options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

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

  private StreamingOptions options(List<String> supportedCodecs) {
    return StreamingOptions.builder()
        .supportedCodecs(supportedCodecs)
        .supportedAudioCodecs(StreamingOptions.DEFAULT_SUPPORTED_AUDIO_CODECS)
        .maxAudioChannels(StreamingOptions.DEFAULT_MAX_AUDIO_CHANNELS)
        .build();
  }

  private StreamingOptions options(
      List<String> supportedCodecs, List<String> audioCodecs, int maxAudioChannels) {
    return StreamingOptions.builder()
        .supportedCodecs(supportedCodecs)
        .supportedAudioCodecs(audioCodecs)
        .maxAudioChannels(maxAudioChannels)
        .build();
  }

  // --- Existing tests (adapted) ---

  @Test
  @DisplayName("Should remux when video codec matches and audio is AAC")
  void shouldRemuxWhenVideoCodecMatchesAndAudioIsAac() {
    var source = probe("h264", "aac");
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should audio transcode when video matches but audio needs conversion")
  void shouldAudioTranscodeWhenVideoMatchesButAudioNeedsConversion() {
    var source = probe("h264", "flac", 2, 0);
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should video transcode when video incompatible but audio is compatible")
  void shouldVideoTranscodeWhenVideoIncompatibleButAudioIsCompatible() {
    var source = probe("hevc", "aac");
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should prefer AV1 over H264 when client supports both")
  void shouldPreferAv1OverH264WhenClientSupportsBoth() {
    var source = probe("hevc", "aac");
    var clientOptions = options(List.of("av1", "h264"));

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @Test
  @DisplayName(
      "Should encode H.264 when the client prefers AV1 and no worker-verified encoder encodes AV1")
  void shouldEncodeH264WhenClientPrefersAv1AndNoWorkerVerifiedEncoderEncodesAv1() {
    var source = probe("hevc", "aac");
    var clientOptions = options(List.of("av1", "h264"));

    var decision = service.decide(source, clientOptions, Set.of("h264", "hevc"));

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName(
      "Should encode H.264 when the client declares only AV1 and no worker-verified encoder encodes any codec")
  void shouldEncodeH264WhenClientDeclaresOnlyAv1AndNoWorkerVerifiedEncoderEncodesAnyCodec() {
    var source = probe("hevc", "aac");
    var clientOptions = options(List.of("av1"));

    var decision = service.decide(source, clientOptions, Set.of());

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName(
      "Should copy an AV1 source when the client declares AV1 and no worker-verified encoder encodes AV1")
  void shouldCopyAv1SourceWhenClientDeclaresAv1AndNoWorkerVerifiedEncoderEncodesAv1() {
    var source = probe("av1", "aac");
    var clientOptions = options(List.of("av1", "h264"));

    var decision = service.decide(source, clientOptions, Set.of("h264"));

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @Test
  @DisplayName("Should remux AV1 source when client supports AV1 and audio is AAC")
  void shouldRemuxAv1SourceWhenClientSupportsAv1AndAudioIsAac() {
    var source = probe("av1", "aac");
    var clientOptions = options(List.of("av1", "h264"));

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @Test
  @DisplayName("Should audio transcode AV1 source when audio is not AAC")
  void shouldAudioTranscodeAv1SourceWhenAudioIsNotAac() {
    var source = probe("av1", "dts", 6, 1_500_000L);
    var clientOptions = options(List.of("av1"));

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should fallback to default codec when client supports neither preference")
  void shouldFallbackToDefaultCodecWhenClientSupportsNeitherPreference() {
    var source = probe("hevc", "aac");
    var clientOptions = options(List.of("vp9"));

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
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
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

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
    var clientOptions = options(List.of("h264"), List.of("aac", "ac3"), 6);

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should copy E-AC-3 audio when client supports it")
  void shouldCopyEac3AudioWhenClientSupportsIt() {
    var source = probe("h264", "eac3", 6, 640_000L);
    var clientOptions = options(List.of("h264"), List.of("aac", "eac3"), 8);

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("eac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName(
      "Should fall back to stereo AAC when source codec is unsupported by default capabilities")
  void shouldFallbackToStereoAacWhenSourceCodecIsUnsupportedByDefaultCapabilities() {
    var source = probe("h264", "flac", 2, 0);
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should transcode to AC-3 when channel cap forces transcode")
  void shouldTranscodeToAc3WhenChannelCapForcesTranscode() {
    var source = probe("h264", "ac3", 8, 640_000L);
    var clientOptions = options(List.of("h264"), List.of("ac3"), 6);

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should copy FLAC audio when remuxing H264 and the client supports FLAC")
  void shouldCopyFlacAudioWhenRemuxingH264AndClientSupportsFlac() {
    var source = probe("h264", "flac", 6, 0);
    var clientOptions = options(List.of("h264"), List.of("aac", "flac"), 6);

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("flac");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should transcode MP3 audio to AAC when the client supports MP3")
  void shouldTranscodeMp3AudioToAacWhenClientSupportsMp3() {
    var source = probe("h264", "mp3", 2, 128_000L);
    var clientOptions = options(List.of("h264"), List.of("aac", "mp3"), 2);

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName(
      "Should video transcode with audio copy when video incompatible but audio compatible")
  void shouldVideoTranscodeWithAudioCopyWhenVideoIncompatibleButAudioCompatible() {
    var source = probe("hevc", "ac3", 6, 384_000L);
    var clientOptions = options(List.of("h264"), List.of("ac3"), 6);

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should full transcode when both video and audio need transcoding")
  void shouldFullTranscodeWhenBothVideoAndAudioNeedTranscoding() {
    var source = probe("hevc", "flac", 6, 0);
    var clientOptions = options(List.of("h264"), List.of("aac"), 2);

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should copy multichannel AAC when remuxing H264")
  void shouldCopyMultichannelAacWhenRemuxingH264() {
    var source = probe("h264", "aac", 6, 384_000L);
    var clientOptions = options(List.of("h264"), List.of("aac"), 6);

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should transcode to E-AC-3 when surround and client supports E-AC-3")
  void shouldTranscodeToEac3WhenSurroundAndEac3Supported() {
    var source = probe("h264", "dts", 8, 640_000L);
    var clientOptions = options(List.of("h264"), List.of("eac3", "aac"), 8);

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("eac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(8);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(512_000L);
  }

  @Test
  @DisplayName("Should transcode to multichannel AAC when only AAC is supported")
  void shouldTranscodeToMultichannelAacWhenOnlyAacIsSupported() {
    var source = probe("h264", "dts", 6, 1_500_000L);
    var clientOptions = options(List.of("h264"), List.of("aac"), 6);

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should copy stereo AAC passthrough")
  void shouldCopyStereoAacPassthrough() {
    var source = probe("h264", "aac", 2, 128_000L);
    var clientOptions = options(List.of("h264"), List.of("aac"), 2);

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  // --- Null-safe defaults ---

  @Test
  @DisplayName("Should use default audio codecs when client sends null")
  void shouldUseDefaultAudioCodecsWhenClientSendsNull() {
    var source = probe("h264", "aac");
    var clientOptions =
        StreamingOptions.builder()
            .supportedCodecs(List.of("h264"))
            .supportedAudioCodecs(null)
            .maxAudioChannels(null)
            .build();

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should default to stereo when source audio channels are empty")
  void shouldDefaultToStereoWhenSourceAudioChannelsAreEmpty() {
    var source =
        probeBuilder("h264", "aac")
            .audioChannels(OptionalInt.empty())
            .audioBitrate(OptionalLong.empty())
            .build();
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions, EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(128_000L);
  }
}
