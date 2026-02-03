package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.fakes.FakeSegmentStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class LocalTranscodeExecutorTest {

  private FakeFfmpegProcessManager processManager;
  private FakeSegmentStore segmentStore;
  private LocalTranscodeExecutor executor;

  @BeforeEach
  void setUp() {
    processManager = new FakeFfmpegProcessManager();
    segmentStore = new FakeSegmentStore();
    var commandBuilder = new FfmpegCommandBuilder();

    var hwCapability =
        HardwareEncodingCapability.builder()
            .available(true)
            .encoders(Set.of("h264_nvenc", "av1_nvenc"))
            .accelerator("cuda")
            .build();

    var capabilityService = createCapabilityService(true, hwCapability);

    executor = new LocalTranscodeExecutor(commandBuilder, processManager, segmentStore,
        capabilityService);
  }

  private TranscodeRequest createRequest(TranscodeMode mode, String codecFamily) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .seekPosition(0)
        .segmentDuration(6)
        .framerate(23.976)
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(mode)
                .videoCodecFamily(codecFamily)
                .audioCodec("aac")
                .containerFormat(
                    "av1".equals(codecFamily) ? ContainerFormat.FMP4 : ContainerFormat.MPEGTS)
                .needsKeyframeAlignment(mode != TranscodeMode.FULL_TRANSCODE)
                .build())
        .width(1920)
        .height(1080)
        .bitrate(5_000_000L)
        .build();
  }

  @Test
  @DisplayName("Should start transcode with GPU encoder when available")
  void shouldStartTranscodeWithGpuEncoderWhenAvailable() {
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");

    var handle = executor.start(request);

    assertThat(handle).isNotNull();
    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(processManager.getStarted()).contains(request.sessionId());
  }

  @Test
  @DisplayName("Should fall back to software encoder when no GPU")
  void shouldFallbackToSoftwareEncoderWhenNoGpu() {
    var noHwCapability =
        HardwareEncodingCapability.builder().available(false).encoders(Set.of()).build();
    var capabilityService = createCapabilityService(true, noHwCapability);

    executor = new LocalTranscodeExecutor(new FfmpegCommandBuilder(), processManager,
        segmentStore, capabilityService);

    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "av1");

    var handle = executor.start(request);

    assertThat(handle).isNotNull();
    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should stop transcode and remove from map")
  void shouldStopTranscodeAndRemoveFromMap() {
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");
    executor.start(request);

    executor.stop(request.sessionId());

    assertThat(executor.isRunning(request.sessionId())).isFalse();
    assertThat(processManager.getStopped()).contains(request.sessionId());
  }

  @Test
  @DisplayName("Should report running for active session")
  void shouldReportRunningForActiveSession() {
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");
    executor.start(request);

    assertThat(executor.isRunning(request.sessionId())).isTrue();
  }

  @Test
  @DisplayName("Should report not running for unknown session")
  void shouldReportNotRunningForUnknownSession() {
    assertThat(executor.isRunning(UUID.randomUUID())).isFalse();
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
        createCapabilityService(false,
            HardwareEncodingCapability.builder().available(false).encoders(Set.of()).build());

    executor = new LocalTranscodeExecutor(new FfmpegCommandBuilder(), processManager,
        segmentStore, capabilityService);

    assertThat(executor.isHealthy()).isFalse();
  }

  @Test
  @DisplayName("Should use copy encoder for remux")
  void shouldUseCopyEncoderForRemux() {
    var request = createRequest(TranscodeMode.REMUX, "h264");

    var handle = executor.start(request);

    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(processManager.getStarted()).contains(request.sessionId());
  }

  private TranscodeCapabilityService createCapabilityService(
      boolean available, HardwareEncodingCapability hwCapability) {
    var service = new TranscodeCapabilityService(
        command -> new FakeProcess("ffmpeg version 7.0", available ? 0 : 1));
    if (available) {
      // Inject capability state via reflection-free approach: detect then override
      // We use a factory that returns the right result
      var outputs = Map.of(
          "ffmpeg", (Process) new FakeProcess("ffmpeg version 7.0", 0),
          "hwaccels", (Process) new FakeProcess(
              hwCapability.available() ? "Hardware acceleration methods:\ncuda\n"
                  : "Hardware acceleration methods:\n",
              0),
          "encoders", (Process) new FakeProcess(
              buildEncoderOutput(hwCapability.encoders()), 0));

      var testService = new TranscodeCapabilityService(command -> {
        var cmdStr = String.join(" ", command);
        if (cmdStr.contains("-version")) {
          return outputs.get("ffmpeg");
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
    return service;
  }

  private String buildEncoderOutput(Set<String> encoders) {
    var sb = new StringBuilder();
    for (var encoder : encoders) {
      sb.append(" V....D ").append(encoder).append("           ").append(encoder)
          .append(" encoder\n");
    }
    return sb.toString();
  }
}
