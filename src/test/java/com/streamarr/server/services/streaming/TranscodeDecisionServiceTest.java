package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class TranscodeDecisionServiceTest {

  private final TranscodeDecisionService service = new TranscodeDecisionService();

  private MediaProbe probe(String videoCodec, String audioCodec) {
    return MediaProbe.builder()
        .duration(Duration.ofMinutes(120))
        .framerate(23.976)
        .width(1920)
        .height(1080)
        .videoCodec(videoCodec)
        .audioCodec(audioCodec)
        .bitrate(5_000_000L)
        .build();
  }

  private StreamingOptions options(List<String> supportedCodecs) {
    return StreamingOptions.builder().supportedCodecs(supportedCodecs).build();
  }

  @Test
  @DisplayName("Should remux when video codec matches and audio is AAC")
  void shouldRemuxWhenVideoCodecMatchesAndAudioIsAac() {
    var source = probe("h264", "aac");
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioCodec()).isEqualTo("aac");
    assertThat(decision.needsKeyframeAlignment()).isTrue();
  }

  @Test
  @DisplayName("Should partial transcode when video matches but audio needs conversion")
  void shouldPartialTranscodeWhenVideoMatchesButAudioNeedsConversion() {
    var source = probe("h264", "flac");
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.PARTIAL_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioCodec()).isEqualTo("aac");
    assertThat(decision.needsKeyframeAlignment()).isTrue();
  }

  @Test
  @DisplayName("Should full transcode when video codec is incompatible")
  void shouldFullTranscodeWhenVideoCodecIncompatible() {
    var source = probe("hevc", "aac");
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioCodec()).isEqualTo("aac");
    assertThat(decision.needsKeyframeAlignment()).isFalse();
  }

  @Test
  @DisplayName("Should prefer AV1 over H264 when client supports both")
  void shouldPreferAv1OverH264WhenClientSupportsBoth() {
    var source = probe("hevc", "aac");
    var clientOptions = options(List.of("av1", "h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
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
  @DisplayName("Should partial transcode AV1 source when audio is not AAC")
  void shouldPartialTranscodeAv1SourceWhenAudioIsNotAac() {
    var source = probe("av1", "ac3");
    var clientOptions = options(List.of("av1"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.PARTIAL_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
    assertThat(decision.audioCodec()).isEqualTo("aac");
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
    var source = probe("hevc", "flac");
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.needsKeyframeAlignment()).isFalse();
  }

  @Test
  @DisplayName("Should need keyframe alignment when doing partial transcode")
  void shouldNeedKeyframeAlignmentWhenDoingPartialTranscode() {
    var source = probe("h264", "flac");
    var clientOptions = options(List.of("h264"));

    var decision = service.decide(source, clientOptions);

    assertThat(decision.needsKeyframeAlignment()).isTrue();
  }
}
