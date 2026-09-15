package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.services.streaming.FfprobeService;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FakeFfprobeService implements FfprobeService {

  private RuntimeException failure;
  private Runnable duringProbe = () -> {};
  private final AtomicInteger probeCount = new AtomicInteger();
  private final AtomicReference<ProbeExecutionRequest> lastRequest = new AtomicReference<>();
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
  public ProbeOutcome probe(ProbeExecutionRequest request) {
    probeCount.incrementAndGet();
    lastRequest.set(request);
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

  public Optional<ProbeExecutionRequest> lastRequest() {
    return Optional.ofNullable(lastRequest.get());
  }

  public boolean wasLastProbeOnVirtualThread() {
    return lastProbeOnVirtualThread;
  }
}
