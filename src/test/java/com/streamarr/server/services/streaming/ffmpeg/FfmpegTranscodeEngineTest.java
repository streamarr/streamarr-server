package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.transcode.engine.AudioDecision;
import com.streamarr.transcode.engine.AudioMode;
import com.streamarr.transcode.engine.ContainerFormat;
import com.streamarr.transcode.engine.SubtitleDecision;
import com.streamarr.transcode.engine.SubtitleMode;
import com.streamarr.transcode.engine.TranscodeDecision;
import com.streamarr.transcode.engine.TranscodeException;
import com.streamarr.transcode.engine.TranscodeMode;
import com.streamarr.transcode.engine.TranscodeRequest;
import com.streamarr.transcode.engine.TranscodeStatus;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("FFmpeg Transcode Engine Tests")
class FfmpegTranscodeEngineTest {

  @TempDir Path tempDir;

  private FakeFfmpegProcessManager processManager;
  private FfmpegTranscodeEngine executor;

  @BeforeEach
  void setUp() {
    processManager = new FakeFfmpegProcessManager();
    var commandBuilder = new FfmpegCommandBuilder("ffmpeg");

    var hwCapability =
        HardwareEncodingCapability.builder()
            .available(true)
            .encoders(Set.of("h264_nvenc", "av1_nvenc"))
            .accelerator("cuda")
            .build();

    var capabilityService = createCapabilityService(true, hwCapability);

    executor = new FfmpegTranscodeEngine(commandBuilder, processManager, capabilityService);
  }

  private TranscodeRequest createRequest(TranscodeMode mode, String codecFamily) {
    return createRequest(mode, codecFamily, null);
  }

  private TranscodeRequest createRequest(
      TranscodeMode mode, String codecFamily, String variantLabel) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .seekPosition(0)
        .targetSegmentDuration(6)
        .framerate(23.976)
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(mode)
                .videoCodecFamily(codecFamily)
                .audioDecision(
                    AudioDecision.builder()
                        .mode(AudioMode.TRANSCODE)
                        .codec("aac")
                        .channels(2)
                        .bitrate(128_000L)
                        .build())
                .subtitleDecision(
                    new SubtitleDecision(
                        SubtitleMode.EXCLUDE,
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty()))
                .containerFormat(
                    "av1".equals(codecFamily) ? ContainerFormat.FMP4 : ContainerFormat.MPEGTS)
                .needsKeyframeAlignment(mode != TranscodeMode.FULL_TRANSCODE)
                .build())
        .width(1920)
        .height(1080)
        .bitrate(5_000_000L)
        .variantLabel(variantLabel)
        .build();
  }

  @Test
  @DisplayName("Should propagate attempt identity and start sequence when starting a producer")
  void shouldPropagateAttemptIdentityAndStartSequenceWhenStartingAProducer() {
    var attemptId = UUID.randomUUID();
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movie.mkv"))
            .seekPosition(12)
            .targetSegmentDuration(6)
            .framerate(23.976)
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily("h264")
                    .audioDecision(
                        AudioDecision.builder()
                            .mode(AudioMode.TRANSCODE)
                            .codec("aac")
                            .channels(2)
                            .bitrate(128_000L)
                            .build())
                    .subtitleDecision(
                        new SubtitleDecision(
                            SubtitleMode.EXCLUDE,
                            Optional.empty(),
                            OptionalInt.empty(),
                            Optional.empty()))
                    .containerFormat(ContainerFormat.MPEGTS)
                    .needsKeyframeAlignment(false)
                    .build())
            .width(1920)
            .height(1080)
            .bitrate(5_000_000L)
            .attemptId(attemptId)
            .startSequenceNumber(2)
            .build();

    var handle = executor.start(request, tempDir);

    // Recovery fencing matches observed handles against the attempt the coordinator minted; a
    // handle carrying a fresh random attemptId would break stale-producer detection invisibly.
    assertThat(handle.attemptId()).isEqualTo(attemptId);
    assertThat(handle.attemptId()).isNotEqualTo(request.sessionId());
    assertThat(handle.startSequenceNumber()).isEqualTo(2);
    assertThat(handle.processId()).isPresent();
  }

  @Test
  @DisplayName("Should start active transcode when GPU encoder available")
  void shouldStartActiveTranscodeWhenGpuEncoderAvailable() {
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");

    var handle = executor.start(request, tempDir);

    assertThat(handle).isNotNull();
    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(processManager.getStarted()).contains(request.sessionId());
  }

  @Test
  @DisplayName("Should start active transcode when no GPU available")
  void shouldStartActiveTranscodeWhenNoGpuAvailable() {
    var noHwCapability =
        HardwareEncodingCapability.builder().available(false).encoders(Set.of()).build();
    var capabilityService = createCapabilityService(true, noHwCapability);

    executor =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder("ffmpeg"), processManager, capabilityService);

    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "av1");

    var handle = executor.start(request, tempDir);

    assertThat(handle).isNotNull();
    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should stop transcode and remove from tracking when stopped")
  void shouldStopTranscodeAndRemoveFromTrackingWhenStopped() {
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");
    executor.start(request, tempDir);

    executor.stop(request.sessionId());

    assertThat(executor.isRunning(request.sessionId(), "default")).isFalse();
    assertThat(processManager.getStopped()).contains(request.sessionId());
  }

  @Test
  @DisplayName("Should report running only between start and stop when session is active")
  void shouldReportRunningOnlyBetweenStartAndStopWhenSessionIsActive() {
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");

    assertThat(executor.isRunning(request.sessionId(), "default")).isFalse();

    executor.start(request, tempDir);

    assertThat(executor.isRunning(request.sessionId(), "default")).isTrue();

    executor.stop(request.sessionId(), "default");

    assertThat(executor.isRunning(request.sessionId(), "default")).isFalse();
  }

  @Test
  @DisplayName("Should report not running when session is unknown")
  void shouldReportNotRunningWhenSessionIsUnknown() {
    assertThat(executor.isRunning(UUID.randomUUID(), "default")).isFalse();
  }

  @Test
  @DisplayName("Should report healthy when FFmpeg available")
  void shouldReportHealthyWhenFfmpegAvailable() {
    assertThat(executor.isHealthy()).isTrue();
  }

  @Test
  @DisplayName("Should report unhealthy when FFmpeg unavailable")
  void shouldReportUnhealthyWhenFfmpegUnavailable() {
    var capabilityService =
        createCapabilityService(
            false,
            HardwareEncodingCapability.builder().available(false).encoders(Set.of()).build());

    executor =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder("ffmpeg"), processManager, capabilityService);

    assertThat(executor.isHealthy()).isFalse();
  }

  @Test
  @DisplayName("Should reject producer start when FFmpeg lacks required HLS capabilities")
  void shouldRejectProducerStartWhenFfmpegLacksRequiredHlsCapabilities() {
    var capabilityService =
        new TranscodeCapabilityService(
            "ffmpeg",
            command -> {
              if (String.join(" ", command).contains("muxer=hls")) {
                return new FakeProcess("Muxer hls [Apple HTTP Live Streaming]:", 0);
              }

              return new FakeProcess("ffmpeg version 4.4.2", 0);
            });
    capabilityService.detectCapabilities();
    executor =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder("ffmpeg"), processManager, capabilityService);
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "av1");

    var thrown = catchThrowable(() -> executor.start(request, tempDir));

    assertThat(processManager.getStarted()).doesNotContain(request.sessionId());
    assertThat(thrown)
        .isInstanceOf(TranscodeException.class)
        .hasMessage("FFmpeg is unavailable: Missing hls_segment_options");
  }

  @Test
  @DisplayName("Should start active transcode when mode is remux")
  void shouldStartActiveTranscodeWhenModeIsRemux() {
    var request = createRequest(TranscodeMode.REMUX, "h264");

    var handle = executor.start(request, tempDir);

    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(processManager.getStarted()).contains(request.sessionId());
  }

  @Test
  @DisplayName("Should report not running for unstarted variant")
  void shouldReportNotRunningForUnstartedVariant() {
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264", "720p");
    executor.start(request, tempDir);

    assertThat(executor.isRunning(request.sessionId(), "1080p")).isFalse();
  }

  @Test
  @DisplayName("Should start active transcode when mode is audio transcode")
  void shouldStartActiveTranscodeWhenModeIsAudioTranscode() {
    var request = createRequest(TranscodeMode.AUDIO_TRANSCODE, "h264");

    var handle = executor.start(request, tempDir);

    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(processManager.getStarted()).contains(request.sessionId());
  }

  private TranscodeCapabilityService createCapabilityService(
      boolean available, HardwareEncodingCapability hwCapability) {
    var service =
        new TranscodeCapabilityService(
            "ffmpeg", command -> new FakeProcess("ffmpeg version 7.0", available ? 0 : 1));
    if (!available) {
      return service;
    }

    var outputs =
        Map.of(
            "ffmpeg", (Process) new FakeProcess("ffmpeg version 7.0", 0),
            "hls", (Process) new FakeProcess("-hls_segment_options <dictionary>", 0),
            "hwaccels",
                (Process)
                    new FakeProcess(
                        hwCapability.available()
                            ? "Hardware acceleration methods:\ncuda\n"
                            : "Hardware acceleration methods:\n",
                        0),
            "encoders", (Process) new FakeProcess(buildEncoderOutput(hwCapability.encoders()), 0));

    var testService =
        new TranscodeCapabilityService(
            "ffmpeg",
            command -> {
              var cmdStr = String.join(" ", command);
              if (cmdStr.contains("-version")) {
                return outputs.get("ffmpeg");
              }

              if (cmdStr.contains("muxer=hls")) {
                return outputs.get("hls");
              }

              if (cmdStr.contains("-hwaccels")) {
                return outputs.get("hwaccels");
              }

              if (cmdStr.contains("-encoders")) {
                return outputs.get("encoders");
              }

              return new FakeProcess("", 1);
            });
    testService.detectCapabilities();
    return testService;
  }

  private String buildEncoderOutput(Set<String> encoders) {
    var sb = new StringBuilder();
    for (var encoder : encoders) {
      sb.append(" V....D ")
          .append(encoder)
          .append("           ")
          .append(encoder)
          .append(" encoder\n");
    }

    return sb.toString();
  }
}
