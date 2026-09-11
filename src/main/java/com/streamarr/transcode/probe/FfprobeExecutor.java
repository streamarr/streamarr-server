package com.streamarr.transcode.probe;

import com.google.protobuf.Duration;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public final class FfprobeExecutor {

  public static final int PROBE_VERSION = 1;
  private static final int AVERROR_INVALIDDATA = -1094995529;
  private static final int AVERROR_EOF = -541478725;

  @NonNull private final ObjectMapper objectMapper;
  @NonNull private final Function<Path, Process> processFactory;

  public static FfprobeExecutor forBinary(@NonNull Path binary) {
    return new FfprobeExecutor(new ObjectMapper(), source -> start(binary, source));
  }

  private static Process start(Path binary, Path source) {
    try {
      return new ProcessBuilder(
              binary.toString(),
              "-v",
              "quiet",
              "-print_format",
              "json",
              "-show_streams",
              "-show_format",
              "-show_error",
              source.toString())
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to start ffprobe", e);
    }
  }

  public ProbeAttemptResult probe(Path source, ProbeRequest request) {
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(request.getProbeVersion());
    if (request.getProbeVersion() != PROBE_VERSION) {
      return result.setFailure(ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION).build();
    }

    if (Thread.currentThread().isInterrupted()) {
      return result.setFailure(ProbeFailure.PROBE_FAILURE_CANCELLED).build();
    }

    try {
      var process = processFactory.apply(source);
      return execute(process, result);
    } catch (Exception _) {
      return result.setFailure(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED).build();
    }
  }

  private ProbeAttemptResult execute(Process process, ProbeAttemptResult.Builder result)
      throws ExecutionException {
    try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var output = readers.submit(() -> readOutput(process));
      return collect(process, output, result);
    }
  }

  private byte[] readOutput(Process process) throws IOException {
    try (var output = process.getInputStream()) {
      return output.readAllBytes();
    }
  }

  private ProbeAttemptResult collect(
      Process process, Future<byte[]> output, ProbeAttemptResult.Builder result)
      throws ExecutionException {
    try {
      var exitCode = process.waitFor();
      return interpret(objectMapper.readTree(output.get()), exitCode, result);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return result.setFailure(ProbeFailure.PROBE_FAILURE_CANCELLED).build();
    } finally {
      if (process.isAlive()) {
        terminate(process);
      }
    }
  }

  private void terminate(Process process) {
    process.destroyForcibly();
    process.onExit().join();
  }

  private ProbeAttemptResult interpret(
      JsonNode json, int exitCode, ProbeAttemptResult.Builder result) {
    if (json == null || !json.isObject()) {
      return result.setFailure(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED).build();
    }

    if (exitCode != 0) {
      return result.setFailure(failureFor(json.path("error").path("code").asInt())).build();
    }

    var media = parseMedia(json);
    if (media.getStreamsList().stream()
        .noneMatch(stream -> "video".equals(stream.getCodecType()))) {
      return result.setFailure(ProbeFailure.PROBE_FAILURE_NO_VIDEO_STREAM).build();
    }

    return result.setMedia(media).build();
  }

  private ProbeFailure failureFor(int errorCode) {
    return switch (errorCode) {
      case AVERROR_INVALIDDATA, AVERROR_EOF -> ProbeFailure.PROBE_FAILURE_INVALID_MEDIA;
      // FFmpeg returns negative ENOENT, EIO, EACCES, or ENOTDIR for these source failures.
      case -2, -5, -13, -20 -> ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE;
      default -> ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED;
    };
  }

  private ProbeMediaInfo parseMedia(JsonNode root) {
    var container = ProbeContainerInfo.newBuilder();
    var format = root.path("format");
    text(format, "format_name").ifPresent(container::setFormat);
    number(format, "bit_rate", Long::parseLong).ifPresent(container::setBitrateBitsPerSecond);
    number(format, "duration", Double::parseDouble)
        .filter(value -> Double.isFinite(value) && value >= 0)
        .map(this::duration)
        .ifPresent(container::setDuration);
    var media = ProbeMediaInfo.newBuilder().setContainer(container);
    var streams = root.path("streams");
    if (!streams.isMissingNode() && !streams.isNull() && !streams.isArray()) {
      throw new IllegalArgumentException("ffprobe returned a malformed stream list");
    }

    for (var stream : streams) {
      media.addStreams(parseStream(stream, media.getStreamsCount()));
    }

    return media.build();
  }

  private ProbeStreamInfo parseStream(JsonNode source, int position) {
    var type =
        text(source, "codec_type")
            .orElseThrow(
                () -> new IllegalArgumentException("ffprobe stream is missing codec_type"));
    var stream =
        ProbeStreamInfo.newBuilder()
            .setIndex(text(source, "index").map(Integer::parseInt).orElse(position))
            .setCodecType(type)
            .setIsDefault(source.path("disposition").path("default").asInt() == 1)
            .setIsForced(source.path("disposition").path("forced").asInt() == 1);
    text(source, "codec_name").ifPresent(stream::setCodec);
    text(source.path("tags"), "language").ifPresent(stream::setLanguage);
    number(source, "bit_rate", Long::parseLong).ifPresent(stream::setBitrateBitsPerSecond);
    number(source, "width", Integer::parseInt).ifPresent(stream::setWidth);
    number(source, "height", Integer::parseInt).ifPresent(stream::setHeight);
    number(source, "r_frame_rate", this::framerate)
        .filter(value -> Double.isFinite(value) && value > 0)
        .ifPresent(stream::setFramerate);
    if ("audio".equals(type)) {
      number(source, "channels", Integer::parseInt).ifPresent(stream::setChannels);
    }

    return stream.build();
  }

  private Optional<String> text(JsonNode parent, String name) {
    return Optional.ofNullable(parent.get(name))
        .filter(value -> !value.isNull())
        .map(JsonNode::asString);
  }

  private double framerate(String value) {
    var parts = value.split("/");
    return parts.length == 2
        ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1])
        : Double.parseDouble(value);
  }

  private <T> Optional<T> number(JsonNode parent, String name, Function<String, T> parser) {
    try {
      return text(parent, name).map(parser);
    } catch (NumberFormatException _) {
      return Optional.empty();
    }
  }

  private Duration duration(double seconds) {
    var millis = (long) (seconds * 1000);
    return Duration.newBuilder()
        .setSeconds(millis / 1000)
        .setNanos((int) (millis % 1000) * 1_000_000)
        .build();
  }
}
