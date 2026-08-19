package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Procfile Tests")
// The Procfile is a literal external contract: launcher class names appear as text.
@SuppressWarnings("checkstyle:fullyQualifiedName")
class ProcfileTest {

  private static final String NATIVE_ACCESS = "--enable-native-access=ALL-UNNAMED";

  @Test
  @DisplayName("Should ship separate server and transcode worker process types")
  void shouldShipSeparateServerAndTranscodeWorkerProcessTypes() throws IOException {
    var processes = processes();

    assertThat(processes)
        .containsOnlyKeys("web", "worker")
        .containsEntry(
            "worker",
            "java -Dloader.main=com.streamarr.transcode.worker.TranscodeWorkerApplication"
                + " org.springframework.boot.loader.launch.PropertiesLauncher")
        .hasEntrySatisfying(
            "web",
            web -> assertThat(web).endsWith("org.springframework.boot.loader.launch.JarLauncher"));
  }

  @Test
  @DisplayName("Should enable native access for the web process when it loads the Cedar engine")
  void shouldEnableNativeAccessForWebProcessWhenItLoadsCedarEngine() throws IOException {
    assertThat(processes().get("web")).startsWith("java " + NATIVE_ACCESS + " ");
  }

  @Test
  @DisplayName("Should enable native access for every JVM the build starts")
  void shouldEnableNativeAccessForEveryJvmTheBuildStarts() throws IOException {
    var pom = Files.readString(Path.of("pom.xml"));

    assertThat(pom)
        .contains(
            "<native.access.args>" + NATIVE_ACCESS + "</native.access.args>",
            "<argLine>${surefire.jacoco.args} ${native.access.args}</argLine>",
            "<argLine>${failsafe.jacoco.args} ${native.access.args}</argLine>",
            "<jvmArguments>${native.access.args}</jvmArguments>");
  }

  private static Map<String, String> processes() throws IOException {
    return Files.readAllLines(Path.of("Procfile")).stream()
        .filter(line -> !line.isBlank())
        .map(line -> line.split(": ", 2))
        .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
  }
}
