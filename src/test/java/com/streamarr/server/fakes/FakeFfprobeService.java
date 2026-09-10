package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.services.streaming.FfprobeService;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

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

    return ProbeFixture.completeProbe(defaultProbe);
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
