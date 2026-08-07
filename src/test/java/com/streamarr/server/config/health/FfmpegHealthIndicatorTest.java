package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

@Tag("UnitTest")
@DisplayName("FFmpeg Health Indicator Tests")
class FfmpegHealthIndicatorTest {

  @Test
  @DisplayName("Should report UP with GPU details when FFmpeg and GPU are available")
  void shouldReportUpWithGpuDetailsWhenFfmpegAndGpuAreAvailable() {
    var service =
        capabilityService().encoders(Set.of("h264_nvenc", "av1_nvenc")).accelerator("cuda").build();

    var indicator = new FfmpegHealthIndicator(service);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("hardwareEncoding", true);
    assertThat(health.getDetails()).containsKey("encoders");
    assertThat(health.getDetails()).containsEntry("accelerator", "cuda");
  }

  @Test
  @DisplayName("Should report UP with CPU only when FFmpeg is available without GPU")
  void shouldReportUpWithCpuOnlyWhenFfmpegIsAvailableWithoutGpu() {
    var service = capabilityService().build();

    var indicator = new FfmpegHealthIndicator(service);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("hardwareEncoding", false);
  }

  @Test
  @DisplayName("Should report DOWN when FFmpeg is unavailable")
  void shouldReportDownWhenFfmpegIsUnavailable() {
    var service = capabilityService().ffmpegAvailable(false).build();

    var indicator = new FfmpegHealthIndicator(service);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  @DisplayName("Should explain missing HLS capability when FFmpeg is installed but incompatible")
  void shouldExplainMissingHlsCapabilityWhenFfmpegIsInstalledButIncompatible() {
    var service =
        new TranscodeCapabilityService(
            "ffmpeg",
            command -> {
              if (String.join(" ", command).contains("muxer=hls")) {
                return new FakeTestProcess("Muxer hls [Apple HTTP Live Streaming]:", 0);
              }
              return new FakeTestProcess("ffmpeg version 4.4.2", 0);
            });
    service.detectCapabilities();
    var indicator = new FfmpegHealthIndicator(service);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("reason", "Missing hls_segment_options");
  }

  private CapabilityServiceFixture capabilityService() {
    return new CapabilityServiceFixture();
  }

  private static final class CapabilityServiceFixture {

    private boolean ffmpegAvailable = true;
    private Set<String> encoders = Set.of();
    private String accelerator = "";

    private CapabilityServiceFixture ffmpegAvailable(boolean available) {
      ffmpegAvailable = available;
      return this;
    }

    private CapabilityServiceFixture encoders(Set<String> availableEncoders) {
      encoders = Set.copyOf(availableEncoders);
      return this;
    }

    private CapabilityServiceFixture accelerator(String availableAccelerator) {
      accelerator = availableAccelerator;
      return this;
    }

    private TranscodeCapabilityService build() {
      var encoderOutput = new StringBuilder();
      for (var encoder : encoders) {
        encoderOutput
            .append(" V....D ")
            .append(encoder)
            .append("           ")
            .append(encoder)
            .append("\n");
      }

      var service =
          new TranscodeCapabilityService(
              "ffmpeg",
              command -> {
                var commandLine = String.join(" ", command);
                if (commandLine.contains("-version")) {
                  return new FakeTestProcess("ffmpeg version 7.0", ffmpegAvailable ? 0 : 1);
                }
                if (commandLine.contains("muxer=hls")) {
                  return new FakeTestProcess("-hls_segment_options <dictionary>", 0);
                }
                if (commandLine.contains("-hwaccels")) {
                  var output = "Hardware acceleration methods:\n";
                  if (!encoders.isEmpty()) {
                    output += accelerator + "\n";
                  }
                  return new FakeTestProcess(output, 0);
                }
                if (commandLine.contains("-encoders")) {
                  return new FakeTestProcess(encoderOutput.toString(), 0);
                }
                return new FakeTestProcess("", 1);
              });
      service.detectCapabilities();
      return service;
    }
  }

  private static class FakeTestProcess extends Process {
    private final InputStream inputStream;
    private final int exitCode;

    FakeTestProcess(String stdout, int exitCode) {
      this.inputStream = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
      this.exitCode = exitCode;
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return inputStream;
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      return exitCode;
    }

    @Override
    public int exitValue() {
      return exitCode;
    }

    @Override
    public void destroy() {
      // no-op for test fake
    }
  }
}
