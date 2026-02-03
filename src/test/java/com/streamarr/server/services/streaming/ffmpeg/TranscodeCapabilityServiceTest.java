package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class TranscodeCapabilityServiceTest {

  @Test
  @DisplayName("shouldReportUnavailableWhenFfmpegNotInstalled")
  void shouldReportUnavailableWhenFfmpegNotInstalled() {
    var service = new TranscodeCapabilityService(command -> createProcess("", 1));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isFalse();
  }

  @Test
  @DisplayName("shouldReportCpuOnlyWhenNoGpuDetected")
  void shouldReportCpuOnlyWhenNoGpuDetected() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\n", 0),
            "encoders", createProcess("", 0));

    var service = new TranscodeCapabilityService(command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isTrue();
    assertThat(service.getGpuCapability().available()).isFalse();
  }

  @Test
  @DisplayName("shouldDetectNvencGpuCapability")
  void shouldDetectNvencGpuCapability() {
    var encoderOutput =
        """
        V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
        V....D hevc_nvenc           NVIDIA NVENC hevc encoder (codec hevc)
        V....D av1_nvenc            NVIDIA NVENC av1 encoder (codec av1)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service = new TranscodeCapabilityService(command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isTrue();
    assertThat(service.getGpuCapability().available()).isTrue();
    assertThat(service.getGpuCapability().encoders())
        .contains("h264_nvenc", "hevc_nvenc", "av1_nvenc");
  }

  @Test
  @DisplayName("shouldResolveH264EncoderToHardwareWhenAvailable")
  void shouldResolveH264EncoderToHardwareWhenAvailable() {
    var encoderOutput =
        """
        V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service = new TranscodeCapabilityService(command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.resolveEncoder("h264")).isEqualTo("h264_nvenc");
  }

  @Test
  @DisplayName("shouldResolveAv1EncoderToSoftwareWhenNoHardware")
  void shouldResolveAv1EncoderToSoftwareWhenNoHardware() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\n", 0),
            "encoders", createProcess("", 0));

    var service = new TranscodeCapabilityService(command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.resolveEncoder("av1")).isEqualTo("libsvtav1");
  }

  @Test
  @DisplayName("shouldResolveH264EncoderToSoftwareWhenNoHardware")
  void shouldResolveH264EncoderToSoftwareWhenNoHardware() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\n", 0),
            "encoders", createProcess("", 0));

    var service = new TranscodeCapabilityService(command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.resolveEncoder("h264")).isEqualTo("libx264");
  }

  @Test
  @DisplayName("shouldDetectQsvCapability")
  void shouldDetectQsvCapability() {
    var encoderOutput =
        """
        V....D h264_qsv             H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (Intel Quick Sync Video acceleration) (codec h264)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\nqsv\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service = new TranscodeCapabilityService(command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.getGpuCapability().available()).isTrue();
    assertThat(service.resolveEncoder("h264")).isEqualTo("h264_qsv");
  }

  private Process resolveProcess(String[] command, Map<String, Process> outputs) {
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
    return createProcess("", 1);
  }

  private Process createProcess(String stdout, int exitCode) {
    return new FakeProcess(stdout, exitCode);
  }
}
