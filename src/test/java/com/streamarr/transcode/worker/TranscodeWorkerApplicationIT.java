package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.tlsResource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Application Integration Tests")
class TranscodeWorkerApplicationIT {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Should connect an environment-configured worker process over mTLS when starting a worker")
  void shouldConnectEnvironmentConfiguredWorkerProcessOverMtlsWhenStartingWorker()
      throws Exception {
    try (var server = server()) {
      server.start();
      var process = workerProcess(server.port()).start();
      try {
        await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(
                () ->
                    assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID))
                        .withFailMessage(() -> failureOutput(process))
                        .isTrue());
      } finally {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      }
    }
  }

  @Test
  @DisplayName("Should exit the worker process when the control plane disconnects")
  void shouldExitWorkerProcessWhenControlPlaneDisconnects() throws Exception {
    var server = server();
    server.start();
    var process = workerProcess(server.port()).start();
    try {
      await()
          .atMost(10, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID))
                      .withFailMessage(() -> failureOutput(process))
                      .isTrue());

      server.close();

      assertThat(process.waitFor(10, TimeUnit.SECONDS))
          .withFailMessage("Worker process remained alive after its control plane disconnected")
          .isTrue();
    } finally {
      server.close();
      process.destroyForcibly();
    }
  }

  @Test
  @DisplayName("Should exit immediately when FFmpeg is unavailable")
  void shouldExitImmediatelyWhenFfmpegIsUnavailable() throws Exception {
    var process = workerProcess(1, tempDir.resolve("missing-ffmpeg")).start();
    try {
      assertThat(process.waitFor(5, TimeUnit.SECONDS))
          .withFailMessage("Worker process remained alive without FFmpeg")
          .isTrue();
      var output = failureOutput(process);

      assertThat(process.exitValue()).isNotZero();
      assertThat(output).contains("FFmpeg is not available to the transcode worker");
    } finally {
      process.destroyForcibly();
    }
  }

  private ProcessBuilder workerProcess(int port) throws Exception {
    return workerProcess(port, fakeFfmpeg());
  }

  private ProcessBuilder workerProcess(int port, Path ffmpeg) throws Exception {
    var process = new ProcessBuilder(workerCommand()).redirectErrorStream(true);
    process
        .environment()
        .putAll(
            Map.of(
                "TRANSCODE_WORKER_CONTROL_PLANE_HOST", "localhost",
                "TRANSCODE_WORKER_CONTROL_PLANE_PORT", String.valueOf(port),
                "TRANSCODE_WORKER_ID", WORKER_ID.toString(),
                "TRANSCODE_WORKER_SOURCE_NAMESPACE_ID", SOURCE_NAMESPACE_ID.toString(),
                "TRANSCODE_WORKER_SOURCE_ROOT", tempDir.toString(),
                "TRANSCODE_WORKER_TLS_CERTIFICATE", tlsResource("worker-cert.pem").toString(),
                "TRANSCODE_WORKER_TLS_PRIVATE_KEY", tlsResource("worker-key.fixture").toString(),
                "TRANSCODE_WORKER_TLS_TRUST_BUNDLE", tlsResource("ca-cert.pem").toString(),
                "TRANSCODE_WORKER_FFMPEG_PATH", ffmpeg.toString()));
    return process;
  }

  private List<String> workerCommand() {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
        .findFirst()
        .ifPresent(command::add);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add("com.streamarr.transcode.worker.TranscodeWorkerApplication");
    return command;
  }

  private Path fakeFfmpeg() throws Exception {
    var executable = tempDir.resolve("ffmpeg");
    Files.writeString(executable, "#!/bin/sh\nexit 0\n");
    assertThat(executable.toFile().setExecutable(true)).isTrue();
    return executable;
  }

  private WorkerSessionServer server() throws URISyntaxException {
    return new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
  }

  private String failureOutput(Process process) {
    if (process.isAlive()) {
      return "Worker process did not connect before the timeout";
    }
    try {
      return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      return e.getMessage();
    }
  }
}
