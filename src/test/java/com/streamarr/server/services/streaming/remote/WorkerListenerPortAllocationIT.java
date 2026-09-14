package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Tag("IntegrationTest")
@DisplayName("Worker Listener Port Allocation Tests")
@Execution(ExecutionMode.SAME_THREAD)
class WorkerListenerPortAllocationIT {

  private static final DockerImageName JDK_IMAGE =
      DockerImageName.parse(
          "eclipse-temurin:25-jdk@sha256:12e44624adee6808a36d962717e1656e0afeeeff5a100f9cb00e0136513558f0");

  @TempDir static Path tempDir;

  private static GenericContainer<?> container;

  @BeforeAll
  static void startIsolatedNetwork() throws Exception {
    var archive = bundleClasspath();
    // Linux tries odd ports first. This two-port range makes an early ephemeral bind take 45001.
    container =
        new GenericContainer<>(JDK_IMAGE)
            .withNetworkMode("none")
            .withCreateContainerCmdModifier(
                command ->
                    command
                        .getHostConfig()
                        .withSysctls(Map.of("net.ipv4.ip_local_port_range", "45000 45001")))
            .withCopyFileToContainer(MountableFile.forHostPath(archive), "/app/classpath.zip")
            .withWorkingDirectory("/app")
            .withCommand("sleep", "infinity");
    container.start();
    var unpacked = container.execInContainer("jar", "xf", "/app/classpath.zip");
    assertThat(unpacked.getExitCode()).as(unpacked.getStderr()).isZero();
  }

  @AfterAll
  static void stopIsolatedNetwork() {
    if (container != null) {
      container.stop();
    }
  }

  @ParameterizedTest(name = "fixed localhost port = {0}")
  @ValueSource(booleans = {true, false})
  @DisplayName(
      "Should start both listeners when a fixed port overlaps the ephemeral allocation range")
  void shouldStartBothListenersWhenAFixedPortOverlapsTheEphemeralAllocationRange(
      boolean fixedLocalhost) throws Exception {
    var result =
        container.execInContainer(
            "java",
            "@/app/java.args",
            WorkerListenerPortAllocationScenario.class.getName(),
            Boolean.toString(fixedLocalhost));

    assertThat(result.getExitCode())
        .as("Listener startup failed:%n%s%n%s", result.getStdout(), result.getStderr())
        .isZero();
    assertThat(result.getStdout())
        .contains("range=45000\t45001")
        .contains("localhost=" + (fixedLocalhost ? 45001 : 45000))
        .contains("mutualTls=" + (fixedLocalhost ? 45000 : 45001));
  }

  private static Path bundleClasspath() throws IOException {
    var archive = tempDir.resolve("classpath.zip");
    var containerEntries = new ArrayList<String>();
    try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
      zip.setLevel(Deflater.NO_COMPRESSION);
      for (var source : runtimeClasspath()) {
        var entry = "cp/" + containerEntries.size();
        containerEntries.add("/app/" + entry);
        copyClasspathEntry(zip, source, entry);
      }

      zip.putNextEntry(new ZipEntry("java.args"));
      zip.write(
          ("--enable-native-access=ALL-UNNAMED\n-cp\n" + String.join(":", containerEntries) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }

    return archive;
  }

  private static void copyClasspathEntry(ZipOutputStream zip, Path source, String entry)
      throws IOException {
    if (Files.isRegularFile(source)) {
      copyFile(zip, source, entry);
      return;
    }

    try (var files = Files.walk(source)) {
      for (var file : files.filter(Files::isRegularFile).toList()) {
        copyFile(
            zip,
            file,
            entry + "/" + source.relativize(file).toString().replace(File.separatorChar, '/'));
      }
    }
  }

  private static void copyFile(ZipOutputStream zip, Path source, String entry) throws IOException {
    zip.putNextEntry(new ZipEntry(entry));
    Files.copy(source, zip);
    zip.closeEntry();
  }

  private static List<Path> runtimeClasspath() {
    var classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    return Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
        .map(Path::of)
        .map(Path::toAbsolutePath)
        .distinct()
        .toList();
  }
}
