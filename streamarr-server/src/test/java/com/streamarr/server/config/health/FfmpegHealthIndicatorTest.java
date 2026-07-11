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
  @DisplayName("Should report UP with GPU details when FFmpeg and GPU available")
  void shouldReportUpWithGpuDetails() {
    var service = createCapabilityService(true, true, Set.of("h264_nvenc", "av1_nvenc"), "cuda");

    var indicator = new FfmpegHealthIndicator(service);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("hardwareEncoding", true);
    assertThat(health.getDetails()).containsKey("encoders");
    assertThat(health.getDetails()).containsEntry("accelerator", "cuda");
  }

  @Test
  @DisplayName("Should report UP with CPU-only note when FFmpeg available but no GPU")
  void shouldReportUpWithCpuOnly() {
    var service = createCapabilityService(true, false, Set.of(), "");

    var indicator = new FfmpegHealthIndicator(service);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("hardwareEncoding", false);
  }

  @Test
  @DisplayName("Should report DOWN when FFmpeg unavailable")
  void shouldReportDownWhenUnavailable() {
    var service = createCapabilityService(false, false, Set.of(), "");

    var indicator = new FfmpegHealthIndicator(service);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  private TranscodeCapabilityService createCapabilityService(
      boolean ffmpegAvailable, boolean gpuAvailable, Set<String> encoders, String accelerator) {
    var encoderOutput = new StringBuilder();
    for (var enc : encoders) {
      encoderOutput.append(" V....D ").append(enc).append("           ").append(enc).append("\n");
    }

    var service =
        new TranscodeCapabilityService(
            "ffmpeg",
            command -> {
              var cmdStr = String.join(" ", command);
              if (cmdStr.contains("-version")) {
                return new FakeTestProcess("ffmpeg version 7.0", ffmpegAvailable ? 0 : 1);
              }
              if (cmdStr.contains("-hwaccels")) {
                return new FakeTestProcess(
                    gpuAvailable
                        ? "Hardware acceleration methods:\n" + accelerator + "\n"
                        : "Hardware acceleration methods:\n",
                    0);
              }
              if (cmdStr.contains("-encoders")) {
                return new FakeTestProcess(encoderOutput.toString(), 0);
              }
              return new FakeTestProcess("", 1);
            });
    service.detectCapabilities();
    return service;
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
