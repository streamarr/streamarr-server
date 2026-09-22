package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

@Slf4j
public final class WorkerContainerFixture implements AutoCloseable {

  private final WorkerSessionServer workerSessions;
  private final UUID sourceNamespaceId;
  private final Path sourceRoot;
  private final String ffmpegScript;
  private final String ffprobeScript;
  private final int availableSlots;
  private final String filenameLocale;
  private final UUID workerId = UUID.randomUUID();
  private GenericContainer<?> container;

  @Builder
  private WorkerContainerFixture(
      WorkerSessionServer workerSessions,
      UUID sourceNamespaceId,
      Path sourceRoot,
      String ffmpegScript,
      String ffprobeScript,
      int availableSlots,
      String filenameLocale) {
    this.workerSessions = workerSessions;
    this.sourceNamespaceId = sourceNamespaceId;
    this.sourceRoot = sourceRoot;
    this.ffmpegScript = ffmpegScript;
    this.ffprobeScript = ffprobeScript;
    this.availableSlots = availableSlots == 0 ? 1 : availableSlots;
    this.filenameLocale = filenameLocale;
  }

  public void start() throws IOException {
    var pin = new Properties();
    try (var input = Files.newBufferedReader(Path.of("worker-image.env"))) {
      pin.load(input);
    }

    var image =
        System.getProperty(
            "streamarr.worker.image",
            Optional.ofNullable(System.getenv("STREAMARR_WORKER_IMAGE"))
                .filter(value -> !value.isBlank())
                .orElse(pin.getProperty("STREAMARR_WORKER_IMAGE")));
    assertThat(image)
        .as(
            "Select a worker image in worker-image.env, STREAMARR_WORKER_IMAGE or streamarr.worker.image")
        .isNotBlank();
    makeMediaReadable();
    Testcontainers.exposeHostPorts(workerSessions.port());
    container =
        new GenericContainer<>(image)
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("worker-" + workerId))
            .withExposedPorts(9091)
            .withEnv("TRANSCODE_WORKER_CONTROL_PLANE_HOST", "host.testcontainers.internal")
            .withEnv("TRANSCODE_WORKER_CONTROL_PLANE_PORT", String.valueOf(workerSessions.port()))
            .withEnv("TRANSCODE_WORKER_ID", workerId.toString())
            .withEnv("TRANSCODE_WORKER_SOURCE_NAMESPACE_ID", sourceNamespaceId.toString())
            .withEnv("TRANSCODE_WORKER_SOURCE_ROOT", "/media")
            .withEnv("TRANSCODE_WORKER_SLOTS", String.valueOf(availableSlots))
            .withEnv("TRANSCODE_WORKER_SEGMENT_BASE_PATH", "/tmp/segments")
            .withEnv("BPL_JVM_THREAD_COUNT", "100")
            .withEnv("LANG", "C.UTF-8")
            .withFileSystemBind(
                sourceRoot.toAbsolutePath().toString(), "/media", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(9091))
            .withStartupTimeout(Duration.ofMinutes(2));
    var script =
        """
        #!/bin/bash
        for argument in "$@"; do
          if [[ $argument == /media/* ]]; then
            attempt=${PWD##*/}
            printf '%s\\0' "$@" > "/tmp/command-$attempt"
            printf '%s' "$$" > "/tmp/producer-$attempt.tmp"
            mv "/tmp/producer-$attempt.tmp" "/tmp/producer-$attempt"
        __FFMPEG_BODY__
            break
          fi
        done
        exec ffmpeg "$@"
        """
            .replace("__FFMPEG_BODY__", ffmpegScript == null ? "" : ffmpegScript.indent(4));
    container
        .withCopyToContainer(Transferable.of(script, 0755), "/tmp/scripted-ffmpeg")
        .withEnv("TRANSCODE_WORKER_FFMPEG_PATH", "/tmp/scripted-ffmpeg");
    if (ffprobeScript != null) {
      container
          .withCopyToContainer(
              Transferable.of("#!/bin/bash\n" + ffprobeScript + "\nexec ffprobe \"$@\"\n", 0755),
              "/tmp/scripted-ffprobe")
          .withEnv("TRANSCODE_WORKER_FFPROBE_PATH", "/tmp/scripted-ffprobe");
    }

    if (filenameLocale != null) {
      container.withEnv("LC_ALL", filenameLocale);
    }

    container.start();
  }

  private void makeMediaReadable() throws IOException {
    try (var paths = Files.walk(sourceRoot)) {
      for (var path : paths.toList()) {
        Files.setPosixFilePermissions(
            path,
            PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwxr-xr-x" : "rw-r--r--"));
      }
    }
  }

  public static String emitSegments(Map<String, byte[]> segments) {
    var script = new StringBuilder();
    segments.forEach(
        (name, bytes) -> {
          assertThat(name).matches("[a-zA-Z0-9.]+");
          var escaped = HexFormat.of().formatHex(bytes).replaceAll("(..)", "\\\\x$1");
          script.append("printf '%b' '").append(escaped).append("' > ").append(name).append('\n');
        });
    return script.append("exit 0\n").toString();
  }

  public Optional<List<String>> commandFor(UUID attemptId) throws Exception {
    var result =
        container.execInContainer(
            "bash",
            "-c",
            "test -f /tmp/producer-$1 && cat /tmp/command-$1",
            "bash",
            attemptId.toString());
    assertThat(result.getExitCode()).isIn(0, 1);
    if (result.getExitCode() == 1) {
      return Optional.empty();
    }

    return Optional.of(List.of(result.getStdout().split("\\x00")));
  }

  public boolean processRunning(UUID attemptId) throws Exception {
    var result =
        container.execInContainer(
            "bash",
            "-c",
            "test -f /tmp/producer-$1 && kill -0 $(cat /tmp/producer-$1) 2>/dev/null",
            "bash",
            attemptId.toString());
    assertThat(result.getExitCode()).isIn(0, 1);
    return result.getExitCode() == 0;
  }

  public void killProducer(UUID attemptId) throws Exception {
    var result =
        container.execInContainer(
            "bash",
            "-c",
            "pid=$(cat /tmp/producer-$1) || exit; if kill -0 $pid 2>/dev/null; then kill -KILL $pid 2>/dev/null || ! kill -0 $pid 2>/dev/null; fi",
            "bash",
            attemptId.toString());
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
  }

  public List<Double> packetTimestamps(Path segment) throws Exception {
    var input = "/tmp/inspect-" + UUID.randomUUID() + ".ts";
    container.copyFileToContainer(Transferable.of(Files.readAllBytes(segment), 0644), input);
    // A native output file excludes launcher diagnostics and avoids truncated Docker exec stdout.
    var probe =
        container.execInContainer(
            "/cnb/lifecycle/launcher",
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v",
            "-show_entries",
            "packet=pts_time",
            "-of",
            "csv=p=0",
            "-o",
            input + ".pts",
            input);
    assertThat(probe.getExitCode()).as(probe.getStderr()).isZero();
    var result = container.execInContainer("cat", input + ".pts");
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    var timestamps =
        result
            .getStdout()
            .lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.equals("N/A"))
            .map(line -> line.replace(",", ""))
            .map(Double::parseDouble)
            .toList();
    assertThat(timestamps).isNotEmpty();
    return timestamps;
  }

  public boolean probeRunning() throws Exception {
    var result =
        container.execInContainer(
            "bash", "-c", "test -f /tmp/held-probe && kill -0 $(cat /tmp/held-probe) 2>/dev/null");
    assertThat(result.getExitCode()).isIn(0, 1);
    return result.getExitCode() == 0;
  }

  public boolean probeStarted() throws Exception {
    var result = container.execInContainer("test", "-f", "/tmp/held-probe");
    assertThat(result.getExitCode()).isIn(0, 1);
    return result.getExitCode() == 0;
  }

  public long decodedVideoFrameCount(Path media) throws Exception {
    var input = "/tmp/decode-" + UUID.randomUUID() + ".mp4";
    container.copyFileToContainer(Transferable.of(Files.readAllBytes(media), 0644), input);
    var decode =
        container.execInContainer(
            "/cnb/lifecycle/launcher",
            "ffmpeg",
            "-v",
            "error",
            "-xerror",
            "-i",
            input,
            "-map",
            "0:v:0",
            "-f",
            "framemd5",
            input + ".frames");
    assertThat(decode.getExitCode()).as("Decode video: %s", decode.getStderr()).isZero();
    var frames = container.execInContainer("cat", input + ".frames");
    assertThat(frames.getExitCode()).as(frames.getStderr()).isZero();
    return frames
        .getStdout()
        .lines()
        .filter(line -> !line.isBlank() && !line.startsWith("#"))
        .count();
  }

  public void pause() {
    container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
  }

  public void unpause() {
    container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
  }

  @Override
  public void close() {
    if (container != null) {
      container.close();
    }
  }
}
