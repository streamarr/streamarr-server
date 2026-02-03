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
                .width(1920)
                .height(1080)
                .bitrate(5_000_000L)
                .build())
        .videoEncoder(videoEncoder)
        .outputDir(Path.of("/tmp/session-123"))
        .build();
  }

  @Test
  @DisplayName("shouldBuildRemuxCommandWithCopyCodecs")
  void shouldBuildRemuxCommandWithCopyCodecs() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-c:v", "copy", "-c:a", "copy");
    assertThat(cmd).doesNotContain("-vf");
  }

  @Test
  @DisplayName("shouldBuildPartialTranscodeWithCopyVideoAndAacAudio")
  void shouldBuildPartialTranscodeWithCopyVideoAndAacAudio() {
    var j =
        job(TranscodeMode.PARTIAL_TRANSCODE, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-c:v", "copy");
    assertThat(cmd).contains("-c:a", "aac", "-b:a", "128k");
  }

  @Test
  @DisplayName("shouldBuildFullTranscodeWithH264MpegtsArgs")
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
  @DisplayName("shouldBuildFullTranscodeWithAv1Fmp4Args")
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
  @DisplayName("shouldUseForceKeyframesForLibx264")
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
  @DisplayName("shouldUseGopSizeForNvencEncoder")
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
  @DisplayName("shouldUseGopSizeForLibsvtav1")
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
  @DisplayName("shouldIncludeForcedIdrForFullTranscode")
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
  @DisplayName("shouldPlaceSeekBeforeInput")
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
  @DisplayName("shouldNotIncludeSeekWhenPositionIsZero")
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

    assertThat(cmd).doesNotContain("-ss");
  }

  @Test
  @DisplayName("shouldIncludeFmp4SegmentOptions")
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
  @DisplayName("shouldIncludeCommonFlags")
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
  @DisplayName("shouldIncludeHlsTempFileFlag")
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
  @DisplayName("shouldStartWithFfmpegBinary")
  void shouldStartWithFfmpegBinary() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd.getFirst()).isEqualTo("ffmpeg");
  }

  @Test
  @DisplayName("shouldSetCorrectSegmentFilenamePattern")
  void shouldSetCorrectSegmentFilenamePattern() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-hls_segment_filename");
    int idx = cmd.indexOf("-hls_segment_filename");
    assertThat(cmd.get(idx + 1)).contains("segment%d.ts");
  }

  @Test
  @DisplayName("shouldSetFmp4SegmentFilenamePattern")
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
  @DisplayName("shouldNotIncludeKeyframeArgsForRemux")
  void shouldNotIncludeKeyframeArgsForRemux() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).noneMatch(s -> s.contains("force_key_frames"));
    assertThat(cmd).doesNotContain("-g:v:0");
    assertThat(cmd).doesNotContain("-forced-idr");
  }

  @Test
  @DisplayName("shouldOutputToHlsFormat")
  void shouldOutputToHlsFormat() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-f", "hls");
  }

  @Test
  @DisplayName("shouldUseForceKeyframesForLibx265")
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
  @DisplayName("shouldUseForceKeyframesForVaapiEncoder")
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
  @DisplayName("shouldIncludeOverwriteFlagButNotNoStdin")
  void shouldIncludeOverwriteFlagButNotNoStdin() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-y");
    assertThat(cmd).doesNotContain("-nostdin");
  }

  @Test
  @DisplayName("shouldSetHlsTime")
  void shouldSetHlsTime() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-hls_time", "6");
  }
}
