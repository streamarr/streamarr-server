package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ProbeFixture {

  public static ProbeOutcome.Success completeProbe(MediaProbe probe) {
    return new ProbeOutcome.Success(
        ProbeContainer.builder()
            .format(probe.containerFormat())
            .duration(Optional.of(probe.duration()))
            .bitrate(OptionalLong.of(probe.bitrate()))
            .build(),
        streams(probe));
  }

  private static List<StreamInfo> streams(MediaProbe probe) {
    if (!probe.streams().isEmpty()) {
      return probe.streams();
    }

    var streams = new ArrayList<StreamInfo>();
    streams.add(
        StreamInfo.builder()
            .codecType("video")
            .codec(Optional.ofNullable(probe.videoCodec()))
            .width(OptionalInt.of(probe.width()))
            .height(OptionalInt.of(probe.height()))
            .framerate(OptionalDouble.of(probe.framerate()))
            .build());
    if (probe.audioCodec() == null) {
      return streams;
    }

    streams.add(
        StreamInfo.builder()
            .index(1)
            .codecType("audio")
            .codec(Optional.of(probe.audioCodec()))
            .channels(probe.audioChannels())
            .bitrate(probe.audioBitrate())
            .build());
    return streams;
  }
}
