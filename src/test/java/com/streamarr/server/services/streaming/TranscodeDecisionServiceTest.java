package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Transcode Decision Service Tests")
class TranscodeDecisionServiceTest {

  private final TranscodeDecisionService service = new TranscodeDecisionService();

  private MediaProbe probe(String videoCodec, String audioCodec) {
    return probe(videoCodec, audioCodec, 2, 128_000L);
  }

  private MediaProbe probe(
      String videoCodec, String audioCodec, int audioChannels, long audioBitrate) {
    return MediaProbe.builder()
        .duration(Duration.ofMinutes(120))
        .framerate(23.976)
        .width(1920)
        .height(1080)
        .videoCodec(videoCodec)
        .audioCodec(audioCodec)
        .bitrate(5_000_000L)
        .audioChannels(audioChannels)
        .audioBitrate(audioBitrate)
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
    assertThat(decision.needsKeyframeAlignment()).isTrue();
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
    assertThat(decision.needsKeyframeAlignment()).isTrue();
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
    assertThat(decision.needsKeyframeAlignment()).isFalse();
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
  @DisplayName("Should use MPEGTS container when transcoding to H264")
  void shouldUseMpegtsContainerWhenTranscodingToH264() {
    var source = probe("hevc", "aac");
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.containerFormat()).isEqualTo(ContainerFormat.MPEGTS);
  }

  @Test
  @DisplayName("Should use fMP4 container when transcoding to AV1")
  void shouldUseFmp4ContainerWhenTranscodingToAv1() {
    var source = probe("hevc", "aac");
    var clientOptions = options(List.of("av1"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.containerFormat()).isEqualTo(ContainerFormat.FMP4);
  }

  @Test
  @DisplayName("Should remux AV1 source when client supports AV1 and audio is AAC")
  void shouldRemuxAv1SourceWhenClientSupportsAv1AndAudioIsAac() {
    var source = probe("av1", "aac");
    var clientOptions = options(List.of("av1", "h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
    assertThat(decision.containerFormat()).isEqualTo(ContainerFormat.FMP4);
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
  @DisplayName("Should use MPEGTS container when remuxing H264")
  void shouldUseMpegtsContainerWhenRemuxingH264() {
    var source = probe("h264", "aac");
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.containerFormat()).isEqualTo(ContainerFormat.MPEGTS);
  }

  @Test
  @DisplayName("Should not need keyframe alignment when doing full transcode")
  void shouldNotNeedKeyframeAlignmentWhenDoingFullTranscode() {
    var source = probe("hevc", "flac", 2, 0);
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.needsKeyframeAlignment()).isFalse();
  }

  @Test
  @DisplayName("Should need keyframe alignment when doing audio transcode")
  void shouldNeedKeyframeAlignmentWhenDoingAudioTranscode() {
    var source = probe("h264", "flac", 2, 0);
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.needsKeyframeAlignment()).isTrue();
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
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("h264")
            .audioCodec(null)
            .bitrate(5_000_000L)
            .audioChannels(0)
            .audioBitrate(0)
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
  @DisplayName("Should fall back to stereo AAC when source codec is unsupported by default capabilities")
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
  @DisplayName("Should fall back to stereo AAC when FLAC in MPEGTS is unsupported")
  void shouldFallbackToStereoAacWhenFlacInMpegtsIsUnsupported() {
    var source = probe("h264", "flac", 6, 0);
    var clientOptions = options(List.of("h264"), List.of("aac", "flac"), 6);

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
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
  @DisplayName("Should block multichannel AAC copy in MPEGTS")
  void shouldBlockMultichannelAacCopyInMpegts() {
    var source = probe("h264", "aac", 6, 384_000L);
    var clientOptions = options(List.of("h264"), List.of("aac"), 6);

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should allow multichannel AAC copy in fMP4")
  void shouldAllowMultichannelAacCopyInFmp4() {
    var source = probe("av1", "aac", 6, 384_000L);
    var clientOptions = options(List.of("av1"), List.of("aac"), 6);

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
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
}
