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
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Live Worker Connection Registry Tests")
class LiveWorkerConnectionRegistryTest {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @Test
  @DisplayName("Should accept a replacement worker before dispatching work to it")
  void shouldAcceptReplacementWorkerBeforeDispatchingWorkToIt() throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var replacementClosing = new CountDownLatch(1);
    var continueReplacement = new CountDownLatch(1);
    var original = new BlockingCloseObserver(replacementClosing, continueReplacement);
    registry.register(WORKER_ID, registration(), original);
    var replacementResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var replacement =
          executor.submit(
              () -> registry.register(WORKER_ID, registration(), collecting(replacementResponses)));
      assertThat(replacementClosing.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(registry.dispatch(variantJob())).isTrue();
      continueReplacement.countDown();
      replacement.get(5, TimeUnit.SECONDS);
    } finally {
      continueReplacement.countDown();
    }

    assertThat(replacementResponses)
        .extracting(EstablishWorkerSessionResponse::getCommandCase)
        .containsExactly(
            EstablishWorkerSessionResponse.CommandCase.SESSION_ACCEPTED,
            EstablishWorkerSessionResponse.CommandCase.START_VARIANT);
  }

  @Test
  @DisplayName(
      "Should report dispatch failure when the worker call is cancelled but not yet reaped")
  void shouldReportDispatchFailureWhenWorkerCallIsCancelledButNotYetReaped() {
    var registry = new LiveWorkerConnectionRegistry();
    var observer = new CancellableObserver();
    registry.register(WORKER_ID, registration(), observer);
    observer.cancel();
    var job = variantJob();

    var dispatched = registry.dispatch(job);

    assertThat(dispatched).isFalse();
    assertThat(registry.isRunning(fromProto(job.getStreamSessionId()))).isFalse();
    assertThat(registry.availableSlots()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should survive stopping a session whose worker call is cancelled but not yet reaped")
  void shouldSurviveStoppingSessionWhoseWorkerCallIsCancelledButNotYetReaped() {
    var registry = new LiveWorkerConnectionRegistry();
    var observer = new CancellableObserver();
    registry.register(WORKER_ID, registration(), observer);
    var job = variantJob();
    assertThat(registry.dispatch(job)).isTrue();
    observer.cancel();
    var streamSessionId = fromProto(job.getStreamSessionId());

    registry.stopStreamSession(streamSessionId);

    assertThat(registry.isRunning(streamSessionId)).isFalse();
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

  private static StreamObserver<EstablishWorkerSessionResponse> collecting(
      List<EstablishWorkerSessionResponse> responses) {
    return new StreamObserver<>() {
      @Override
      public void onNext(EstablishWorkerSessionResponse value) {
        responses.add(value);
      }

      @Override
      public void onError(Throwable throwable) {
        throw new AssertionError("Replacement worker should remain connected", throwable);
      }

      @Override
      public void onCompleted() {
        throw new AssertionError("Replacement worker should remain connected");
      }
    };
  }

  private static final class CancellableObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private boolean cancelled;

    private void cancel() {
      cancelled = true;
    }

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      if (cancelled) {
        throw Status.CANCELLED.withDescription("call already cancelled").asRuntimeException();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      cancelled = true;
    }

    @Override
    public void onCompleted() {
      cancelled = true;
    }
  }

  private record BlockingCloseObserver(CountDownLatch closing, CountDownLatch continueClosing)
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      assertThat(value.hasSessionAccepted()).isTrue();
    }

    @Override
    public void onError(Throwable throwable) {
      closing.countDown();
      try {
        continueClosing.await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Replaced worker should fail instead of completing");
    }
  }
}
