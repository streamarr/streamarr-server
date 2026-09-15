package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a flow-control-blocked control stream does not freeze liveness queries behind the
 * server monitor. This test exhausts the worker's HTTP/2 stream window for real (the client parks
 * inside its first {@code onNext} and never requests another message) and then floods dispatches
 * far past the window. If sends block, the bounded flood wait fails; if gRPC buffers outbound
 * messages asynchronously, the flood and a subsequent liveness probe complete promptly.
 */
@Tag("IntegrationTest")
@DisplayName("Worker Dispatch Flow Control Integration Tests")
class WorkerDispatchFlowControlIT {

  private static final UUID AUTHENTICATED_WORKER_ID =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  private static final int FLOOD_COMMANDS = 2_000;
  private static final int ADVERTISED_SLOTS = 100_000;
  private static final String WINDOW_FILLING_KEY = "k".repeat(8_192);

  @Test
  @DisplayName(
      "Should keep dispatch and liveness queries responsive when a stalled worker exhausts its"
          + " stream window")
  void shouldKeepDispatchAndLivenessResponsiveWhenStalledWorkerExhaustsStreamWindow()
      throws Exception {
    var sessionAccepted = new CountDownLatch(1);
    var releaseClient = new CountDownLatch(1);

    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());
      try {
        var requests =
            TranscodeWorkerServiceGrpc.newStub(channel)
                .establishWorkerSession(new StallingObserver(sessionAccepted, releaseClient));
        requests.onNext(registration());
        assertThat(sessionAccepted.await(5, TimeUnit.SECONDS))
            .as("worker registration must be accepted before the flood")
            .isTrue();

        int dispatched;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
          var flood =
              executor.submit(
                  () -> {
                    var sent = 0;
                    for (var i = 0; i < FLOOD_COMMANDS; i++) {
                      if (server.dispatch(variantJob())) {
                        sent++;
                      }
                    }
                    return sent;
                  });
          dispatched = flood.get(30, TimeUnit.SECONDS);
        }

        boolean unknownSessionRunning;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
          unknownSessionRunning =
              executor
                  .submit(() -> server.isRunning(UUID.randomUUID(), "720p"))
                  .get(2, TimeUnit.SECONDS);
        }

        assertThat(dispatched)
            .as("every dispatch must be accepted despite the exhausted stream window")
            .isEqualTo(FLOOD_COMMANDS);
        assertThat(unknownSessionRunning).isFalse();
      } finally {
        releaseClient.countDown();
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  private WorkerSessionServer server() {
    var configuration = WorkerSessionServerConfiguration.builder().port(0).build();
    return new WorkerSessionServer(configuration, new FakeSegmentStore());
  }

  private ManagedChannel workerChannel(int port) {
    return plaintextChannelBuilder(port, AUTHENTICATED_WORKER_ID).build();
  }

  private EstablishWorkerSessionRequest registration() {
    return EstablishWorkerSessionRequest.newBuilder()
        .setRegistration(
            WorkerRegistration.newBuilder()
                .setWorker(
                    WorkerIdentity.newBuilder()
                        .setWorkerId(uuid(AUTHENTICATED_WORKER_ID))
                        .setBootId(uuid(UUID.randomUUID())))
                .setCapabilities(
                    WorkerCapabilities.newBuilder()
                        .addSourceNamespaceIds(uuid(SOURCE_NAMESPACE_ID)))
                .setAvailableSlots(ADVERTISED_SLOTS))
        .build();
  }

  private VariantJob variantJob() {
    return VariantJob.newBuilder()
        .setStreamSessionId(uuid(UUID.randomUUID()))
        .setJobId(uuid(UUID.randomUUID()))
        .setJobAttemptId(uuid(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(uuid(SOURCE_NAMESPACE_ID))
                .setRelativeKey(WINDOW_FILLING_KEY))
        .setVariant(VariantSpec.newBuilder().setVariantLabel("720p"))
        .build();
  }

  private Uuid uuid(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }

  /**
   * Parks inside the first {@code onNext} so the client never requests another inbound message;
   * gRPC auto flow control then stops issuing window updates and the server-side stream window
   * stays exhausted for the remainder of the test.
   */
  private static final class StallingObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private final CountDownLatch sessionAccepted;
    private final CountDownLatch releaseClient;

    private StallingObserver(CountDownLatch sessionAccepted, CountDownLatch releaseClient) {
      this.sessionAccepted = sessionAccepted;
      this.releaseClient = releaseClient;
    }

    @Override
    public void onNext(EstablishWorkerSessionResponse response) {
      if (response.hasSessionAccepted()) {
        sessionAccepted.countDown();
      }
      try {
        var released = releaseClient.await(60, TimeUnit.SECONDS);
        if (!released) {
          throw new IllegalStateException("Stalled observer was never released");
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      // Only the stalled onNext path is relevant to this validation.
    }

    @Override
    public void onCompleted() {
      // Only the stalled onNext path is relevant to this validation.
    }
  }
}
