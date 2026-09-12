package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.StartProbeCommand;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("IntegrationTest")
@DisplayName("Worker Application Probe Capability Tests")
class WorkerProbeCapabilityAdmissionIT {

  @TempDir Path tempDir;

  @ParameterizedTest(name = "{displayName} [{0}]")
  @EnumSource(UnavailableBinary.class)
  @DisplayName("Should decline probe admission when the configured binary cannot execute")
  void shouldDeclineProbeAdmissionWhenTheConfiguredBinaryCannotExecute(
      UnavailableBinary unavailable) throws Exception {
    var binary = unavailableBinary(unavailable);
    assertThat(Files.isExecutable(binary)).isFalse();

    try (var controlPlane = new ProbeControlPlane()) {
      var fixture = ApplicationFixture.builder().port(controlPlane.port()).ffprobe(binary).build();
      var process = applicationProcess(fixture).start();
      try {
        var registered =
            controlPlane.registration.<StartupOutcome>thenApply(StartupOutcome.Registered::new);
        var exited =
            process
                .onExit()
                .<StartupOutcome>thenApply(worker -> new StartupOutcome.Exited(worker.exitValue()));
        var outcome = registered.applyToEither(exited, value -> value).get(10, TimeUnit.SECONDS);

        switch (outcome) {
          case StartupOutcome.Registered(var registration) ->
              assertThat(registration.getCapabilities().getProbeVersionsList())
                  .as(
                      "an application without an executable probe producer cannot claim probe support")
                  .isEmpty();
          case StartupOutcome.Exited(var code) -> {
            assertThat(code).as("startup refusal must be explicit").isNotZero();
            assertThat(Files.readString(tempDir.resolve("worker.log")))
                .as("startup refusal identifies the unavailable producer")
                .containsIgnoringCase("ffprobe");
          }
        }
      } finally {
        stop(process);
      }
    }
  }

  @Test
  @DisplayName("Should refuse startup when ffprobe reports a failing version check")
  void shouldRefuseStartupWhenFfprobeReportsAFailingVersionCheck() throws Exception {
    var binary = tempDir.resolve("failing-ffprobe");
    Files.writeString(binary, "#!/bin/sh\nexit 7\n");
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));

    try (var controlPlane = new ProbeControlPlane()) {
      var fixture = ApplicationFixture.builder().port(controlPlane.port()).ffprobe(binary).build();
      var process = applicationProcess(fixture).start();
      try {
        assertThat(process.waitFor(10, TimeUnit.SECONDS))
            .as("the worker refuses startup after the configured ffprobe fails its version check")
            .isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(Files.readString(tempDir.resolve("worker.log")))
            .contains("ffprobe", binary.toString(), "7");
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

    try (var controlPlane = new ProbeControlPlane()) {
      var configuredBinary = location == BinaryLocation.PATH ? binary.getFileName() : binary;
      var fixture =
          ApplicationFixture.builder().port(controlPlane.port()).ffprobe(configuredBinary).build();
      var process = applicationProcess(fixture).start();
      try {
        var registration = controlPlane.registration.get(10, TimeUnit.SECONDS);
        assertThat(registration.getCapabilities().getProbeVersionsList()).contains(1);
        controlPlane.startProbe(registration, request);

        var result = controlPlane.result.get(5, TimeUnit.SECONDS);
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

  private Path unavailableBinary(UnavailableBinary unavailable) throws Exception {
    var binary = tempDir.resolve("unavailable-ffprobe");
    if (unavailable == UnavailableBinary.NON_EXECUTABLE) {
      Files.writeString(binary, "#!/bin/sh\nexit 0\n");
      Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rw-------"));
    }

    return binary;
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
  private record ApplicationFixture(int port, Path ffprobe) {}

  private sealed interface StartupOutcome {
    record Registered(WorkerRegistration registration) implements StartupOutcome {}

    record Exited(int code) implements StartupOutcome {}
  }

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

  private static final class ProbeControlPlane
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase implements AutoCloseable {

    private final CompletableFuture<WorkerRegistration> registration = new CompletableFuture<>();
    private final CompletableFuture<ProbeAttemptResult> result = new CompletableFuture<>();
    private final Server server;
    private StreamObserver<EstablishWorkerSessionResponse> responses;

    private ProbeControlPlane() throws Exception {
      server = NettyServerBuilder.forPort(0).addService(this).build().start();
    }

    @Override
    public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      responses = responseObserver;
      return new StreamObserver<>() {
        @Override
        public void onNext(EstablishWorkerSessionRequest value) {
          if (value.hasRegistration()) {
            responses.onNext(
                EstablishWorkerSessionResponse.newBuilder()
                    .setSessionAccepted(
                        WorkerSessionAccepted.newBuilder()
                            .setWorkerSessionId(toProto(UUID.randomUUID())))
                    .build());
            registration.complete(value.getRegistration());
            return;
          }

          if (value.hasProbeResult()) {
            result.complete(value.getProbeResult());
          }
        }

        @Override
        public void onError(Throwable failure) {
          // Process termination closes this session during test cleanup.
        }

        @Override
        public void onCompleted() {
          responses.onCompleted();
        }
      };
    }

    private int port() {
      return server.getPort();
    }

    private void startProbe(WorkerRegistration registration, ProbeRequest request) {
      responses.onNext(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartProbe(
                  StartProbeCommand.newBuilder()
                      .setTarget(registration.getWorker())
                      .setRequest(request))
              .build());
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
