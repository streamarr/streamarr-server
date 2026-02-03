package com.streamarr.server.services.streaming.ffmpeg;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.exceptions.FfmpegNotAvailableException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.FfprobeService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    var videoStream = findStream(root, "video");
    if (videoStream == null) {
      throw new TranscodeException("No video stream found in: " + filepath);
    }

    var audioStream = findStream(root, "audio");
    var format = root.get("format");

    return MediaProbe.builder()
        .videoCodec(videoStream.get("codec_name").asText())
        .audioCodec(audioStream != null ? audioStream.get("codec_name").asText() : null)
        .width(videoStream.get("width").asInt())
        .height(videoStream.get("height").asInt())
        .framerate(parseFramerate(videoStream.get("r_frame_rate").asText()))
        .duration(parseDuration(format.get("duration").asText()))
        .bitrate(format.get("bit_rate").asLong())
        .build();
  }

  private JsonNode findStream(JsonNode root, String codecType) {
    var streams = root.get("streams");
    if (streams == null) {
      return null;
    }
    for (var stream : streams) {
      if (codecType.equals(stream.get("codec_type").asText())) {
        return stream;
      }
    }
    return null;
  }

  private double parseFramerate(String rFrameRate) {
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
