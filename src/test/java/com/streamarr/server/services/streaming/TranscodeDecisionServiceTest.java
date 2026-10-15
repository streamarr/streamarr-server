package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.fixtures.ProbeFixture;
import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Transcode Decision Service Tests")
class TranscodeDecisionServiceTest {

  private static final KeyframeKnowledge KEYFRAMES_EVERY_TWO_SECONDS =
      ProbeFixture.keyframesEvery(Duration.ofSeconds(2), Duration.ofMinutes(120));

  // At the 6 s period, keyframes 10.01 s apart leave the interval [12 s, 18 s) without one.
  private static final KeyframeKnowledge KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY =
      ProbeFixture.keyframesEvery(Duration.ofMillis(10_010), Duration.ofMinutes(120));

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build());

  @Test
  @DisplayName("Should encode the video when a segment interval of the source holds no keyframe")
  void shouldEncodeTheVideoWhenASegmentIntervalOfTheSourceHoldsNoKeyframe() {
    var source = h264Probe(KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY, "aac");

    var decision = service.decide(source, options(List.of("h264")));

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
  }

  @Test
  @DisplayName(
      "Should encode both streams when the audio needs encoding and an interval holds no keyframe")
  void shouldEncodeBothStreamsWhenTheAudioNeedsEncodingAndAnIntervalHoldsNoKeyframe() {
    var source = h264Probe(KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY, "flac");

    var decision = service.decide(source, options(List.of("h264")));

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
  }

  @Test
  @DisplayName(
      "Should encode with the preferred codec when the source's keyframes rule out a stream copy")
  void shouldEncodeWithThePreferredCodecWhenTheSourcesKeyframesRuleOutAStreamCopy() {
    var source = h264Probe(KEYFRAMES_LEAVING_AN_INTERVAL_EMPTY, "aac");

    var decision = service.decide(source, options(List.of("av1", "h264")));

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @ParameterizedTest
  @MethodSource("keyframesThatAreNotKnown")
  @DisplayName("Should encode the video when the source's keyframe positions are not known")
  void shouldEncodeTheVideoWhenTheSourcesKeyframePositionsAreNotKnown(KeyframeKnowledge keyframes) {
    var source = h264Probe(keyframes, "aac");

    var decision = service.decide(source, options(List.of("h264")));

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  static Stream<KeyframeKnowledge> keyframesThatAreNotKnown() {
    return Stream.of(
        new KeyframeKnowledge.NotReported(),
        new KeyframeKnowledge.PacketScanRequired(
            KeyframePacketScanReason.INCOMPLETE_CONTAINER_INDEX),
        new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.NO_KEYFRAMES));
  }

  @Test
  @DisplayName("Should copy the video when every segment interval holds a keyframe")
  void shouldCopyTheVideoWhenEverySegmentIntervalHoldsAKeyframe() {
    var source =
        h264Probe(
            ProbeFixture.keyframesEvery(Duration.ofMillis(5_005), Duration.ofMinutes(120)), "aac");

    var decision = service.decide(source, options(List.of("h264")));

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  private MediaProbe h264Probe(KeyframeKnowledge keyframes, String audioCodec) {
    return MediaProbe.builder()
        .keyframes(keyframes)
        .duration(Duration.ofMinutes(120))
        .framerate(OptionalDouble.of(23.976))
        .width(1920)
        .height(1080)
        .videoCodec("h264")
        .audioCodec(audioCodec)
        .bitrate(5_000_000L)
        .build();
  }

  private MediaProbe probe(String videoCodec, String audioCodec) {
    return probe(videoCodec, audioCodec, 2, 128_000L);
  }

  private MediaProbe probe(
      String videoCodec, String audioCodec, int audioChannels, long audioBitrate) {
    return MediaProbe.builder()
        .keyframes(KEYFRAMES_EVERY_TWO_SECONDS)
        .duration(Duration.ofMinutes(120))
        .framerate(OptionalDouble.of(23.976))
        .width(1920)
        .height(1080)
        .videoCodec(videoCodec)
        .audioCodec(audioCodec)
        .bitrate(5_000_000L)
        .audioChannels(OptionalInt.of(audioChannels))
        .audioBitrate(OptionalLong.of(audioBitrate))
        .build();
  }

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

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should audio transcode when video matches but audio needs conversion")
  void shouldAudioTranscodeWhenVideoMatchesButAudioNeedsConversion() {
    var source = probe("h264", "flac", 2, 0);
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should video transcode when video incompatible but audio is compatible")
  void shouldVideoTranscodeWhenVideoIncompatibleButAudioIsCompatible() {
    var source = probe("hevc", "aac");
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

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

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @Test
  @DisplayName("Should remux AV1 source when client supports AV1 and audio is AAC")
  void shouldRemuxAv1SourceWhenClientSupportsAv1AndAudioIsAac() {
    var source = probe("av1", "aac");
    var clientOptions = options(List.of("av1", "h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @Test
  @DisplayName("Should audio transcode AV1 source when audio is not AAC")
  void shouldAudioTranscodeAv1SourceWhenAudioIsNotAac() {
    var source = probe("av1", "dts", 6, 1_500_000L);
    var clientOptions = options(List.of("av1"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should fallback to default codec when client supports neither preference")
  void shouldFallbackToDefaultCodecWhenClientSupportsNeitherPreference() {
    var source = probe("hevc", "aac");
    var clientOptions = options(List.of("vp9"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  // --- Video-only (no audio stream) ---

  @Test
  @DisplayName("Should decide audio mode none when source has no audio stream")
  void shouldDecideAudioModeNoneWhenSourceHasNoAudioStream() {
    var source =
        MediaProbe.builder()
            .keyframes(KEYFRAMES_EVERY_TWO_SECONDS)
            .duration(Duration.ofMinutes(120))
            .framerate(OptionalDouble.of(23.976))
            .width(1920)
            .height(1080)
            .videoCodec("h264")
            .audioCodec(null)
            .bitrate(5_000_000L)
            .build();
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

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

    var decision = service.decide(source, clientOptions);

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

    var decision = service.decide(source, clientOptions);

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

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should transcode to AC-3 when channel cap forces transcode")
  void shouldTranscodeToAc3WhenChannelCapForcesTranscode() {
    var source = probe("h264", "ac3", 8, 640_000L);
    var clientOptions = options(List.of("h264"), List.of("ac3"), 6);

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should copy FLAC audio when remuxing H264 and the client supports FLAC")
  void shouldCopyFlacAudioWhenRemuxingH264AndClientSupportsFlac() {
    var source = probe("h264", "flac", 6, 0);
    var clientOptions = options(List.of("h264"), List.of("aac", "flac"), 6);

    var decision = service.decide(source, clientOptions);

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

    var decision = service.decide(source, clientOptions);

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

    var decision = service.decide(source, clientOptions);

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

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should copy multichannel AAC when remuxing H264")
  void shouldCopyMultichannelAacWhenRemuxingH264() {
    var source = probe("h264", "aac", 6, 384_000L);
    var clientOptions = options(List.of("h264"), List.of("aac"), 6);

    var decision = service.decide(source, clientOptions);

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

    var decision = service.decide(source, clientOptions);

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

    var decision = service.decide(source, clientOptions);

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

    var decision = service.decide(source, clientOptions);

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

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should default to stereo when source audio channels are empty")
  void shouldDefaultToStereoWhenSourceAudioChannelsAreEmpty() {
    var source =
        MediaProbe.builder()
            .keyframes(KEYFRAMES_EVERY_TWO_SECONDS)
            .duration(Duration.ofMinutes(120))
            .framerate(OptionalDouble.of(23.976))
            .width(1920)
            .height(1080)
            .videoCodec("h264")
            .audioCodec("aac")
            .bitrate(5_000_000L)
            .audioChannels(OptionalInt.empty())
            .audioBitrate(OptionalLong.empty())
            .build();
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(128_000L);
  }
}
