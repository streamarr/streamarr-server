package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.transcode.v1.CancelProbeCommand;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.StartProbeCommand;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Worker Probe Dispatch Tests")
class WorkerProbeDispatchTest {

  @Test
  @DisplayName("Should keep a worker available for transcodes when it advertises no probe support")
  void shouldKeepWorkerAvailableForTranscodesWhenItAdvertisesNoProbeSupport() {
    var registry = new LiveWorkerConnectionRegistry();
    var responses = new CapturingResponses();
    registry.register(WORKER_ID, registration().build(), responses);

    assertThat(registry.dispatchProbe(probe().build())).isEmpty();

    var job =
        VariantJob.newBuilder()
            .setJobAttemptId(toProto(UUID.randomUUID()))
            .setSource(source())
            .build();
    assertThat(registry.dispatch(job)).isTrue();
    assertThat(responses.values)
        .extracting(EstablishWorkerSessionResponse::getCommandCase)
        .containsExactly(
            EstablishWorkerSessionResponse.CommandCase.SESSION_ACCEPTED,
            EstablishWorkerSessionResponse.CommandCase.START_VARIANT);
  }

  @ParameterizedTest
  @CsvSource({"1,true,true", "2,true,false", "1,false,false"})
  @DisplayName("Should dispatch a probe only when its version and source namespace are advertised")
  void shouldDispatchProbeOnlyWhenItsVersionAndSourceNamespaceAreAdvertised(
      int version, boolean sourceAvailable, boolean eligible) {
    var registry = new LiveWorkerConnectionRegistry();
    var responses = new CapturingResponses();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    registry.register(WORKER_ID, registration.build(), responses);
    var requestedSource =
        sourceAvailable
            ? source()
            : source().toBuilder().setSourceNamespaceId(toProto(UUID.randomUUID())).build();
    var request = probe().setProbeVersion(version).setSource(requestedSource).build();

    var attempt = registry.dispatchProbe(request);

    assertThat(attempt.isPresent()).isEqualTo(eligible);
    attempt.ifPresent(future -> assertThat(future).isNotDone());
    var expected =
        eligible
            ? List.of(
                StartProbeCommand.newBuilder()
                    .setTarget(registration.getWorker())
                    .setRequest(request)
                    .build())
            : List.<StartProbeCommand>of();
    assertThat(
            responses.values.stream()
                .filter(EstablishWorkerSessionResponse::hasStartProbe)
                .map(EstablishWorkerSessionResponse::getStartProbe)
                .toList())
        .containsExactlyElementsOf(expected);
    assertThat(responses.errors).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName(
      "Should share worker capacity when either a probe or transcode occupies the last slot")
  void shouldShareWorkerCapacityWhenEitherProbeOrTranscodeOccupiesLastSlot(boolean probeFirst) {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var job =
        VariantJob.newBuilder()
            .setJobAttemptId(toProto(UUID.randomUUID()))
            .setSource(source())
            .build();
    var accepted =
        probeFirst ? registry.dispatchProbe(probe().build()).isPresent() : registry.dispatch(job);
    assertThat(accepted).isTrue();

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isZero();
    assertThat(registry.dispatchProbe(probe().build())).isEmpty();
    assertThat(
            registry.dispatch(job.toBuilder().setJobAttemptId(toProto(UUID.randomUUID())).build()))
        .isFalse();
  }

  @Test
  @DisplayName(
      "Should complete a probe and release its slot when the current session returns the requested version")
  void shouldCompleteProbeAndReleaseItsSlotWhenCurrentSessionReturnsRequestedVersion()
      throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(2);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().setProbeVersion(2).build();
    var attempt = registry.dispatchProbe(request).orElseThrow();
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(2)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();

    assertThat(registry.completeProbe(WORKER_ID, sessionId, result)).isTrue();

    assertThat(attempt.get(1, TimeUnit.SECONDS)).isEqualTo(result);
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
    assertThat(registry.dispatchProbe(probe().setProbeVersion(2).build())).isPresent();
  }

  @Test
  @DisplayName(
      "Should fail the pending probe without accepting data when its reply has another version")
  void shouldFailPendingProbeWithoutAcceptingDataWhenItsReplyHasAnotherVersion() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(2);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().setProbeVersion(2).build();
    var attempt = registry.dispatchProbe(request).orElseThrow();
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(1)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();

    assertThat(registry.completeProbe(WORKER_ID, sessionId, result)).isFalse();

    assertThatThrownBy(() -> attempt.get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeExecutionException.class);
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
  }

  @Test
  @DisplayName("Should fail a pending probe for retry when its worker session disconnects")
  void shouldFailPendingProbeForRetryWhenItsWorkerSessionDisconnects() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var attempt = registry.dispatchProbe(probe().build()).orElseThrow();

    registry.disconnect(WORKER_ID, sessionId);

    assertThatThrownBy(() -> attempt.get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeExecutionException.class);
    assertThat(registry.dispatchProbe(probe().build())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should retain probe capacity after cancellation until the worker acknowledges termination")
  void shouldRetainProbeCapacityAfterCancellationUntilWorkerAcknowledgesTermination() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var responses = new CapturingResponses();
    var sessionId = registry.register(WORKER_ID, registration.build(), responses);
    var request = probe().build();
    var attempt = registry.dispatchProbe(request).orElseThrow();

    assertThat(attempt.cancel(true)).isTrue();

    var expected =
        CancelProbeCommand.newBuilder()
            .setTarget(registration.getWorker())
            .setProbeAttemptId(request.getProbeAttemptId())
            .build();
    assertThat(responses.values.getLast().getCancelProbe()).isEqualTo(expected);
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isZero();
    assertThat(registry.dispatchProbe(probe().build())).isEmpty();
    var reply =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(1)
            .setFailure(ProbeFailure.PROBE_FAILURE_CANCELLED)
            .build();

    assertThat(registry.completeProbe(WORKER_ID, sessionId, reply)).isTrue();

    assertThat(attempt).isCancelled();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should fail a disconnected probe without waiting for a concurrent segment publication")
  void shouldFailDisconnectedProbeWithoutWaitingForConcurrentSegmentPublication() throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration().setAvailableSlots(2);
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var attempt = registry.dispatchProbe(probe().build()).orElseThrow();
    var job =
        VariantJob.newBuilder()
            .setJobAttemptId(toProto(UUID.randomUUID()))
            .setSource(source())
            .build();
    assertThat(registry.dispatch(job)).isTrue();
    var metadata =
        SegmentUploadMetadata.newBuilder()
            .setWorker(registration.getWorker())
            .setWorkerSessionId(toProto(sessionId))
            .setJobAttemptId(job.getJobAttemptId())
            .build();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publishing =
          executor.submit(
              () ->
                  registry.publishIfAuthorized(
                      WORKER_ID, metadata, () -> holdPublication(entered, release)));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        executor.submit(() -> registry.disconnect(WORKER_ID, sessionId)).get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> attempt.get(1, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(ProbeExecutionException.class);
      } finally {
        release.countDown();
      }

      assertThat(publishing.get(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should preserve the original pending probe when the same attempt is dispatched again")
  void shouldPreserveOriginalPendingProbeWhenSameAttemptIsDispatchedAgain() throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration().setAvailableSlots(2);
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var responses = new CapturingResponses();
    var sessionId = registry.register(WORKER_ID, registration.build(), responses);
    var request = probe().build();
    var original = registry.dispatchProbe(request).orElseThrow();

    assertThat(registry.dispatchProbe(request)).isEmpty();

    var reply =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(1)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();
    assertThat(registry.completeProbe(WORKER_ID, sessionId, reply)).isTrue();
    assertThat(original.get(1, TimeUnit.SECONDS)).isEqualTo(reply);
    assertThat(responses.values.stream().filter(EstablishWorkerSessionResponse::hasStartProbe))
        .hasSize(1);
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(2);
  }

  private static void holdPublication(CountDownLatch entered, CountDownLatch release) {
    entered.countDown();
    try {
      release.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"worker", "session", "attempt"})
  @DisplayName(
      "Should retain the pending probe when a reply belongs to another worker session or attempt")
  void shouldRetainPendingProbeWhenReplyBelongsToAnotherWorkerSessionOrAttempt(String mismatch)
      throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().build();
    var pending = registry.dispatchProbe(request).orElseThrow();
    var reply =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(1)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();
    var reportedWorker = mismatch.equals("worker") ? UUID.randomUUID() : WORKER_ID;
    var reportedSession = mismatch.equals("session") ? UUID.randomUUID() : sessionId;
    var reportedReply =
        mismatch.equals("attempt")
            ? reply.toBuilder().setProbeAttemptId(toProto(UUID.randomUUID())).build()
            : reply;

    assertThat(registry.completeProbe(reportedWorker, reportedSession, reportedReply)).isFalse();

    assertThat(pending).isNotDone();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isZero();
    assertThat(registry.completeProbe(WORKER_ID, sessionId, reply)).isTrue();
    assertThat(pending.get(1, TimeUnit.SECONDS)).isEqualTo(reply);
    assertThat(registry.completeProbe(WORKER_ID, sessionId, reply)).isFalse();
  }

  @Test
  @DisplayName(
      "Should decline a probe dispatch when its connection disappears during the command send")
  void shouldDeclineProbeDispatchWhenItsConnectionDisappearsDuringCommandSend() throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var entered = new CountDownLatch(1);
    var release = new Semaphore(0);
    var responses =
        new CapturingResponses(
            response -> {
              if (response.hasStartProbe()) {
                entered.countDown();
                release.acquireUninterruptibly();
              }
            });
    var sessionId = registry.register(WORKER_ID, registration.build(), responses);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var dispatch = executor.submit(() -> registry.dispatchProbe(probe().build()));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        registry.disconnect(WORKER_ID, sessionId);
      } finally {
        release.release();
      }

      assertThat(dispatch.get(5, TimeUnit.SECONDS)).isEmpty();
    }
  }

  private WorkerRegistration.Builder registration() {
    return WorkerRegistration.newBuilder()
        .setWorker(
            WorkerIdentity.newBuilder()
                .setWorkerId(toProto(WORKER_ID))
                .setBootId(toProto(UUID.randomUUID())))
        .setCapabilities(
            WorkerCapabilities.newBuilder().addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID)))
        .setAvailableSlots(1);
  }

  @Test
  @DisplayName(
      "Should fail an abandoned probe and ignore its old session after a worker reconnects")
  void shouldFailAbandonedProbeAndIgnoreItsOldSessionAfterWorkerReconnects() throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var oldSession = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().build();
    var abandoned = registry.dispatchProbe(request).orElseThrow();

    var replacementSession =
        registry.register(WORKER_ID, registration.build(), new CapturingResponses());

    assertThatThrownBy(() -> abandoned.get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeExecutionException.class);
    var replacement = registry.dispatchProbe(request).orElseThrow();
    var reply =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(1)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();
    assertThat(registry.completeProbe(WORKER_ID, oldSession, reply)).isFalse();
    assertThat(replacement).isNotDone();
    assertThat(registry.completeProbe(WORKER_ID, replacementSession, reply)).isTrue();
    assertThat(replacement.get(1, TimeUnit.SECONDS)).isEqualTo(reply);
  }

  private ProbeRequest.Builder probe() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(1)
        .setSource(source());
  }

  @Test
  @DisplayName("Should release probe capacity when sending its start command fails")
  void shouldReleaseProbeCapacityWhenSendingItsStartCommandFails() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var responses =
        new CapturingResponses(
            response -> {
              if (response.hasStartProbe()) {
                throw new IllegalStateException("Simulated command delivery failure");
              }
            });
    registry.register(WORKER_ID, registration.build(), responses);

    assertThat(registry.dispatchProbe(probe().build())).isEmpty();

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
    assertThat(responses.values)
        .extracting(EstablishWorkerSessionResponse::getCommandCase)
        .containsExactly(EstablishWorkerSessionResponse.CommandCase.SESSION_ACCEPTED);
  }

  private MediaSourceRef source() {
    return MediaSourceRef.newBuilder()
        .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
        .setRelativeKey("movie.mkv")
        .build();
  }

  private static final class CapturingResponses
      implements StreamObserver<EstablishWorkerSessionResponse> {
    private final List<EstablishWorkerSessionResponse> values = new CopyOnWriteArrayList<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final Consumer<EstablishWorkerSessionResponse> beforeCapture;

    private CapturingResponses() {
      this(_ -> {});
    }

    private CapturingResponses(Consumer<EstablishWorkerSessionResponse> beforeCapture) {
      this.beforeCapture = beforeCapture;
    }

    @Override
    public void onNext(EstablishWorkerSessionResponse response) {
      beforeCapture.accept(response);
      values.add(response);
    }

    @Override
    public void onError(Throwable error) {
      errors.add(error);
    }

    @Override
    public void onCompleted() {
      // No asynchronous work is needed by the recording observer.
    }
  }
}
