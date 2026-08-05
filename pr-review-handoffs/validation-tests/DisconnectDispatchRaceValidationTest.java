package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Temporary code-review validation test for a PR #255 finding. Passing means the defect MANIFESTS:
 * a dispatch racing a disconnect reports success while the job is tracked nowhere and no
 * DISCONNECTED terminal evidence is retained for it.
 */
@Tag("UnitTest")
@DisplayName("VALIDATION: disconnect/dispatch race loses terminal evidence (PR #255)")
class DisconnectDispatchRaceValidationTest {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @Test
  @DisplayName(
      "VALIDATION: dispatch overlapping a disconnect reports success but leaves the job untracked"
          + " with no retained evidence")
  void validateDispatchOverlappingDisconnectLosesTheJob() throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var observer = new GatedDispatchObserver();
    var workerSessionId = registry.register(WORKER_ID, registration(), observer);
    var job = variantJob();
    var streamSessionId = fromProto(job.getStreamSessionId());
    var jobAttemptId = fromProto(job.getJobAttemptId());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var dispatching = executor.submit(() -> registry.dispatch(job));
      // The dispatcher is now inside tryDispatch, mid-send, before its bookkeeping put.
      assertThat(observer.dispatchReached.await(5, TimeUnit.SECONDS)).isTrue();

      // Disconnect completes fully: connection removed, activeVariants drained (empty), evidence
      // retained for nothing. drainActiveVariants does not take the connection monitor, so the
      // in-flight dispatch does not block it.
      registry.disconnect(WORKER_ID, workerSessionId);

      observer.dispatchRelease.countDown();
      var dispatched = dispatching.get(5, TimeUnit.SECONDS);

      assertThat(dispatched)
          .as("dispatch reports success even though the worker already fully disconnected")
          .isTrue();
      assertThat(registry.isRunning(streamSessionId))
          .as("the successfully dispatched job is tracked nowhere")
          .isFalse();
      assertThat(registry.consumeEnd(streamSessionId, "720p", jobAttemptId))
          .as("no DISCONNECTED terminal evidence was retained for the lost job")
          .isEmpty();
    }
  }

  private static WorkerRegistration registration() {
    return WorkerRegistration.newBuilder()
        .setWorker(
            WorkerIdentity.newBuilder()
                .setWorkerId(toProto(WORKER_ID))
                .setBootId(toProto(UUID.randomUUID())))
        .setCapabilities(
            WorkerCapabilities.newBuilder().addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID)))
        .setAvailableSlots(1)
        .build();
  }

  private static VariantJob variantJob() {
    return VariantJob.newBuilder()
        .setStreamSessionId(toProto(UUID.randomUUID()))
        .setJobId(toProto(UUID.randomUUID()))
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                .setRelativeKey("movie.mkv"))
        .setVariant(VariantSpec.newBuilder().setVariantLabel("720p"))
        .build();
  }

  private static final class GatedDispatchObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private final CountDownLatch dispatchReached = new CountDownLatch(1);
    private final CountDownLatch dispatchRelease = new CountDownLatch(1);

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      if (!value.hasStartVariant()) {
        return;
      }
      dispatchReached.countDown();
      try {
        dispatchRelease.await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onCompleted() {}
  }
}
