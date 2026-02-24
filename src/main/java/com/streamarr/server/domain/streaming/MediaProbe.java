package com.streamarr.server.domain.streaming;

import java.time.Duration;
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
    OptionalLong audioBitrate) {

  public MediaProbe {
    if (audioChannels == null) {
      audioChannels = OptionalInt.empty();
    }
    if (audioBitrate == null) {
      audioBitrate = OptionalLong.empty();
    }
  }
}
