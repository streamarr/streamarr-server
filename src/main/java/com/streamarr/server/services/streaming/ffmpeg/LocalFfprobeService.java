package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.exceptions.FfmpegNotAvailableException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.FfprobeService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

  private final ObjectMapper objectMapper;
  private final Function<Path, Process> processFactory;

  @Override
  public MediaProbe probe(Path filepath) {
    try {
      var process = processFactory.apply(filepath);
      var json = objectMapper.readTree(process.getInputStream());
      var exitCode = process.waitFor();

      if (exitCode != 0) {
        log.error("ffprobe exited with code {} for: {}", exitCode, filepath);
        throw new FfmpegNotAvailableException(
            "Media processing is unavailable. Check server logs for details");
      }

      return parseProbe(json, filepath);
    } catch (FfmpegNotAvailableException | TranscodeException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("ffprobe interrupted for: {}", filepath, e);
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, e);
    } catch (Exception e) {
      log.error("Failed to parse ffprobe output for: {}", filepath, e);
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, e);
    }
  }

  private MediaProbe parseProbe(JsonNode root, Path filepath) {
    var videoStream =
        findStream(root, "video")
            .orElseThrow(() -> {
              log.error("No video stream found in: {}", filepath);
              return new TranscodeException(TranscodeException.GENERIC_MESSAGE);
            });
    var audioStream = findStream(root, AUDIO);
    var format = root.get("format");

    return MediaProbe.builder()
        .videoCodec(videoStream.get(CODEC_NAME).asString())
        .audioCodec(audioStream.map(s -> s.get(CODEC_NAME).asString()).orElse(null))
        .audioChannels(audioStream.map(s -> optionalInt(s, "channels")).orElse(OptionalInt.empty()))
        .audioBitrate(audioStream.map(s -> optionalLong(s, BIT_RATE)).orElse(OptionalLong.empty()))
        .width(videoStream.get("width").asInt())
        .height(videoStream.get("height").asInt())
        .framerate(parseFrameRate(videoStream.get("r_frame_rate").asString()))
        .duration(parseDuration(format.get("duration").asString()))
        .bitrate(format.get(BIT_RATE).asLong())
        .containerFormat(optionalString(format, "format_name"))
        .streams(parseAllStreams(root))
        .build();
  }

  private List<StreamInfo> parseAllStreams(JsonNode root) {
    var streamsNode = root.get("streams");
    if (streamsNode == null) {
      return List.of();
    }

    var result = new ArrayList<StreamInfo>();
    for (int i = 0; i < streamsNode.size(); i++) {
      var stream = streamsNode.get(i);
      var codecType = stream.get("codec_type").asString();
      var indexNode = stream.get("index");
      result.add(
          StreamInfo.builder()
              .index(indexNode != null && !indexNode.isNull() ? indexNode.asInt() : i)
              .codecType(codecType)
              .codec(stream.get(CODEC_NAME).asString())
              .language(extractLanguage(stream))
              .channels(
                  AUDIO.equals(codecType) ? optionalInt(stream, "channels") : OptionalInt.empty())
              .bitrate(
                  AUDIO.equals(codecType) ? optionalLong(stream, BIT_RATE) : OptionalLong.empty())
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
      if (codecType.equals(stream.get("codec_type").asString())) {
        return Optional.of(stream);
      }
    }
    return Optional.empty();
  }

  private Optional<String> optionalString(JsonNode node, String field) {
    var value = node.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    return Optional.of(value.asString());
  }

  private OptionalInt optionalInt(JsonNode node, String field) {
    var value = node.get(field);
    return value != null && !value.isNull() ? OptionalInt.of(value.asInt()) : OptionalInt.empty();
  }

  private OptionalLong optionalLong(JsonNode node, String field) {
    var value = node.get(field);
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

  private double parseFrameRate(String rFrameRate) {
    var parts = rFrameRate.split("/");
    if (parts.length == 2) {
      return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
    }

    return Double.parseDouble(rFrameRate);
  }

  private Duration parseDuration(String durationStr) {
    var seconds = Double.parseDouble(durationStr);
    return Duration.ofMillis((long) (seconds * 1000));
  }
}
