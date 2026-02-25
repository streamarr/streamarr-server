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

  private final ObjectMapper objectMapper;
  private final Function<Path, Process> processFactory;

  @Override
  public MediaProbe probe(Path filepath) {
    try {
      var process = processFactory.apply(filepath);
      var json = objectMapper.readTree(process.getInputStream());
      var exitCode = process.waitFor();

      if (exitCode != 0) {
        throw new FfmpegNotAvailableException(
            "ffprobe exited with code " + exitCode + " for: " + filepath);
      }

      return parseProbe(json, filepath);
    } catch (FfmpegNotAvailableException | TranscodeException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TranscodeException("ffprobe interrupted for: " + filepath, e);
    } catch (Exception e) {
      throw new TranscodeException("Failed to parse ffprobe output for: " + filepath, e);
    }
  }

  private MediaProbe parseProbe(JsonNode root, Path filepath) {
    var videoStream =
        findStream(root, "video")
            .orElseThrow(() -> new TranscodeException("No video stream found in: " + filepath));
    var audioStream = findStream(root, "audio");
    var format = root.get("format");

    return MediaProbe.builder()
        .videoCodec(videoStream.get("codec_name").asString())
        .audioCodec(audioStream.map(s -> s.get("codec_name").asString()).orElse(null))
        .audioChannels(audioStream.map(s -> optionalInt(s, "channels")).orElse(OptionalInt.empty()))
        .audioBitrate(
            audioStream.map(s -> optionalLong(s, "bit_rate")).orElse(OptionalLong.empty()))
        .width(videoStream.get("width").asInt())
        .height(videoStream.get("height").asInt())
        .framerate(parseFrameRate(videoStream.get("r_frame_rate").asString()))
        .duration(parseDuration(format.get("duration").asString()))
        .bitrate(format.get("bit_rate").asLong())
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
    int index = 0;
    for (var stream : streamsNode) {
      var codecType = stream.get("codec_type").asString();
      result.add(
          StreamInfo.builder()
              .index(index++)
              .codecType(codecType)
              .codec(stream.get("codec_name").asString())
              .language(extractLanguage(stream))
              .channels(
                  "audio".equals(codecType) ? optionalInt(stream, "channels") : OptionalInt.empty())
              .bitrate(
                  "audio".equals(codecType)
                      ? optionalLong(stream, "bit_rate")
                      : OptionalLong.empty())
              .isDefault(extractDisposition(stream, "default"))
              .isForced(extractDisposition(stream, "forced"))
              .build());
    }
    return List.copyOf(result);
  }

  private String extractLanguage(JsonNode stream) {
    var tags = stream.get("tags");
    if (tags == null || tags.isNull()) {
      return null;
    }
    var language = tags.get("language");
    if (language == null || language.isNull()) {
      return null;
    }
    return language.asString();
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

  private String optionalString(JsonNode node, String field) {
    var value = node.get(field);
    return value != null && !value.isNull() ? value.asString() : null;
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
