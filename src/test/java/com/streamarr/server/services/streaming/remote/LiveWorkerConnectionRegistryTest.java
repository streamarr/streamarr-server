package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
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
  @DisplayName("Should report dispatch failure when a disconnect completes mid-dispatch")
  void shouldReportDispatchFailureWhenDisconnectCompletesMidDispatch() throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var observer = new GatedDispatchObserver();
    var workerSessionId = registry.register(WORKER_ID, registration(), observer);
    var job = variantJob();
    var streamSessionId = fromProto(job.getStreamSessionId());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var dispatching = executor.submit(() -> registry.dispatch(job));
      // The dispatcher is inside tryDispatch, mid-send, before its bookkeeping put.
      assertThat(observer.dispatchReached.await(5, TimeUnit.SECONDS)).isTrue();

      registry.disconnect(WORKER_ID, workerSessionId);
      observer.dispatchRelease.countDown();

      // The job must not be reported as dispatched while tracked nowhere: the race resolves to
      // an honest dispatch failure and recovery moves on to another target.
      assertThat(dispatching.get(5, TimeUnit.SECONDS)).isFalse();
      assertThat(registry.isRunning(streamSessionId)).isFalse();
    }
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
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
  }

  @Test
  @DisplayName("Should report health and capacity only for the requested source namespace")
  void shouldReportHealthAndCapacityOnlyForRequestedSourceNamespace() {
    var registry = new LiveWorkerConnectionRegistry();
    registry.register(WORKER_ID, registration(), new CancellableObserver());
    var unavailableNamespace = UUID.randomUUID();

    assertThat(registry.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isTrue();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
    assertThat(registry.hasConnectedWorker(unavailableNamespace)).isFalse();
    assertThat(registry.availableSlots(unavailableNamespace)).isZero();
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

  @Test
  @DisplayName("Should not block worker disconnect while a segment publish is in progress")
  void shouldNotBlockDisconnectWhileSegmentPublishInProgress() throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var worker =
        WorkerIdentity.newBuilder()
            .setWorkerId(toProto(WORKER_ID))
            .setBootId(toProto(UUID.randomUUID()))
            .build();
    var registration =
        WorkerRegistration.newBuilder()
            .setWorker(worker)
            .setCapabilities(
                WorkerCapabilities.newBuilder().addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID)))
            .setAvailableSlots(1)
            .build();
    var workerSessionId =
        registry.register(WORKER_ID, registration, collecting(new CopyOnWriteArrayList<>()));
    var job = variantJob();
    assertThat(registry.dispatch(job)).isTrue();

    var metadata =
        SegmentUploadMetadata.newBuilder()
            .setWorkerSessionId(toProto(workerSessionId))
            .setWorker(worker)
            .setJobAttemptId(job.getJobAttemptId())
            .setStreamSessionId(job.getStreamSessionId())
            .setJobId(job.getJobId())
            .setVariantLabel(job.getVariant().getVariantLabel())
            .build();

    var inPublish = new CountDownLatch(1);
    var releasePublish = new CountDownLatch(1);
    Runnable blockingPublish =
        () -> {
          inPublish.countDown();
          try {
            releasePublish.await();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
        };

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publishing =
          executor.submit(() -> registry.publishIfAuthorized(WORKER_ID, metadata, blockingPublish));
      assertThat(inPublish.await(5, TimeUnit.SECONDS)).isTrue();

      var disconnected = new CountDownLatch(1);
      executor.submit(
          () -> {
            registry.disconnect(WORKER_ID, workerSessionId);
            disconnected.countDown();
          });
      var disconnectedPromptly = disconnected.await(2, TimeUnit.SECONDS);
      releasePublish.countDown();
      publishing.get(5, TimeUnit.SECONDS);

      assertThat(disconnectedPromptly)
          .as("disconnect must not be blocked by an in-progress segment publish")
          .isTrue();
    }
  }

  @Test
  @DisplayName("Should ignore a stale disconnect after the worker connection was replaced")
  void shouldIgnoreAStaleDisconnectAfterTheWorkerConnectionWasReplaced() {
    var registry = new LiveWorkerConnectionRegistry();
    var staleObserver = new CancellableObserver();
    var staleSessionId = registry.register(WORKER_ID, registration(), staleObserver);
    var freshResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    registry.register(WORKER_ID, registration(), collecting(freshResponses));

    // The stale connection's server-side cancellation races the replacement registration; its
    // late disconnect must not evict the fresh connection or its jobs.
    registry.disconnect(WORKER_ID, staleSessionId);

    assertThat(registry.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isTrue();
    assertThat(registry.dispatch(variantJob())).isTrue();
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

  /** Holds the dispatcher inside its StartVariant send until the test releases it. */
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
        dispatchRelease.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      // The connection under test is closed by the registry; nothing to assert here.
    }

    @Override
    public void onCompleted() {
      // The connection under test is closed by the registry; nothing to assert here.
    }
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
