package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeJob;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("FFmpeg Command Builder Tests")
class FfmpegCommandBuilderTest {

  private final FfmpegCommandBuilder builder = new FfmpegCommandBuilder("ffmpeg");

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
        mode,
        codecFamily,
        audioCodec,
        container,
        videoEncoder,
        needsKeyframeAlignment,
        seekPosition,
        1920,
        1080,
        5_000_000L);
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
    var audio = audioDecisionFor(mode, audioCodec);
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
                        .audioDecision(audio)
                        .subtitleDecision(SubtitleDecision.exclude())
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

  private TranscodeJob jobWithStartNumber(
      TranscodeMode mode,
      String codecFamily,
      String audioCodec,
      ContainerFormat container,
      String videoEncoder,
      boolean needsKeyframeAlignment,
      int startNumber) {
    var audio = audioDecisionFor(mode, audioCodec);
    return TranscodeJob.builder()
        .request(
            TranscodeRequest.builder()
                .sessionId(UUID.randomUUID())
                .sourcePath(Path.of("/media/movie.mkv"))
                .seekPosition(0)
                .segmentDuration(6)
                .framerate(23.976)
                .transcodeDecision(
                    TranscodeDecision.builder()
                        .transcodeMode(mode)
                        .videoCodecFamily(codecFamily)
                        .audioDecision(audio)
                        .subtitleDecision(SubtitleDecision.exclude())
                        .containerFormat(container)
                        .needsKeyframeAlignment(needsKeyframeAlignment)
                        .build())
                .width(1920)
                .height(1080)
                .bitrate(5_000_000L)
                .startNumber(startNumber)
                .build())
        .videoEncoder(videoEncoder)
        .outputDir(Path.of("/tmp/session-123"))
        .build();
  }

  private AudioDecision audioDecisionFor(TranscodeMode mode, String audioCodec) {
    return switch (mode) {
      case REMUX, VIDEO_TRANSCODE -> AudioDecision.copy(audioCodec, 2, 0);
      case AUDIO_TRANSCODE, FULL_TRANSCODE -> AudioDecision.stereoAac();
    };
  }

  private TranscodeJob jobWithAudio(
      TranscodeMode mode,
      String codecFamily,
      AudioDecision audio,
      ContainerFormat container,
      String videoEncoder) {
    return TranscodeJob.builder()
        .request(
            TranscodeRequest.builder()
                .sessionId(UUID.randomUUID())
                .sourcePath(Path.of("/media/movie.mkv"))
                .seekPosition(0)
                .segmentDuration(6)
                .framerate(23.976)
                .transcodeDecision(
                    TranscodeDecision.builder()
                        .transcodeMode(mode)
                        .videoCodecFamily(codecFamily)
                        .audioDecision(audio)
                        .subtitleDecision(SubtitleDecision.exclude())
                        .containerFormat(container)
                        .needsKeyframeAlignment(mode == TranscodeMode.REMUX)
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
  @DisplayName("Should use copy codecs when mode is remux")
  void shouldUseCopyCodecsWhenModeIsRemux() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .isNotEmpty()
        .containsSubsequence("-c:v", "copy")
        .containsSubsequence("-c:a", "copy")
        .doesNotContain("-vf")
        .doesNotContain("-b:v", "-maxrate", "-bufsize");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fullTranscodeExpectedFlags")
  @DisplayName("Should include expected flags when mode is full transcode")
  void shouldIncludeExpectedFlagsWhenModeIsFullTranscode(String scenario, String... expectedFlags) {
    var j =
        job(TranscodeMode.FULL_TRANSCODE, "h264", "aac", ContainerFormat.MPEGTS, "libx264", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains(expectedFlags);
  }

  static Stream<Arguments> fullTranscodeExpectedFlags() {
    return Stream.of(
        Arguments.of("scale filter", new String[] {"-vf", "scale=-2:1080"}),
        Arguments.of("forced IDR", new String[] {"-forced-idr", "1"}),
        Arguments.of("audio downmix to stereo", new String[] {"-ac", "2"}));
  }

  @Test
  @DisplayName("Should include bitrate control when mode is full transcode")
  void shouldIncludeBitrateControlWhenModeIsFullTranscode() {
    var j =
        job(TranscodeMode.FULL_TRANSCODE, "h264", "aac", ContainerFormat.MPEGTS, "libx264", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .contains("-b:v", "5000000")
        .contains("-maxrate", "5000000")
        .contains("-bufsize", "10000000");
  }

  @Test
  @DisplayName("Should use variant dimensions when variant differs from source")
  void shouldUseVariantDimensionsWhenVariantDiffersFromSource() {
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

    assertThat(cmd)
        .contains("-vf", "scale=-2:720")
        .contains("-b:v", "3000000")
        .contains("-bufsize", "6000000");
  }

  @Test
  @DisplayName("Should not include scale or bitrate when mode is audio transcode")
  void shouldNotIncludeScaleOrBitrateWhenModeIsAudioTranscode() {
    var j = job(TranscodeMode.AUDIO_TRANSCODE, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).isNotEmpty().doesNotContain("-vf", "-b:v", "-maxrate", "-bufsize");
  }

  @Test
  @DisplayName("Should use copy video and AAC audio when mode is audio transcode")
  void shouldUseCopyVideoAndAacAudioWhenModeIsAudioTranscode() {
    var j = job(TranscodeMode.AUDIO_TRANSCODE, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .containsSubsequence("-c:v", "copy")
        .containsSubsequence("-c:a", "aac")
        .containsSubsequence("-b:a", "128k");
  }

  @Test
  @DisplayName("Should include H264 MPEGTS args when full transcode targets H264")
  void shouldIncludeH264MpegtsArgsWhenFullTranscodeTargetsH264() {
    var j =
        job(TranscodeMode.FULL_TRANSCODE, "h264", "aac", ContainerFormat.MPEGTS, "libx264", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .containsSubsequence("-c:v", "libx264")
        .containsSubsequence("-c:a", "aac")
        .containsSubsequence("-b:a", "128k")
        .containsSubsequence("-hls_segment_type", "mpegts");
  }

  @Test
  @DisplayName("Should include AV1 fMP4 args when full transcode targets AV1")
  void shouldIncludeAv1Fmp4ArgsWhenFullTranscodeTargetsAv1() {
    var j =
        job(TranscodeMode.FULL_TRANSCODE, "av1", "aac", ContainerFormat.FMP4, "libsvtav1", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .contains("-c:v", "libsvtav1")
        .contains("-hls_segment_type", "fmp4")
        .contains("-hls_fmp4_init_filename", "init.mp4");
  }

  @Test
  @DisplayName("Should use force keyframes when encoder is libx264")
  void shouldUseForceKeyframesWhenEncoderIsLibx264() {
    var j =
        job(TranscodeMode.FULL_TRANSCODE, "h264", "aac", ContainerFormat.MPEGTS, "libx264", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .isNotEmpty()
        .anyMatch(s -> s.startsWith("expr:gte(t,n_forced*"))
        .contains("-sc_threshold:v:0", "0");
  }

  @Test
  @DisplayName("Should use GOP size when encoder is NVENC")
  void shouldUseGopSizeWhenEncoderIsNvenc() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "h264_nvenc",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).isNotEmpty().contains("-g:v:0").doesNotContain("-force_key_frames:0");
  }

  @Test
  @DisplayName("Should use GOP size when encoder is libsvtav1")
  void shouldUseGopSizeWhenEncoderIsLibsvtav1() {
    var j =
        job(TranscodeMode.FULL_TRANSCODE, "av1", "aac", ContainerFormat.FMP4, "libsvtav1", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-g:v:0");
  }

  @Test
  @DisplayName("Should place seek before input when seek position is non-zero")
  void shouldPlaceSeekBeforeInputWhenSeekPositionIsNonZero() {
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
  @DisplayName("Should include fMP4 segment options when container is fMP4")
  void shouldIncludeFmp4SegmentOptionsWhenContainerIsFmp4() {
    var j =
        job(TranscodeMode.FULL_TRANSCODE, "av1", "aac", ContainerFormat.FMP4, "libsvtav1", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-hls_segment_options", "movflags=+frag_discont");
  }

  @Test
  @DisplayName("Should include common flags when building command")
  void shouldIncludeCommonFlagsWhenBuildingCommand() {
    var j =
        job(TranscodeMode.FULL_TRANSCODE, "h264", "aac", ContainerFormat.MPEGTS, "libx264", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .contains("-map_metadata", "-1")
        .contains("-map_chapters", "-1")
        .contains("-copyts")
        .contains("-avoid_negative_ts", "disabled")
        .contains("-max_muxing_queue_size", "128");
  }

  @Test
  @DisplayName("Should include HLS temp file flag when building command")
  void shouldIncludeHlsTempFileFlagWhenBuildingCommand() {
    var j =
        job(TranscodeMode.FULL_TRANSCODE, "h264", "aac", ContainerFormat.MPEGTS, "libx264", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).isNotEmpty().anyMatch(s -> s.contains("temp_file"));
  }

  @Test
  @DisplayName("Should start with FFmpeg binary when building command")
  void shouldStartWithFfmpegBinaryWhenBuildingCommand() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd.getFirst()).isEqualTo("ffmpeg");
  }

  @Test
  @DisplayName("Should set TS segment filename pattern when container is MPEGTS")
  void shouldSetTsSegmentFilenamePatternWhenContainerIsMpegts() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-hls_segment_filename");
    int idx = cmd.indexOf("-hls_segment_filename");
    assertThat(cmd.get(idx + 1)).contains("segment%d.ts");
  }

  @Test
  @DisplayName("Should set m4s segment filename pattern when container is fMP4")
  void shouldSetM4sSegmentFilenamePatternWhenContainerIsFmp4() {
    var j =
        job(TranscodeMode.FULL_TRANSCODE, "av1", "aac", ContainerFormat.FMP4, "libsvtav1", false);

    var cmd = builder.buildCommand(j);

    int idx = cmd.indexOf("-hls_segment_filename");
    assertThat(cmd.get(idx + 1)).contains("segment%d.m4s");
  }

  @Test
  @DisplayName("Should not include keyframe args when mode is remux")
  void shouldNotIncludeKeyframeArgsWhenModeIsRemux() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .isNotEmpty()
        .noneMatch(s -> s.contains("force_key_frames"))
        .doesNotContain("-g:v:0")
        .doesNotContain("-forced-idr");
  }

  @Test
  @DisplayName("Should output to HLS format when building command")
  void shouldOutputToHlsFormatWhenBuildingCommand() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-f", "hls");
  }

  @Test
  @DisplayName("Should use force keyframes when encoder is libx265")
  void shouldUseForceKeyframesWhenEncoderIsLibx265() {
    var j =
        job(TranscodeMode.FULL_TRANSCODE, "hevc", "aac", ContainerFormat.FMP4, "libx265", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .isNotEmpty()
        .anyMatch(s -> s.startsWith("expr:gte(t,n_forced*"))
        .doesNotContain("-sc_threshold:v:0");
  }

  @Test
  @DisplayName("Should use force keyframes when encoder is VAAPI")
  void shouldUseForceKeyframesWhenEncoderIsVaapi() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "h264_vaapi",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).isNotEmpty().anyMatch(s -> s.startsWith("expr:gte(t,n_forced*"));
  }

  @Test
  @DisplayName("Should include overwrite flag but not nostdin when building command")
  void shouldIncludeOverwriteFlagButNotNostdinWhenBuildingCommand() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).isNotEmpty().contains("-y").doesNotContain("-nostdin");
  }

  @Test
  @DisplayName("Should set HLS time when building command")
  void shouldSetHlsTimeWhenBuildingCommand() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-hls_time", "6");
  }

  @Test
  @DisplayName("Should include start number when start number is non-zero")
  void shouldIncludeStartNumberWhenStartNumberIsNonZero() {
    var j =
        jobWithStartNumber(
            TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true, 5);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-start_number", "5");
  }

  @Test
  @DisplayName("Should not include start number when start number is zero")
  void shouldNotIncludeStartNumberWhenStartNumberIsZero() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).isNotEmpty().doesNotContain("-start_number");
  }

  @Test
  @DisplayName("Should map first video and audio streams when building command")
  void shouldMapFirstVideoAndAudioStreamsWhenBuildingCommand() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).containsSequence("-map", "0:v:0", "-map", "0:a:0");
  }

  @Test
  @DisplayName("Should exclude subtitle streams when building command")
  void shouldExcludeSubtitleStreamsWhenBuildingCommand() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).containsSequence("-map", "-0:s");
  }

  @Test
  @DisplayName("Should downmix audio to stereo when mode is audio transcode")
  void shouldDownmixAudioToStereoWhenModeIsAudioTranscode() {
    var j = job(TranscodeMode.AUDIO_TRANSCODE, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-ac", "2");
  }

  @Test
  @DisplayName("Should not downmix audio when mode is remux")
  void shouldNotDownmixAudioWhenModeIsRemux() {
    var j = job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).isNotEmpty().doesNotContain("-ac");
  }

  @Test
  @DisplayName("Should use video encoder with audio copy when mode is video transcode")
  void shouldUseVideoEncoderWithAudioCopyWhenModeIsVideoTranscode() {
    var j =
        job(TranscodeMode.VIDEO_TRANSCODE, "h264", "ac3", ContainerFormat.MPEGTS, "libx264", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .isNotEmpty()
        .contains("-c:v", "libx264")
        .contains("-c:a", "copy")
        .contains("-vf", "scale=-2:1080")
        .doesNotContain("-ac")
        .doesNotContain("-b:a");
  }

  @Test
  @DisplayName("Should include keyframe args when mode is video transcode")
  void shouldIncludeKeyframeArgsWhenModeIsVideoTranscode() {
    var j =
        job(TranscodeMode.VIDEO_TRANSCODE, "h264", "ac3", ContainerFormat.MPEGTS, "libx264", false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd).contains("-forced-idr", "1");
  }

  @Test
  @DisplayName("Should only add forced IDR when encoder is in neither keyframe set")
  void shouldOnlyAddForcedIdrWhenEncoderIsInNeitherKeyframeSet() {
    var j =
        job(
            TranscodeMode.FULL_TRANSCODE,
            "h264",
            "aac",
            ContainerFormat.MPEGTS,
            "h264_videotoolbox",
            false);

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .isNotEmpty()
        .contains("-forced-idr", "1")
        .doesNotContain("-g:v:0")
        .noneMatch(s -> s.startsWith("-force_key_frames"));
  }

  // --- Video-only (no audio) ---

  @Test
  @DisplayName("Should omit audio map and codec args when audio mode is none")
  void shouldOmitAudioMapAndCodecArgsWhenAudioModeIsNone() {
    var audio = AudioDecision.none();
    var j =
        jobWithAudio(
            TranscodeMode.FULL_TRANSCODE, "h264", audio, ContainerFormat.MPEGTS, "libx264");

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .isNotEmpty()
        .doesNotContain("0:a:0")
        .doesNotContain("-c:a")
        .doesNotContain("-ac")
        .doesNotContain("-b:a");
  }

  // --- Surround sound audio args ---

  @Test
  @DisplayName("Should transcode to AC-3 5.1 when audio decision is AC-3 transcode")
  void shouldTranscodeToAc3SurroundWhenAudioDecisionIsAc3Transcode() {
    var audio = new AudioDecision(AudioMode.TRANSCODE, "ac3", 6, 384_000L);
    var j =
        jobWithAudio(TranscodeMode.AUDIO_TRANSCODE, "h264", audio, ContainerFormat.MPEGTS, "copy");

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .containsSubsequence("-c:a", "ac3")
        .containsSubsequence("-ac", "6")
        .containsSubsequence("-b:a", "384k");
  }

  @Test
  @DisplayName("Should transcode to E-AC-3 7.1 when audio decision is E-AC-3 transcode")
  void shouldTranscodeToEac3SurroundWhenAudioDecisionIsEac3Transcode() {
    var audio = new AudioDecision(AudioMode.TRANSCODE, "eac3", 8, 512_000L);
    var j =
        jobWithAudio(TranscodeMode.AUDIO_TRANSCODE, "h264", audio, ContainerFormat.MPEGTS, "copy");

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .containsSubsequence("-c:a", "eac3")
        .containsSubsequence("-ac", "8")
        .containsSubsequence("-b:a", "512k");
  }

  @Test
  @DisplayName("Should copy surround audio when audio decision is copy with 5.1 channels")
  void shouldCopySurroundAudioWhenAudioDecisionIsCopyWith51Channels() {
    var audio = AudioDecision.copy("ac3", 6, 384_000L);
    var j = jobWithAudio(TranscodeMode.REMUX, "h264", audio, ContainerFormat.MPEGTS, "copy");

    var cmd = builder.buildCommand(j);

    assertThat(cmd)
        .isNotEmpty()
        .contains("-c:a", "copy")
        .doesNotContain("-ac")
        .doesNotContain("-b:a");
  }

  // --- Map ordering ---

  @Test
  @DisplayName("Should map video, audio, and exclude subtitles in correct order")
  void shouldMapVideoAudioAndExcludeSubtitlesInCorrectOrder() {
    var transcodeJob =
        job(TranscodeMode.REMUX, "h264", "aac", ContainerFormat.MPEGTS, "copy", true);

    var cmd = builder.buildCommand(transcodeJob);

    assertThat(cmd).containsSequence("-map", "0:v:0", "-map", "0:a:0", "-map", "-0:s");
  }

  @Test
  @DisplayName("Should map video and exclude subtitles without audio when audio mode is none")
  void shouldMapVideoAndExcludeSubtitlesWithoutAudioWhenAudioModeIsNone() {
    var audio = AudioDecision.none();
    var transcodeJob =
        jobWithAudio(
            TranscodeMode.FULL_TRANSCODE, "h264", audio, ContainerFormat.MPEGTS, "libx264");

    var cmd = builder.buildCommand(transcodeJob);

    assertThat(cmd).containsSequence("-map", "0:v:0", "-map", "-0:s").doesNotContain("0:a:0");
  }
}
