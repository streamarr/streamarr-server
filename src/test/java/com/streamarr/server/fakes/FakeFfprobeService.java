package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.services.streaming.FfprobeService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeFfprobeService implements FfprobeService {

  private RuntimeException failure;
  private Runnable duringProbe = () -> {};
  private final AtomicInteger probeCount = new AtomicInteger();
  private volatile boolean lastProbeOnVirtualThread;

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
  public ProbeOutcome probe(Path filepath) {
    probeCount.incrementAndGet();
    lastProbeOnVirtualThread = Thread.currentThread().isVirtual();
    duringProbe.run();
    if (failure != null) {
      throw failure;
    }

    return new ProbeOutcome.Success(
        ProbeContainer.builder()
            .format(defaultProbe.containerFormat())
            .duration(Optional.of(defaultProbe.duration()))
            .bitrate(OptionalLong.of(defaultProbe.bitrate()))
            .build(),
        streams());
  }

  private List<StreamInfo> streams() {
    if (!defaultProbe.streams().isEmpty()) {
      return defaultProbe.streams();
    }

    var streams = new ArrayList<StreamInfo>();
    streams.add(
        StreamInfo.builder()
            .codecType("video")
            .codec(Optional.ofNullable(defaultProbe.videoCodec()))
            .width(OptionalInt.of(defaultProbe.width()))
            .height(OptionalInt.of(defaultProbe.height()))
            .framerate(OptionalDouble.of(defaultProbe.framerate()))
            .build());
    if (defaultProbe.audioCodec() == null) {
      return streams;
    }

    streams.add(
        StreamInfo.builder()
            .index(1)
            .codecType("audio")
            .codec(Optional.of(defaultProbe.audioCodec()))
            .channels(defaultProbe.audioChannels())
            .bitrate(defaultProbe.audioBitrate())
            .build());
    return streams;
  }

  public void setDefaultProbe(MediaProbe probe) {
    this.defaultProbe = probe;
  }

  public void failWith(RuntimeException failure) {
    this.failure = failure;
  }

  public void runDuringProbe(Runnable action) {
    this.duringProbe = action;
  }

  public int probeCount() {
    return probeCount.get();
  }

  public boolean wasLastProbeOnVirtualThread() {
    return lastProbeOnVirtualThread;
  }
}
