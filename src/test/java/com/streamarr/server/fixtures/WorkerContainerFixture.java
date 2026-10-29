package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
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

  // The metadata key under which FFmpeg's signalstats filter prints a frame's mean Cr.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static final String MEAN_CR_METADATA_KEY = "lavfi.signalstats.VAVG";

  private final WorkerSessionServer workerSessions;
  private final UUID sourceNamespaceId;
  private final Path sourceRoot;
  private final String ffmpegScript;
  private final String ffprobeScript;
  private final int availableSlots;
  private final Integer backgroundProbeCapacity;
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
      Integer backgroundProbeCapacity,
      String filenameLocale) {
    this.workerSessions = workerSessions;
    this.sourceNamespaceId = sourceNamespaceId;
    this.sourceRoot = sourceRoot;
    this.ffmpegScript = ffmpegScript;
    this.ffprobeScript = ffprobeScript;
    this.availableSlots = availableSlots == 0 ? 1 : availableSlots;
    this.backgroundProbeCapacity = backgroundProbeCapacity;
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
            .withEnv("BPL_JVM_THREAD_COUNT", "100")
            .withFileSystemBind(
                sourceRoot.toAbsolutePath().toString(), "/media", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(9091))
            .withStartupTimeout(Duration.ofMinutes(2));
    // The worker names the job attempt in STREAMARR_JOB_ATTEMPT_ID; the script keys its command and
    // process files by it. A keyframe packet scan runs FFmpeg outside any job attempt, so the
    // script passes that run straight to FFmpeg.
    var script =
        """
        #!/bin/bash
        if [[ -z ${STREAMARR_JOB_ATTEMPT_ID:-} ]]; then
          exec ffmpeg "$@"
        fi
        for argument in "$@"; do
          if [[ $argument == /media/* ]]; then
            attempt=$STREAMARR_JOB_ATTEMPT_ID
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
    for (var recording : RecordedStream.values()) {
      container.withCopyToContainer(
          Transferable.of(recording.bytes(), 0644), recording.containerPath());
    }

    if (ffprobeScript != null) {
      container
          .withCopyToContainer(
              Transferable.of("#!/bin/bash\n" + ffprobeScript + "\nexec ffprobe \"$@\"\n", 0755),
              "/tmp/scripted-ffprobe")
          .withEnv("TRANSCODE_WORKER_FFPROBE_PATH", "/tmp/scripted-ffprobe");
    }

    if (backgroundProbeCapacity != null) {
      container.withEnv("TRANSCODE_WORKER_PROBE_CAPACITY", String.valueOf(backgroundProbeCapacity));
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

  /**
   * A scripted FFmpeg body that writes the recording to standard output, as the mp4 muxer writes to
   * {@code pipe:1}, and exits cleanly.
   */
  public static String emitRecordedStream(RecordedStream recording) {
    return "cat " + recording.containerPath() + "\nexit 0\n";
  }

  /**
   * Writes media that the worker image's FFmpeg makes from {@code inputArguments} to {@code
   * target}, a path under the source root, where the worker can read it.
   */
  public Path generateMedia(Path target, List<String> inputArguments) throws Exception {
    var output = "/tmp/generated-" + UUID.randomUUID() + "-" + target.getFileName();
    var command = new ArrayList<>(List.of("/cnb/lifecycle/launcher", "ffmpeg", "-v", "error"));
    command.addAll(inputArguments);
    command.add(output);
    var result = container.execInContainer(command.toArray(String[]::new));
    assertThat(result.getExitCode()).as("Generate media: %s", result.getStderr()).isZero();
    container.copyFileFromContainer(output, target.toString());
    return target;
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

  public List<Double> packetTimestamps(Path media) throws Exception {
    var timestamps =
        probeEntries(media, "v", List.of("-show_entries", "packet=pts_time")).stream()
            .filter(line -> !line.equals("N/A"))
            .map(line -> line.replace(",", ""))
            .map(Double::parseDouble)
            .toList();
    assertThat(timestamps).isNotEmpty();
    return timestamps;
  }

  public List<String> videoPacketHashes(Path media) throws Exception {
    return packetHashes(media, "v");
  }

  public List<String> audioPacketHashes(Path media) throws Exception {
    return packetHashes(media, "a");
  }

  /**
   * The side data that the container declares for the first video stream, such as a mastering
   * display or a Dolby Vision configuration record, as ffprobe names each type.
   */
  public List<String> videoStreamSideDataTypes(Path media) throws Exception {
    return probeEntries(media, "v:0", List.of("-show_entries", "stream_side_data=side_data_type"))
        .stream()
        .flatMap(line -> Stream.of(line.split(",")))
        .toList();
  }

  /**
   * One entry of the first video stream, such as codec_name or color_transfer, as ffprobe names it.
   */
  public String videoStreamEntry(Path media, String entry) throws Exception {
    var values = probeEntries(media, "v:0", List.of("-show_entries", "stream=" + entry));
    assertThat(values).as("the %s of the first video stream", entry).hasSize(1);
    return values.getFirst();
  }

  /**
   * The mean Cr of the first video stream's first frames, on the 8-bit scale where 128 is neutral,
   * as the image's FFmpeg decodes them.
   */
  public double meanCr(Path media, int frameCount) throws Exception {
    var input = "/tmp/measure-" + UUID.randomUUID() + "-" + media.getFileName();
    container.copyFileToContainer(Transferable.of(Files.readAllBytes(media), 0644), input);
    var measure =
        container.execInContainer(
            "/cnb/lifecycle/launcher",
            "ffmpeg",
            "-v",
            "error",
            "-i",
            input,
            "-map",
            "0:v:0",
            "-frames:v",
            String.valueOf(frameCount),
            "-vf",
            "format=yuv420p,signalstats,metadata=mode=print:key="
                + MEAN_CR_METADATA_KEY
                + ":file="
                + input
                + ".cr",
            "-f",
            "null",
            "-");
    assertThat(measure.getExitCode()).as("Measure Cr: %s", measure.getStderr()).isZero();
    var printed = container.execInContainer("cat", input + ".cr");
    assertThat(printed.getExitCode()).as(printed.getStderr()).isZero();
    var means =
        printed
            .getStdout()
            .lines()
            .filter(line -> line.startsWith(MEAN_CR_METADATA_KEY + "="))
            .map(line -> Double.parseDouble(line.substring(MEAN_CR_METADATA_KEY.length() + 1)))
            .limit(frameCount)
            .toList();
    assertThat(means).as("the mean Cr of each measured frame").hasSize(frameCount);
    return means.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
  }

  private List<String> packetHashes(Path media, String streamSelector) throws Exception {
    return probeEntries(
        media,
        streamSelector,
        List.of("-show_entries", "packet=data_hash", "-show_data_hash", "sha256"));
  }

  private List<String> probeEntries(
      Path media, String streamSelector, List<String> inspectionArguments) throws Exception {
    var input = "/tmp/inspect-" + UUID.randomUUID() + ".mp4";
    container.copyFileToContainer(Transferable.of(Files.readAllBytes(media), 0644), input);
    // A native output file excludes launcher diagnostics and avoids truncated Docker exec stdout.
    var command =
        new ArrayList<>(
            List.of(
                "/cnb/lifecycle/launcher",
                "ffprobe",
                "-v",
                "error",
                "-select_streams",
                streamSelector));
    command.addAll(inspectionArguments);
    command.addAll(List.of("-of", "csv=p=0", "-o", input + ".pts", input));
    var probe = container.execInContainer(command.toArray(String[]::new));
    assertThat(probe.getExitCode()).as(probe.getStderr()).isZero();
    var result = container.execInContainer("cat", input + ".pts");
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    return result.getStdout().lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
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

  public String logs() {
    return container.getLogs();
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
