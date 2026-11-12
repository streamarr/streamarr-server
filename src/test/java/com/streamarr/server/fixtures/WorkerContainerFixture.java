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
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public final class WorkerContainerFixture implements AutoCloseable {

  private static final String RMS_LEVEL = "RMS level dB: ";

  // The frame metadata key that FFmpeg's signalstats filter writes.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static final String LUMA_CHANGE = "lavfi.signalstats.YDIF";

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
    // Keyframe scans can belong to a job attempt too. Let their framecrc output reach the worker;
    // only the media-producing command is scripted and owns the attempt's captured process id.
    var script =
        """
        #!/bin/bash
        if [[ -z ${STREAMARR_JOB_ATTEMPT_ID:-} || ${*: -3} == "-f framecrc pipe:1" ]]; then
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
        probePackets(media, "v", List.of("-show_entries", "packet=pts_time")).stream()
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
   * What ffprobe prints for each decoded frame of the media's first video stream, as {@code
   * key=value} lines: the pixel format, the colour signaling, and the type of each side data that
   * the decoder attaches. Holds no {@code pix_fmt} line when no frame decodes.
   */
  public List<String> decodedVideoFrames(Path media) throws Exception {
    return probe(
        media,
        "v:0",
        List.of(
            "-show_entries",
            "frame=pix_fmt,color_space,color_transfer,color_primaries"
                + ":frame_side_data=side_data_type"),
        "default=nw=1");
  }

  private List<String> packetHashes(Path media, String streamSelector) throws Exception {
    return probePackets(
        media,
        streamSelector,
        List.of("-show_entries", "packet=data_hash", "-show_data_hash", "sha256"));
  }

  /**
   * The codec of each stream of the media and each of its packets in file order, as the worker
   * image's ffprobe reads them without decoding. A packet without a presentation time is left out.
   */
  public ProbedPackets probedPackets(Path media) throws Exception {
    var probed =
        JsonMapper.shared()
            .readTree(
                probeOutput(
                    media,
                    List.of(
                        "-show_entries",
                        "stream=codec_type,codec_name:packet=codec_type,pts_time,data_hash",
                        "-show_data_hash",
                        "sha256",
                        "-of",
                        "json")));
    var streams =
        probed
            .path("streams")
            .valueStream()
            .map(
                stream ->
                    new ProbedStream(
                        stream.path("codec_type").asString(), stream.path("codec_name").asString()))
            .toList();
    var packets =
        probed
            .path("packets")
            .valueStream()
            .filter(packet -> !packet.path("pts_time").asString("N/A").equals("N/A"))
            .map(
                packet ->
                    new ProbedPacket(
                        packet.path("codec_type").asString(),
                        Double.parseDouble(packet.path("pts_time").asString()),
                        packet.path("data_hash").asString()))
            .toList();
    assertThat(packets).isNotEmpty();
    return new ProbedPackets(streams, packets);
  }

  /** A stream's media type as ffprobe names it, such as {@code video}, and its codec. */
  public record ProbedStream(String mediaType, String codecName) {}

  /** A packet's media type, presentation time in seconds and the SHA-256 hash of its data. */
  public record ProbedPacket(String mediaType, double presentationSeconds, String dataHash) {}

  /** The streams and packets that ffprobe reads of one file. */
  public record ProbedPackets(List<ProbedStream> streams, List<ProbedPacket> packets) {

    public List<String> codecsOf(String mediaType) {
      return streams.stream()
          .filter(stream -> stream.mediaType().equals(mediaType))
          .map(ProbedStream::codecName)
          .toList();
    }

    public List<Double> presentationTimesOf(String mediaType) {
      return packetsOf(mediaType).map(ProbedPacket::presentationSeconds).toList();
    }

    public List<String> dataHashesOf(String mediaType) {
      return packetsOf(mediaType).map(ProbedPacket::dataHash).toList();
    }

    private Stream<ProbedPacket> packetsOf(String mediaType) {
      return packets.stream().filter(packet -> packet.mediaType().equals(mediaType));
    }
  }

  private List<String> probePackets(
      Path media, String streamSelector, List<String> inspectionArguments) throws Exception {
    return probe(media, streamSelector, inspectionArguments, "csv=p=0");
  }

  private List<String> probe(
      Path media, String streamSelector, List<String> inspectionArguments, String outputFormat)
      throws Exception {
    var arguments = new ArrayList<>(List.of("-select_streams", streamSelector));
    arguments.addAll(inspectionArguments);
    arguments.addAll(List.of("-of", outputFormat));
    return probeOutput(media, arguments)
        .lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .toList();
  }

  private String probeOutput(Path media, List<String> arguments) throws Exception {
    var input = "/tmp/inspect-" + UUID.randomUUID() + ".mp4";
    container.copyFileToContainer(Transferable.of(Files.readAllBytes(media), 0644), input);
    // A native output file excludes launcher diagnostics and avoids truncated Docker exec stdout.
    var command = new ArrayList<>(List.of("/cnb/lifecycle/launcher", "ffprobe", "-v", "error"));
    command.addAll(arguments);
    command.addAll(List.of("-o", input + ".pts", input));
    var probe = container.execInContainer(command.toArray(String[]::new));
    assertThat(probe.getExitCode()).as(probe.getStderr()).isZero();
    var result = container.execInContainer("cat", input + ".pts");
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    return result.getStdout();
  }

  /**
   * The first video frame as the worker image's ffprobe describes it, by field name: its pixel
   * format, colour description and the fields of its side data, such as the mastering display.
   */
  public Map<String, String> firstVideoFrame(Path media) throws Exception {
    var input = "/tmp/frame-" + UUID.randomUUID() + ".mp4";
    container.copyFileToContainer(Transferable.of(Files.readAllBytes(media), 0644), input);
    var probe =
        container.execInContainer(
            "/cnb/lifecycle/launcher",
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_frames",
            "-read_intervals",
            "%+#1",
            "-of",
            "default=noprint_wrappers=1",
            "-o",
            input + ".frame",
            input);
    assertThat(probe.getExitCode()).as(probe.getStderr()).isZero();
    var result = container.execInContainer("cat", input + ".frame");
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    return result
        .getStdout()
        .lines()
        .filter(line -> line.contains("="))
        .collect(
            Collectors.toMap(
                line -> line.substring(0, line.indexOf('=')),
                line -> line.substring(line.indexOf('=') + 1),
                (first, _) -> first));
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

  /**
   * The media's first audio stream as the worker image's FFmpeg decodes it: the decoder's channel
   * layout and each channel's RMS level in dBFS, in the layout's channel order.
   */
  public DecodedAudio decodedAudio(Path media) throws Exception {
    var layout =
        probePackets(media, "a:0", List.of("-show_entries", "stream=channel_layout")).getFirst();
    var input = "/tmp/levels-" + UUID.randomUUID() + ".mp4";
    container.copyFileToContainer(Transferable.of(Files.readAllBytes(media), 0644), input);
    // astats reports its summary at the info level on standard error; a file keeps the launcher's
    // diagnostics out of it.
    var decode =
        container.execInContainer(
            "bash",
            "-c",
            "/cnb/lifecycle/launcher ffmpeg -nostats -xerror -i \"$1\" -map 0:a:0 -af"
                + " astats=measure_perchannel=RMS_level:measure_overall=none -f null - 2>"
                + " \"$1.levels\"",
            "bash",
            input);
    assertThat(decode.getExitCode()).as("Decode audio: %s", decode.getStderr()).isZero();
    var report = container.execInContainer("cat", input + ".levels");
    assertThat(report.getExitCode()).as(report.getStderr()).isZero();
    var levels =
        report
            .getStdout()
            .lines()
            .filter(line -> line.contains(RMS_LEVEL))
            .map(line -> rmsLevel(line.substring(line.indexOf(RMS_LEVEL) + RMS_LEVEL.length())))
            .toList();
    return new DecodedAudio(layout.split(",")[0], levels);
  }

  // astats writes -inf for a silent channel.
  private static double rmsLevel(String value) {
    if (value.equals("-inf")) {
      return Double.NEGATIVE_INFINITY;
    }

    return Double.parseDouble(value);
  }

  /** A decoded audio stream's channel layout, as FFmpeg names it, and each channel's RMS level. */
  public record DecodedAudio(String channelLayout, List<Double> channelLevels) {}

  public long decodedVideoFrameCount(Path media) throws Exception {
    return decodedVideoFrameHashes(media).size();
  }

  /**
   * The MD5 hash of each picture that the worker image's FFmpeg decodes from the media's first
   * video stream, in presentation order.
   */
  public List<String> decodedVideoFrameHashes(Path media) throws Exception {
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
        .map(line -> line.substring(line.lastIndexOf(',') + 1).trim())
        .toList();
  }

  /**
   * How much each decoded frame of the media's first video stream differs from the frame before it:
   * the mean absolute difference of their luma samples, which FFmpeg's signalstats filter reports
   * as YDIF, in presentation order. The first frame has none before it and reports 0.
   */
  public List<Double> lumaChangeFromPreviousFrame(Path media) throws Exception {
    var input = "/tmp/change-" + UUID.randomUUID() + ".mp4";
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
            "-vf",
            "signalstats,metadata=mode=print:key=" + LUMA_CHANGE + ":file=" + input + ".change",
            "-f",
            "null",
            "-");
    assertThat(decode.getExitCode()).as("Decode video: %s", decode.getStderr()).isZero();
    var changes = container.execInContainer("cat", input + ".change");
    assertThat(changes.getExitCode()).as(changes.getStderr()).isZero();
    return changes
        .getStdout()
        .lines()
        .filter(line -> line.startsWith(LUMA_CHANGE + "="))
        .map(line -> Double.parseDouble(line.substring(LUMA_CHANGE.length() + 1)))
        .toList();
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
