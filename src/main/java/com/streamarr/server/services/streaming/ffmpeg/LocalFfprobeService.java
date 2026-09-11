package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.services.streaming.FfprobeService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RequiredArgsConstructor
public class LocalFfprobeService implements FfprobeService {

  private static final String AUDIO = "audio";
  private static final String CODEC_NAME = "codec_name";
  private static final String BIT_RATE = "bit_rate";
  // AVERROR_INVALIDDATA is FFERRTAG('I', 'N', 'D', 'A'), independent of platform errno values.
  private static final int AVERROR_INVALIDDATA = -1094995529;
  private static final int AVERROR_EOF = -541478725;

  private final ObjectMapper objectMapper;
  private final Function<Path, Process> processFactory;

  @Override
  public ProbeOutcome probe(Path filepath) {
    try {
      var process = processFactory.apply(filepath);
      var json = objectMapper.readTree(process.getInputStream());
      var exitCode = process.waitFor();
      if (json == null || json.isNull() || json.isMissingNode()) {
        throw new ProbeExecutionException(
            new IllegalArgumentException("ffprobe returned no result"));
      }

      var errorCode = json.path("error").path("code").asInt();

      if (exitCode != 0 && (errorCode == AVERROR_INVALIDDATA || errorCode == AVERROR_EOF)) {
        return new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA);
      }

      if (exitCode != 0) {
        log.error("ffprobe exited with code {} for: {}", exitCode, filepath);
        throw new ProbeExecutionException();
      }

      return parseProbe(json, filepath);
    } catch (ProbeExecutionException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("ffprobe interrupted for: {}", filepath, e);
      throw new ProbeExecutionException(e);
    } catch (Exception e) {
      log.error("Failed to parse ffprobe output for: {}", filepath, e);
      throw new ProbeExecutionException(e);
    }
  }

  private ProbeOutcome parseProbe(JsonNode root, Path filepath) {
    if (findStream(root, "video").isEmpty()) {
      log.error("No video stream found in: {}", filepath);
      return new ProbeOutcome.Failure(ProbeError.NO_VIDEO_STREAM);
    }

    var format = root.get("format");
    var container =
        ProbeContainer.builder()
            .format(optionalString(format, "format_name"))
            .duration(optionalString(format, "duration").flatMap(this::parseDuration))
            .bitrate(optionalLong(format, BIT_RATE))
            .build();
    return new ProbeOutcome.Success(container, parseAllStreams(root));
  }

  private List<StreamInfo> parseAllStreams(JsonNode root) {
    var streamsNode = root.get("streams");
    if (streamsNode == null) {
      return List.of();
    }

    var result = new ArrayList<StreamInfo>();
    for (int i = 0; i < streamsNode.size(); i++) {
      var stream = streamsNode.get(i);
      var codecType = requiredCodecType(stream);
      var indexNode = stream.get("index");
      result.add(
          StreamInfo.builder()
              .index(indexNode != null && !indexNode.isNull() ? indexNode.asInt() : i)
              .codecType(codecType)
              .codec(optionalString(stream, CODEC_NAME))
              .language(extractLanguage(stream))
              .channels(
                  AUDIO.equals(codecType) ? optionalInt(stream, "channels") : OptionalInt.empty())
              .bitrate(optionalLong(stream, BIT_RATE))
              .width(optionalInt(stream, "width"))
              .height(optionalInt(stream, "height"))
              .framerate(
                  optionalString(stream, "r_frame_rate")
                      .map(this::parseFrameRate)
                      .orElse(OptionalDouble.empty()))
              .isDefault(extractDisposition(stream, "default"))
              .isForced(extractDisposition(stream, "forced"))
              .build());
    }
    return List.copyOf(result);
  }

  private Optional<String> extractLanguage(JsonNode stream) {
    var tags = stream.get("tags");
    if (tags == null || tags.isNull()) {
      return Optional.empty();
    }
    var language = tags.get("language");
    if (language == null || language.isNull()) {
      return Optional.empty();
    }
    return Optional.of(language.asString());
  }

  private boolean extractDisposition(JsonNode stream, String flag) {
    var disposition = stream.get("disposition");
    if (disposition == null || disposition.isNull()) {
      return false;
    }
    var value = disposition.get(flag);
    return value != null && !value.isNull() && value.asInt() == 1;
  }

  private Optional<JsonNode> findStream(JsonNode root, String codecType) {
    var streams = root.get("streams");
    if (streams == null) {
      return Optional.empty();
    }

    for (var stream : streams) {
      if (codecType.equals(requiredCodecType(stream))) {
        return Optional.of(stream);
      }
    }
    return Optional.empty();
  }

  private String requiredCodecType(JsonNode stream) {
    return Optional.ofNullable(stream.get("codec_type"))
        .filter(JsonNode::isTextual)
        .map(JsonNode::asString)
        .filter(value -> !value.isBlank())
        .orElseThrow(
            () ->
                new ProbeExecutionException(
                    new IllegalArgumentException("ffprobe stream is missing codec_type")));
  }

  private Optional<String> optionalString(JsonNode node, String field) {
    var value = node == null ? null : node.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    return Optional.of(value.asString());
  }

  private OptionalInt optionalInt(JsonNode node, String field) {
    var value = node == null ? null : node.get(field);
    if (value == null || value.isNull()) {
      return OptionalInt.empty();
    }

    try {
      return OptionalInt.of(Integer.parseInt(value.asString()));
    } catch (NumberFormatException _) {
      return OptionalInt.empty();
    }
  }

  private OptionalLong optionalLong(JsonNode node, String field) {
    var value = node == null ? null : node.get(field);
    if (value == null || value.isNull()) {
      return OptionalLong.empty();
    }
    var text = value.asString();
    try {
      return OptionalLong.of(Long.parseLong(text));
    } catch (NumberFormatException _) {
      return OptionalLong.empty();
    }
  }

  private OptionalDouble parseFrameRate(String rFrameRate) {
    try {
      var parts = rFrameRate.split("/");
      var rate =
          parts.length == 2
              ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1])
              : Double.parseDouble(rFrameRate);
      return Double.isFinite(rate) && rate > 0 ? OptionalDouble.of(rate) : OptionalDouble.empty();
    } catch (NumberFormatException _) {
      return OptionalDouble.empty();
    }
  }

  private Optional<Duration> parseDuration(String durationStr) {
    try {
      var seconds = Double.parseDouble(durationStr);
      if (!Double.isFinite(seconds) || seconds < 0) {
        return Optional.empty();
      }

      return Optional.of(Duration.ofMillis((long) (seconds * 1000)));
    } catch (NumberFormatException _) {
      return Optional.empty();
    }
  }
}
