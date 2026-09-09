package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class FfmpegTestToolchain {

  private FfmpegTestToolchain() {}

  static void requireNode() throws IOException, InterruptedException {
    var expected = Files.readString(Path.of("buildpacks/ffmpeg/.nvmrc")).trim();
    var instructions =
        "FFmpeg tooling requires Node.js %1$s; run nvm install %1$s && nvm use %1$s"
            .formatted(expected);
    try {
      var process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
      var version = new String(process.getInputStream().readAllBytes()).trim();
      assertThat(process.waitFor()).as("Unable to read Node.js version").isZero();
      assertThat(version).as(instructions).startsWith("v" + expected.split("\\.")[0] + ".");
    } catch (IOException cause) {
      throw new IOException(instructions, cause);
    }
  }
}
