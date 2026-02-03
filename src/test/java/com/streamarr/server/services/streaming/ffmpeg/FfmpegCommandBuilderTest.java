package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeJob;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class FfmpegCommandBuilderTest {

  private final FfmpegCommandBuilder builder = new FfmpegCommandBuilder();

  private TranscodeJob job(
      TranscodeMode mode,
      String codecFamily,
      String audioCodec,
      ContainerFormat container,
      String videoEncoder,
      boolean needsKeyframeAlignment) {
    return job(mode, codecFamily, audioCodec, container, videoEncoder, needsKeyframeAlignment, 0);
  }

  private TranscodeJob job(
      TranscodeMode mode,
      String codecFamily,
      String audioCodec,
      ContainerFormat container,
      String videoEncoder,
      boolean needsKeyframeAlignment,
      int seekPosition) {
    return job(
        mode, codecFamily, audioCodec, container, videoEncoder, needsKeyframeAlignment,
        seekPosition, 1920, 1080, 5_000_000L);
  }

  private TranscodeJob job(
      TranscodeMode mode,
      String codecFamily,
      String audioCodec,
      ContainerFormat container,
      String videoEncoder,
      boolean needsKeyframeAlignment,
      int seekPosition,
      int width,
      int height,
      long bitrate) {
    return TranscodeJob.builder()
        .request(
            TranscodeRequest.builder()
                .sessionId(UUID.randomUUID())
                .sourcePath(Path.of("/media/movie.mkv"))
                .seekPosition(seekPosition)
                .segmentDuration(6)
                .framerate(23.976)
                .transcodeDecision(
                    TranscodeDecision.builder()
                        .transcodeMode(mode)
                        .videoCodecFamily(codecFamily)
                        .audioCodec(audioCodec)
                        .containerFormat(container)
                        .needsKeyframeAlignment(needsKeyframeAlignment)
                        .build())
                .width(width)
                .height(height)
                .bitrate(bitrate)
                .build())
        .videoEncoder(videoEncoder)
        .outputDir(Path.of("/tmp/session-123"))
        .build();
  }

  @Test
  @DisplayName("Should build remux command with copy codecs")
  void shouldBuildRemuxCommandWithCopyCodecs() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-c:v", "copy", "-c:a", "copy");
    assertThat(cmd).doesNotContain("-vf");
    assertThat(cmd).doesNotContain("-b:v", "-maxrate", "-bufsize");
  }

  @Test
  @DisplayName("Should include scale filter for full transcode")
  void shouldIncludeScaleFilterForFullTranscode() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "libx264",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-vf", "scale=-2:1080");
  }

  @Test
  @DisplayName("Should include bitrate control for full transcode")
  void shouldIncludeBitrateControlForFullTranscode() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "libx264",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-b:v", "5000000");
    assertThat(cmd).contains("-maxrate", "5000000");
    assertThat(cmd).contains("-bufsize", "10000000");
  }

  @Test
  @DisplayName("Should use variant height for scale and bitrate")
  void shouldUseVariantHeightForScaleAndBitrate() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "libx264",
            false,
            0,
            1280,
            720,
            3_000_000L);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-vf", "scale=-2:720");
    assertThat(cmd).contains("-b:v", "3000000");
    assertThat(cmd).contains("-bufsize", "6000000");
  }

  @Test
  @DisplayName("Should not include scale or bitrate for partial transcode")
  void shouldNotIncludeScaleOrBitrateForPartialTranscode() {
    var j =
        job(
            TranscodeMode.PARTIAL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "copy",
            true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).doesNotContain("-vf", "-b:v", "-maxrate", "-bufsize");
  }

  @Test
  @DisplayName("Should build partial transcode with copy video and AAC audio")
  void shouldBuildPartialTranscodeWithCopyVideoAndAacAudio() {
    var j =
        job(TranscodeMode.PARTIAL_TRANSCODE, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-c:v", "copy");
    assertThat(cmd).contains("-c:a", "aac", "-b:a", "128k");
  }

  @Test
  @DisplayName("Should build full transcode with H264 MPEGTS args")
  void shouldBuildFullTranscodeWithH264MpegtsArgs() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "libx264",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-c:v", "libx264");
    assertThat(cmd).contains("-c:a", "aac", "-b:a", "128k");
    assertThat(cmd).contains("-hls_segment_type", "mpegts");
  }

  @Test
  @DisplayName("Should build full transcode with AV1 fMP4 args")
  void shouldBuildFullTranscodeWithAv1Fmp4Args() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "av1",
            "aac",
            ContainerFormat.FMP4,
            "libsvtav1",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-c:v", "libsvtav1");
    assertThat(cmd).contains("-hls_segment_type", "fmp4");
    assertThat(cmd).contains("-hls_fmp4_init_filename", "init.mp4");
  }

  @Test
  @DisplayName("Should use force keyframes for libx264")
  void shouldUseForceKeyframesForLibx264() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "libx264",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).anyMatch(s -> s.startsWith("expr:gte(t,n_forced*"));
    assertThat(cmd).contains("-sc_threshold:v:0", "0");
  }

  @Test
  @DisplayName("Should use GOP size for NVENC encoder")
  void shouldUseGopSizeForNvencEncoder() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "h264_nvenc",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-g:v:0");
    assertThat(cmd).doesNotContain("-force_key_frames:0");
  }

  @Test
  @DisplayName("Should use GOP size for libsvtav1")
  void shouldUseGopSizeForLibsvtav1() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "av1",
            "aac",
            ContainerFormat.FMP4,
            "libsvtav1",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-g:v:0");
  }

  @Test
  @DisplayName("Should include forced IDR for full transcode")
  void shouldIncludeForcedIdrForFullTranscode() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "libx264",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-forced-idr", "1");
  }

  @Test
  @DisplayName("Should place seek before input")
  void shouldPlaceSeekBeforeInput() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "libx264",
            false,
            300);

    var cmd = builder.buildCommand(j);

    int ssIndex = cmd.indexOf("-ss");
    int iIndex = cmd.indexOf("-i");
    assertThat(ssIndex).isGreaterThan(-1);
    assertThat(iIndex).isGreaterThan(ssIndex);
    assertThat(cmd.get(ssIndex + 1)).isEqualTo("300");
  }

  @Test
  @DisplayName("Should not include seek when position is zero")
  void shouldNotIncludeSeekWhenPositionIsZero() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "libx264",
            false,
            0);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).isNotEmpty().doesNotContain("-ss");
  }

  @Test
  @DisplayName("Should include fMP4 segment options")
  void shouldIncludeFmp4SegmentOptions() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "av1",
            "aac",
            ContainerFormat.FMP4,
            "libsvtav1",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-hls_segment_options", "movflags=+frag_discont");
  }

  @Test
  @DisplayName("Should include common flags")
  void shouldIncludeCommonFlags() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "libx264",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-map_metadata", "-1");
    assertThat(cmd).contains("-map_chapters", "-1");
    assertThat(cmd).contains("-copyts");
    assertThat(cmd).contains("-avoid_negative_ts", "disabled");
    assertThat(cmd).contains("-max_muxing_queue_size", "128");
  }

  @Test
  @DisplayName("Should include HLS temp file flag")
  void shouldIncludeHlsTempFileFlag() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "libx264",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).anyMatch(s -> s.contains("temp_file"));
  }

  @Test
  @DisplayName("Should start with FFmpeg binary")
  void shouldStartWithFfmpegBinary() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd.getFirst()).isEqualTo("ffmpeg");
  }

  @Test
  @DisplayName("Should set correct segment filename pattern")
  void shouldSetCorrectSegmentFilenamePattern() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-hls_segment_filename");
    int idx = cmd.indexOf("-hls_segment_filename");
    assertThat(cmd.get(idx + 1)).contains("segment%d.ts");
  }

  @Test
  @DisplayName("Should set fMP4 segment filename pattern")
  void shouldSetFmp4SegmentFilenamePattern() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "av1",
            "aac",
            ContainerFormat.FMP4,
            "libsvtav1",
            false);

    var cmd = builder.buildCommand(j);

    int idx = cmd.indexOf("-hls_segment_filename");
    assertThat(cmd.get(idx + 1)).contains("segment%d.m4s");
  }

  @Test
  @DisplayName("Should not include keyframe args for remux")
  void shouldNotIncludeKeyframeArgsForRemux() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).noneMatch(s -> s.contains("force_key_frames"));
    assertThat(cmd).doesNotContain("-g:v:0");
    assertThat(cmd).doesNotContain("-forced-idr");
  }

  @Test
  @DisplayName("Should output to HLS format")
  void shouldOutputToHlsFormat() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-f", "hls");
  }

  @Test
  @DisplayName("Should use force keyframes for libx265")
  void shouldUseForceKeyframesForLibx265() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "hevc",
            "aac",
            ContainerFormat.FMP4,
            "libx265",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).anyMatch(s -> s.startsWith("expr:gte(t,n_forced*"));
    assertThat(cmd).doesNotContain("-sc_threshold:v:0");
  }

  @Test
  @DisplayName("Should use force keyframes for VAAPI encoder")
  void shouldUseForceKeyframesForVaapiEncoder() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "h264_vaapi",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).anyMatch(s -> s.startsWith("expr:gte(t,n_forced*"));
  }

  @Test
  @DisplayName("Should include overwrite flag but not nostdin")
  void shouldIncludeOverwriteFlagButNotNoStdin() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-y");
    assertThat(cmd).doesNotContain("-nostdin");
  }

  @Test
  @DisplayName("Should set HLS time")
  void shouldSetHlsTime() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-hls_time", "6");
  }
}
