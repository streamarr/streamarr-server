package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.tlsResource;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.worker.support.WorkerApplicationControlPlane;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Application Integration Tests")
class TranscodeWorkerApplicationIT {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should return a source failure when a POSIX worker receives a Unicode source")
  void shouldReturnASourceFailureWhenAPosixWorkerReceivesAUnicodeSource() throws Exception {
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(1)
            .setSource(
                MediaSourceRef.newBuilder()
                    .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                    .setRelativeKey("caf\u00e9.mkv"))
            .build();

    try (var controlPlane = WorkerApplicationControlPlane.builder().build()) {
      var fixture =
          ApplicationFixture.builder()
              .port(controlPlane.port())
              .ffprobe(versionOnlyFfprobe())
              .build();
      var processBuilder = applicationProcess(fixture);
      processBuilder.environment().put("LC_ALL", "C");
      processBuilder.environment().put("LANG", "C");
      var process = processBuilder.start();
      try {
        controlPlane.awaitRegistration();
        controlPlane.startProbe(request);

        var result = controlPlane.awaitResult();
        assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
        assertThat(result.getProbeVersion()).isEqualTo(request.getProbeVersion());
        assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE);
      } finally {
        stop(process);
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{0}]")
  @EnumSource(UnavailableBinary.class)
  @DisplayName("Should decline probe admission when the configured binary cannot execute")
  void shouldDeclineProbeAdmissionWhenTheConfiguredBinaryCannotExecute(
      UnavailableBinary unavailable) throws Exception {
    var binary = unavailableBinary(unavailable);
    assertThat(Files.isExecutable(binary)).isFalse();

    try (var controlPlane = WorkerApplicationControlPlane.builder().build()) {
      var fixture = ApplicationFixture.builder().port(controlPlane.port()).ffprobe(binary).build();
      var process = applicationProcess(fixture).start();
      try {
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("startup refuses an unavailable producer").isNotZero();
        assertThat(Files.readString(tempDir.resolve("worker.log")))
            .contains("ffprobe", binary.toString());
        assertThat(controlPlane.registrationsAfterExit()).isEmpty();
      } finally {
        stop(process);
      }
    }
  }

  @Test
  @DisplayName("Should refuse startup when ffprobe reports a failing version check")
  void shouldRefuseStartupWhenFfprobeReportsAFailingVersionCheck() throws Exception {
    var binary = tempDir.resolve("failing-ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        [ "$#" -eq 1 ] && [ "$1" = "-version" ] || exit 99
        exit 7
        """);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));

    try (var controlPlane = WorkerApplicationControlPlane.builder().build()) {
      var fixture = ApplicationFixture.builder().port(controlPlane.port()).ffprobe(binary).build();
      var process = applicationProcess(fixture).start();
      try {
        assertThat(process.waitFor(10, TimeUnit.SECONDS))
            .as("the worker refuses startup after the configured ffprobe fails its version check")
            .isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(Files.readString(tempDir.resolve("worker.log")))
            .contains("ffprobe", binary.toString(), "exited with code 7");
        assertThat(controlPlane.registrationsAfterExit()).isEmpty();
      } finally {
        stop(process);
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{0}]")
  @EnumSource(BinaryLocation.class)
  @DisplayName("Should produce media with the configured binary when the application starts")
  void shouldProduceMediaWithTheConfiguredBinaryWhenTheApplicationStarts(BinaryLocation location)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var binary = tempDir.resolve("working-ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        if [ "$#" -eq 1 ]; then
          [ "$1" = "-version" ] || exit 99
          exit 0
        fi
        echo '{"streams":[{"index":0,"codec_type":"video","codec_name":"configured-codec"}]}'
        """);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(1)
            .setSource(
                MediaSourceRef.newBuilder()
                    .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                    .setRelativeKey("movie.mkv"))
            .build();

    try (var controlPlane = WorkerApplicationControlPlane.builder().build()) {
      var configuredBinary = location == BinaryLocation.PATH ? binary.getFileName() : binary;
      var fixture =
          ApplicationFixture.builder().port(controlPlane.port()).ffprobe(configuredBinary).build();
      var process = applicationProcess(fixture).start();
      try {
        var registration = controlPlane.awaitRegistration();
        assertThat(registration.getCapabilities().getProbeVersionsList()).contains(1);
        controlPlane.startProbe(request);

        var result = controlPlane.awaitResult();
        assertThat(result.hasMedia()).isTrue();
        assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
        assertThat(result.getMedia().getStreamsList())
            .singleElement()
            .satisfies(stream -> assertThat(stream.getCodec()).isEqualTo("configured-codec"));
      } finally {
        stop(process);
      }
    }
  }

  @Test
  @DisplayName(
      "Should connect an environment-configured worker process over mTLS when starting a worker")
  void shouldConnectEnvironmentConfiguredWorkerProcessOverMtlsWhenStartingWorker()
      throws Exception {
    try (var controlPlane = WorkerApplicationControlPlane.builder().mutualTls(true).build()) {
      var fixture =
          ApplicationFixture.builder()
              .port(controlPlane.port())
              .ffprobe(versionOnlyFfprobe())
              .mutualTls(true)
              .build();
      var processBuilder = applicationProcess(fixture);
      var workerId = UUID.fromString(processBuilder.environment().get("TRANSCODE_WORKER_ID"));
      var process = processBuilder.start();
      try {
        var registration = controlPlane.awaitRegistration();

        assertThat(registration.getWorker().getWorkerId()).isEqualTo(toProto(workerId));
        assertThat(registration.getCapabilities().getSourceNamespaceIdsList())
            .containsExactly(toProto(SOURCE_NAMESPACE_ID));
      } finally {
        stop(process);
      }
    }
  }

  @Test
  @DisplayName("Should exit the worker process when the control plane disconnects")
  void shouldExitWorkerProcessWhenControlPlaneDisconnects() throws Exception {
    try (var controlPlane = WorkerApplicationControlPlane.builder().mutualTls(true).build()) {
      var fixture =
          ApplicationFixture.builder()
              .port(controlPlane.port())
              .ffprobe(versionOnlyFfprobe())
              .mutualTls(true)
              .build();
      var process = applicationProcess(fixture).start();
      try {
        controlPlane.awaitRegistration();
        controlPlane.close();

        assertThat(process.waitFor(10, TimeUnit.SECONDS))
            .as("the application exits after its control plane disconnects")
            .isTrue();
      } finally {
        stop(process);
      }
    }
  }

  @Test
  @DisplayName("Should exit immediately when FFmpeg is unavailable")
  void shouldExitImmediatelyWhenFfmpegIsUnavailable() throws Exception {
    var fixture = ApplicationFixture.builder().port(1).ffprobe(versionOnlyFfprobe()).build();
    var processBuilder = applicationProcess(fixture);
    processBuilder
        .environment()
        .put("TRANSCODE_WORKER_FFMPEG_PATH", tempDir.resolve("missing-ffmpeg").toString());
    var process = processBuilder.start();
    try {
      assertThat(process.waitFor(5, TimeUnit.SECONDS))
          .as("startup refuses missing FFmpeg")
          .isTrue();
      assertThat(process.exitValue()).isNotZero();
      assertThat(Files.readString(tempDir.resolve("worker.log")))
          .contains("FFmpeg is not available to the transcode worker: FFmpeg not found");
    } finally {
      stop(process);
    }
  }

  private Path versionOnlyFfprobe() throws Exception {
    var binary = tempDir.resolve("ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        [ "$#" -eq 1 ] && [ "$1" = "-version" ]
        """);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
    return binary;
  }

  private Path unavailableBinary(UnavailableBinary unavailable) throws Exception {
    var binary = tempDir.resolve("unavailable-ffprobe");
    if (unavailable == UnavailableBinary.NON_EXECUTABLE) {
      Files.writeString(binary, "#!/bin/sh\nexit 0\n");
      Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rw-------"));
    }

    return binary;
  }

  @Test
  @DisplayName("Should wait for successful probe validation when registering the application")
  void shouldWaitForSuccessfulProbeValidationWhenRegisteringTheApplication() throws Exception {
    var signals = Files.createDirectory(tempDir.resolve("signals"));
    var pidFile = tempDir.resolve("probe.pid");
    var release = tempDir.resolve("release.fifo");
    var fifo = new ProcessBuilder("mkfifo", release.toString()).start();
    try {
      assertThat(fifo.waitFor(5, TimeUnit.SECONDS)).isTrue();
      assertThat(fifo.exitValue()).isZero();
    } finally {
      stop(fifo);
    }

    var binary = tempDir.resolve("held-version-ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        [ "$#" -eq 1 ] && [ "$1" = "-version" ] || exit 99
        printf '%s' "$$" > "$PROBE_PID_FILE"
        /bin/mkdir "$PROBE_READY_DIR/started"
        read release < "$PROBE_RELEASE_FIFO"
        exit 0
        """);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));

    try (var controlPlane =
            WorkerApplicationControlPlane.builder()
                .producerAdmitted(() -> validationHasExited(pidFile))
                .build();
        var watcher = signals.getFileSystem().newWatchService()) {
      signals.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
      var fixture = ApplicationFixture.builder().port(controlPlane.port()).ffprobe(binary).build();
      var processBuilder = applicationProcess(fixture);
      processBuilder.environment().put("PROBE_PID_FILE", pidFile.toString());
      processBuilder.environment().put("PROBE_READY_DIR", signals.toString());
      processBuilder.environment().put("PROBE_RELEASE_FIFO", release.toString());
      var process = processBuilder.start();
      try {
        assertThat(watcher.poll(10, TimeUnit.SECONDS))
            .as("the version check reaches its held wait")
            .isNotNull();
        assertThat(validationHasExited(pidFile)).isFalse();
        var releasing =
            new ProcessBuilder(
                    "/bin/sh",
                    "-c",
                    "printf 'release\\n' > \"$1\"",
                    "release-probe",
                    release.toString())
                .start();
        try {
          assertThat(releasing.waitFor(5, TimeUnit.SECONDS)).isTrue();
          assertThat(releasing.exitValue()).isZero();
        } finally {
          stop(releasing);
        }

        assertThat(controlPlane.awaitRegistration().getCapabilities().getProbeVersionsList())
            .containsExactly(1);
        assertThat(controlPlane.wasAdmittedAtRegistration())
            .as("registration occurs after the held version process has exited successfully")
            .isTrue();
      } finally {
        stop(process);
        stopNativeProcess(pidFile);
      }
    }
  }

  private static boolean validationHasExited(Path pidFile) {
    if (!Files.exists(pidFile)) {
      return false;
    }

    try {
      return ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
          .map(process -> !process.isAlive())
          .orElse(true);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Test
  @DisplayName("Should preserve interruption and stop ffprobe when startup is interrupted")
  void shouldPreserveInterruptionAndStopFfprobeWhenStartupIsInterrupted() throws Exception {
    var signals = Files.createDirectory(tempDir.resolve("signals"));
    var pidFile = tempDir.resolve("probe.pid");
    var binary = tempDir.resolve("waiting-ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        printf '%s' "$$" > "$PROBE_PID_FILE"
        /bin/mkdir "$PROBE_READY_DIR/started"
        exec /bin/sleep 300
        """);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
    var fixture = ApplicationFixture.builder().port(1).ffprobe(binary).build();
    var processBuilder = applicationProcess(fixture);
    var command = processBuilder.command();
    command.set(command.size() - 1, InterruptedStartup.class.getName());
    processBuilder.environment().put("PROBE_PID_FILE", pidFile.toString());
    processBuilder.environment().put("PROBE_READY_DIR", signals.toString());
    var process = processBuilder.start();
    try {
      assertThat(process.waitFor(10, TimeUnit.SECONDS))
          .as("interrupted startup finishes after its native process has terminated")
          .isTrue();
      assertThat(process.exitValue()).isZero();
      assertThat(Files.readString(tempDir.resolve("worker.log")))
          .contains("startup-interrupted=true");
      var nativeProcess = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)));
      assertThat(nativeProcess.map(ProcessHandle::isAlive).orElse(false)).isFalse();
    } finally {
      stop(process);
      stopNativeProcess(pidFile);
    }
  }

  private ProcessBuilder applicationProcess(ApplicationFixture fixture) throws Exception {
    var ffmpeg = tempDir.resolve("ffmpeg");
    Files.writeString(
        ffmpeg,
        """
        #!/bin/sh
        case "$*" in
          *"muxer=hls"*) printf '%s\\n' '  -hls_segment_options <dictionary>' ;;
        esac
        exit 0
        """);
    Files.setPosixFilePermissions(ffmpeg, PosixFilePermissions.fromString("rwx------"));
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
        .findFirst()
        .ifPresent(command::add);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(TranscodeWorkerApplication.class.getName());
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(tempDir.resolve("worker.log").toFile());
    process.environment().keySet().removeIf(key -> key.startsWith("TRANSCODE_WORKER_"));
    process
        .environment()
        .put(
            "PATH",
            tempDir + System.getProperty("path.separator") + process.environment().get("PATH"));
    process
        .environment()
        .putAll(
            Map.ofEntries(
                Map.entry("TRANSCODE_WORKER_CONTROL_PLANE_HOST", "localhost"),
                Map.entry("TRANSCODE_WORKER_CONTROL_PLANE_PORT", String.valueOf(fixture.port())),
                Map.entry("TRANSCODE_WORKER_ID", UUID.randomUUID().toString()),
                Map.entry("TRANSCODE_WORKER_SOURCE_NAMESPACE_ID", SOURCE_NAMESPACE_ID.toString()),
                Map.entry("TRANSCODE_WORKER_SOURCE_ROOT", tempDir.toString()),
                Map.entry(
                    "TRANSCODE_WORKER_SEGMENT_BASE_PATH", tempDir.resolve("segments").toString()),
                Map.entry("TRANSCODE_WORKER_PLAINTEXT", "true"),
                Map.entry("TRANSCODE_WORKER_HEALTH_PORT", "0"),
                Map.entry("TRANSCODE_WORKER_FFMPEG_PATH", ffmpeg.toString()),
                Map.entry("TRANSCODE_WORKER_FFPROBE_PATH", fixture.ffprobe().toString())));
    if (fixture.mutualTls()) {
      process.environment().put("TRANSCODE_WORKER_PLAINTEXT", "false");
      process
          .environment()
          .put("TRANSCODE_WORKER_TLS_CERTIFICATE", tlsResource("worker-cert.pem").toString());
      process
          .environment()
          .put("TRANSCODE_WORKER_TLS_PRIVATE_KEY", tlsResource("worker-key.fixture").toString());
      process
          .environment()
          .put("TRANSCODE_WORKER_TLS_TRUST_BUNDLE", tlsResource("ca-cert.pem").toString());
    }

    return process;
  }

  private static void stop(Process process) throws InterruptedException {
    process.destroy();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly();
    }

    assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("the worker process terminates").isTrue();
  }

  private static void stopNativeProcess(Path pidFile) throws Exception {
    if (!Files.exists(pidFile)) {
      return;
    }

    var nativeProcess = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)));
    if (nativeProcess.isEmpty()) {
      return;
    }

    var process = nativeProcess.orElseThrow();
    process.destroyForcibly();
    process.onExit().get(5, TimeUnit.SECONDS);
  }

  private enum UnavailableBinary {
    MISSING,
    NON_EXECUTABLE
  }

  private enum BinaryLocation {
    ABSOLUTE_PATH,
    PATH
  }

  @Builder
  private record ApplicationFixture(int port, Path ffprobe, boolean mutualTls) {}

  public static final class InterruptedStartup {

    private InterruptedStartup() {}

    public static void main(String[] args) throws Exception {
      var signals = Path.of(System.getenv("PROBE_READY_DIR"));
      var startup = Thread.currentThread();
      try (var watcher = signals.getFileSystem().newWatchService()) {
        signals.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
        var interrupter =
            Thread.ofPlatform()
                .daemon()
                .start(
                    () -> {
                      try {
                        watcher.take();
                        startup.interrupt();
                      } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                      }
                    });
        try {
          TranscodeWorkerApplication.main(args);
          throw new AssertionError("Startup returned without reporting interruption");
        } catch (InterruptedException _) {
          System.out.println("startup-interrupted=" + Thread.currentThread().isInterrupted());
        } finally {
          interrupter.interrupt();
        }
      }
    }
  }
}
