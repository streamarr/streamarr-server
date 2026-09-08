package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class FfmpegTestToolchain {

  private FfmpegTestToolchain() {}

  static void requireNode() throws IOException, InterruptedException {
    var expected = Files.readString(Path.of(".nvmrc")).trim();
    try {
      var process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
      var version = new String(process.getInputStream().readAllBytes()).trim();
      assertThat(process.waitFor()).as("Unable to read Node.js version").isZero();
      assertThat(version)
          .as("FFmpeg tooling requires Node.js %s; run nvm install && nvm use", expected)
          .startsWith("v" + expected.split("\\.")[0] + ".");
    } catch (IOException cause) {
      throw new IOException(
          "FFmpeg tooling requires Node.js "
              + expected
              + "; run nvm install && nvm use before ./mvnw verify",
          cause);
    }
  }
}
