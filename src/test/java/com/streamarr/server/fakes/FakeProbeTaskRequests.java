package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.task.ProbeAttemptFailure;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Records desired inputs as the scheduler-backed requests do. A test decides what a dispatcher does
 * with each request, and a dispatcher that throws rejects the request; by default every request
 * stays pending.
 */
public class FakeProbeTaskRequests implements ProbeTaskRequests {

  private final FakeMediaFileContainerInfoRepository outcomes;
  private final List<ProbeTaskRequest> requests = new CopyOnWriteArrayList<>();
  private final BlockingQueue<ProbeTaskRequest> unawaited = new LinkedBlockingQueue<>();
  private volatile Consumer<ProbeTaskRequest> dispatcher = _ -> {};

  public FakeProbeTaskRequests(FakeMediaFileContainerInfoRepository outcomes) {
    this.outcomes = outcomes;
  }

  @Override
  public void request(ProbeTaskRequest request) {
    if (!outcomes.trySaveProbeRequest(request.mediaFileId(), request.inputs())) {
      return;
    }

    dispatcher.accept(request);
    requests.add(request);
    unawaited.add(request);
  }

  @Override
  public void requestRetryingFailure(ProbeTaskRequest request) {
    request(request);
  }

  public void dispatchWith(Consumer<ProbeTaskRequest> dispatcher) {
    this.dispatcher = dispatcher;
  }

  /** Dispatches every later request to a probe that succeeds at once. */
  public void succeedEachRequest() {
    dispatchWith(this::succeed);
  }

  /**
   * Returns the oldest dispatched request that no earlier call returned, waiting up to {@code
   * bound} for one to arrive.
   */
  public ProbeTaskRequest awaitRequest(Duration bound) throws InterruptedException {
    var request = unawaited.poll(bound.toNanos(), TimeUnit.NANOSECONDS);
    if (request == null) {
      throw new AssertionError("No probe request arrived within " + bound);
    }

    return request;
  }

  public List<ProbeTaskRequest> requests() {
    return List.copyOf(requests);
  }

  public void succeed(ProbeTaskRequest request) {
    outcomes.publish(
        ProbePublication.builder()
            .mediaFileId(request.mediaFileId())
            .snapshot(request.snapshot())
            .probeVersion(request.probeVersion())
            .outcome(
                ProbeFixture.completeProbe(
                    MediaProbe.builder()
                        .duration(Duration.ofMinutes(90))
                        .videoCodec("h264")
                        .width(1920)
                        .height(1080)
                        .build()))
            .build());
  }

  public void fail(ProbeTaskRequest request, ItemFailureReason reason, Instant failedAt) {
    outcomes.trySaveProbeFailure(
        request.mediaFileId(),
        request.inputs(),
        ProbeAttemptFailure.builder()
            .reason(reason)
            .detail("Probe attempt failed")
            .failedAt(failedAt)
            .build());
  }
}
