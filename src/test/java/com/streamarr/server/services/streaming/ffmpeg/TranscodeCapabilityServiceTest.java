package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Transcode Capability Service Tests")
class TranscodeCapabilityServiceTest {

  @Test
  @DisplayName("Should report unavailable when FFmpeg not installed")
  void shouldReportUnavailableWhenFfmpegNotInstalled() {
    var service = new TranscodeCapabilityService(command -> createProcess("", 1));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isFalse();
  }

  @Test
  @DisplayName("Should report CPU only when no GPU detected")
  void shouldReportCpuOnlyWhenNoGpuDetected() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\n", 0),
            "encoders", createProcess("", 0));

    var service = new TranscodeCapabilityService(command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isTrue();
    assertThat(service.getHardwareEncodingCapability().available()).isFalse();
  }

  @Test
  @DisplayName("Should detect NVENC capability when NVENC encoders are available")
  void shouldDetectNvencCapabilityWhenNvencEncodersAreAvailable() {
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
    assertThat(service.getHardwareEncodingCapability().available()).isTrue();
    assertThat(service.getHardwareEncodingCapability().encoders())
        .contains("h264_nvenc", "hevc_nvenc", "av1_nvenc");
  }

  @Test
  @DisplayName("Should resolve H264 encoder to hardware when available")
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
  @DisplayName("Should resolve AV1 encoder to software when no hardware")
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
  @DisplayName("Should resolve H264 encoder to software when no hardware")
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
  @DisplayName("Should detect QSV capability when QSV encoder is available")
  void shouldDetectQsvCapabilityWhenQsvEncoderIsAvailable() {
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

    assertThat(service.getHardwareEncodingCapability().available()).isTrue();
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

  @Test
  @DisplayName("Should resolve HEVC encoder to hardware when NVENC available")
  void shouldResolveHevcEncoderToHardwareWhenNvencAvailable() {
    var encoderOutput =
        """
        V....D hevc_nvenc           NVIDIA NVENC hevc encoder (codec hevc)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service = new TranscodeCapabilityService(command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.resolveEncoder("hevc")).isEqualTo("hevc_nvenc");
  }

  @Test
  @DisplayName("Should resolve to software default when codec family is unknown")
  void shouldResolveToSoftwareDefaultWhenCodecFamilyIsUnknown() {
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

    assertThat(service.resolveEncoder("vp9")).isEqualTo("libx264");
  }

  @Test
  @DisplayName("Should fallback to software when hardware encoder does not match codec")
  void shouldFallbackToSoftwareWhenHardwareEncoderDoesNotMatchCodec() {
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

    assertThat(service.resolveEncoder("av1")).isEqualTo("libsvtav1");
  }

  @Test
  @DisplayName("Should detect accelerator string when GPU present")
  void shouldDetectAcceleratorStringWhenGpuPresent() {
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

    assertThat(service.getHardwareEncodingCapability().accelerator()).isEqualTo("cuda");
  }

  @Test
  @DisplayName("Should concatenate multiple accelerators with comma")
  void shouldConcatenateMultipleAcceleratorsWithComma() {
    var encoderOutput =
        """
        V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\ncuvid\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service = new TranscodeCapabilityService(command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.getHardwareEncodingCapability().accelerator()).isEqualTo("cuda,cuvid");
  }

  @Test
  @DisplayName("Should detect VideoToolbox encoder on macOS")
  void shouldDetectVideoToolboxEncoderOnMacOs() {
    var encoderOutput =
        """
        V....D h264_videotoolbox    VideoToolbox H.264 Encoder (codec h264)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\nvideotoolbox\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service = new TranscodeCapabilityService(command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.getHardwareEncodingCapability().encoders()).contains("h264_videotoolbox");
    assertThat(service.resolveEncoder("h264")).isEqualTo("h264_videotoolbox");
  }

  private Process createProcess(String stdout, int exitCode) {
    return new FakeProcess(stdout, exitCode);
  }
}
