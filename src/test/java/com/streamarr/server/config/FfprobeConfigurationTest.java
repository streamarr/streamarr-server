package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Ffprobe configuration tests")
class FfprobeConfigurationTest {

  @TempDir Path directory;

  @Test
  @DisplayName(
      "Should return a terminal media failure when the configured producer reports invalid input")
  void shouldReturnATerminalMediaFailureWhenTheConfiguredProducerReportsInvalidInput()
      throws IOException {
    var executable = directory.resolve("ffprobe");
    Files.writeString(
        executable,
        """
        #!/bin/sh
        for argument in "$@"; do
          if [ "$argument" = "-show_error" ]; then
            printf '%s\n' '{"error":{"code":-1094995529,"string":"Invalid data found when processing input"}}'
            exit 1
          fi
        done
        printf '%s\n' '{}'
        exit 1
        """);
    assertThat(executable.toFile().setExecutable(true)).isTrue();
    var service =
        new StreamingConfig()
            .ffprobeService(new ObjectMapper(), new FfmpegPaths("unused", executable.toString()));

    assertThat(service.probe(directory.resolve("corrupt.mkv")))
        .isEqualTo(new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA));
  }
}
