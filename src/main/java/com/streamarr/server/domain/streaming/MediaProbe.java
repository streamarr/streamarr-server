package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Builder;

@Builder
public record MediaProbe(
    Duration duration,
    double framerate,
    int width,
    int height,
    String videoCodec,
    String audioCodec,
    long bitrate,
    OptionalInt audioChannels,
    OptionalLong audioBitrate,
    Optional<String> containerFormat,
    List<StreamInfo> streams) {

  public MediaProbe {
    if (audioChannels == null) {
      audioChannels = OptionalInt.empty();
    }
    if (audioBitrate == null) {
      audioBitrate = OptionalLong.empty();
    }
    if (streams == null) {
      streams = List.of();
    }
  }

  @SuppressWarnings("java:S1068") // Lombok builder default — field is used by generated code
  public static class MediaProbeBuilder {
    private Optional<String> containerFormat = Optional.empty();
  }

  public List<StreamInfo> audioStreams() {
    return streams.stream().filter(s -> "audio".equals(s.codecType())).toList();
  }

  public List<StreamInfo> subtitleStreams() {
    return streams.stream().filter(s -> "subtitle".equals(s.codecType())).toList();
  }
}
