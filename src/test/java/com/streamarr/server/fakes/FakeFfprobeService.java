package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.services.streaming.FfprobeService;
import java.nio.file.Path;
import java.time.Duration;

public class FakeFfprobeService implements FfprobeService {

  private MediaProbe defaultProbe =
      MediaProbe.builder()
          .duration(Duration.ofMinutes(120))
          .framerate(23.976)
          .width(1920)
          .height(1080)
          .videoCodec("h264")
          .audioCodec("aac")
          .bitrate(5_000_000L)
          .build();

  @Override
  public MediaProbe probe(Path filepath) {
    return defaultProbe;
  }

  public void setDefaultProbe(MediaProbe probe) {
    this.defaultProbe = probe;
  }
}
