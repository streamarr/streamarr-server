package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.transcode.v1.CancelProbeCommand;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
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
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Worker Probe Dispatch Tests")
class WorkerProbeDispatchTest {

  // The registry visits workers in hash order: WORKER_ID hashes to 0 and this ID to 1.
  private static final UUID LATER_VISITED_WORKER = new UUID(0, 1);

  @ParameterizedTest(name = "explicit nil={0}")
  @ValueSource(booleans = {false, true})
  @DisplayName("Should refuse a probe when its attempt identifier is omitted or nil")
  void shouldRefuseProbeWhenItsAttemptIdentifierIsOmittedOrNil(boolean explicitNil)
      throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().clearProbeAttemptId();
    if (explicitNil) {
      request.setProbeAttemptId(toProto(new UUID(0, 0)));
    }

    var decoded = ProbeRequest.parseFrom(request.build().toByteArray());

    assertThat(registry.dispatchProbe(decoded))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.INVALID_REQUEST));
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should complete another worker probe when replacement waits for segment publication")
  void shouldCompleteAnotherWorkerProbeWhenReplacementWaitsForSegmentPublication()
      throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var oldRegistration = registration().build();
    var oldSession = registry.register(WORKER_ID, oldRegistration, new CapturingResponses());
    var job =
        VariantJob.newBuilder()
            .setStreamSessionId(toProto(UUID.randomUUID()))
            .setJobId(toProto(UUID.randomUUID()))
            .setJobAttemptId(toProto(UUID.randomUUID()))
            .setSource(source())
            .build();
    assertThat(registry.dispatch(job)).isTrue();
    var secondWorker = UUID.randomUUID();
    var secondRegistration = registration();
    secondRegistration.getWorkerBuilder().setWorkerId(toProto(secondWorker));
    secondRegistration.getCapabilitiesBuilder().addProbeVersions(1);
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var secondSession =
        Context.current()
            .withValue(WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID, secondWorker)
            .call(() -> service.establishWorkerSession(new CapturingResponses()));
    secondSession.onNext(
        EstablishWorkerSessionRequest.newBuilder().setRegistration(secondRegistration).build());
    var request = probe().build();
    var pending = dispatched(registry.dispatchProbe(request));
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(1)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();
    var event = EstablishWorkerSessionRequest.newBuilder().setProbeResult(result).build();
    var metadata =
        SegmentUploadMetadata.newBuilder()
            .setWorker(oldRegistration.getWorker())
            .setWorkerSessionId(toProto(oldSession))
            .setStreamSessionId(job.getStreamSessionId())
            .setJobId(job.getJobId())
            .setJobAttemptId(job.getJobAttemptId())
            .build();
    var publicationEntered = new CountDownLatch(1);
    var releasePublication = new CountDownLatch(1);
    var replacementAccepted = new CountDownLatch(1);
    var replacementResponses =
        new CapturingResponses(
            response -> {
              if (response.hasSessionAccepted()) {
                replacementAccepted.countDown();
              }
            });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publishing =
          executor.submit(
              () ->
                  registry.publishIfAuthorized(
                      WORKER_ID,
                      metadata,
                      () -> holdPublication(publicationEntered, releasePublication)));
      try {
        assertThat(publicationEntered.await(5, TimeUnit.SECONDS)).isTrue();
        var replacing =
            executor.submit(
                () -> registry.register(WORKER_ID, oldRegistration, replacementResponses));
        assertThat(replacementAccepted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(replacing)
            .as("The replacement must still wait for the old session's segment publication")
            .isNotDone();
        var receiving = executor.submit(() -> secondSession.onNext(event));
        assertThat(pending.get(1, TimeUnit.SECONDS))
            .as("The other worker's reply must complete its probe while the replacement waits")
            .isEqualTo(result);
        receiving.get(1, TimeUnit.SECONDS);
        assertThat(publishing).as("The held segment publication must still be blocked").isNotDone();
      } finally {
        releasePublication.countDown();
      }

      assertThat(publishing.get(5, TimeUnit.SECONDS))
          .as("The released segment publication must succeed")
          .isTrue();
    }
  }

  @ParameterizedTest(name = "advertised version={0}, explicit zero={1}")
  @CsvSource({"0,false", "0,true", "1,false", "1,true"})
  @DisplayName("Should reject unversioned probes even when a worker advertises version zero")
  void shouldRejectUnversionedProbesEvenWhenWorkerAdvertisesVersionZero(
      int advertisedVersion, boolean explicitZero) throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(advertisedVersion);
    var responses = new CapturingResponses();
    registry.register(WORKER_ID, registration.build(), responses);
    var request = probe().clearProbeVersion();
    if (explicitZero) {
      request.setProbeVersion(0);
    }

    var decodedRequest = ProbeRequest.parseFrom(request.build().toByteArray());
    var dispatched = registry.dispatchProbe(decodedRequest);

    assertSoftly(
        softly -> {
          softly
              .assertThat(dispatched)
              .as("unversioned request must be rejected")
              .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.INVALID_REQUEST));
          softly
              .assertThat(
                  responses.values.stream().filter(EstablishWorkerSessionResponse::hasStartProbe))
              .as("worker must not receive an unversioned start command")
              .isEmpty();
        });
  }

  @ParameterizedTest(name = "explicit zero={0}")
  @ValueSource(booleans = {false, true})
  @DisplayName("Should reject an unversioned reply when the pending probe requested version one")
  void shouldRejectUnversionedReplyWhenPendingProbeRequestedVersionOne(boolean explicitZero)
      throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().build();
    var pending = dispatched(registry.dispatchProbe(request));
    var reply =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
    if (explicitZero) {
      reply.setProbeVersion(0);
    }

    var decoded = ProbeAttemptResult.parseFrom(reply.build().toByteArray());

    assertThat(registry.completeProbe(WORKER_ID, sessionId, decoded)).isFalse();
    assertThatThrownBy(() -> pending.get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeExecutionException.class);
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
  }

  @Test
  @DisplayName("Should keep a worker available for transcodes when it advertises no probe support")
  void shouldKeepWorkerAvailableForTranscodesWhenItAdvertisesNoProbeSupport() {
    var registry = new LiveWorkerConnectionRegistry();
    var responses = new CapturingResponses();
    registry.register(WORKER_ID, registration().build(), responses);

    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.NO_COMPATIBLE_WORKER));

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

  @Test
  @DisplayName("Should dispatch a probe when its version and source namespace are advertised")
  void shouldDispatchProbeWhenItsVersionAndSourceNamespaceAreAdvertised() {
    var registry = new LiveWorkerConnectionRegistry();
    var responses = new CapturingResponses();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    registry.register(WORKER_ID, registration.build(), responses);
    var request = probe().build();

    var attempt = dispatched(registry.dispatchProbe(request));

    assertThat(attempt).isNotDone();
    assertThat(startCommands(responses))
        .containsExactly(
            StartProbeCommand.newBuilder()
                .setTarget(registration.getWorker())
                .setRequest(request)
                .build());
    assertThat(responses.errors).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(Incompatibility.class)
  @DisplayName(
      "Should refuse a probe as incompatible when the worker does not advertise its version or"
          + " source namespace")
  void shouldRefuseProbeAsIncompatibleWhenWorkerDoesNotAdvertiseItsVersionOrSourceNamespace(
      Incompatibility incompatibility) {
    var registry = new LiveWorkerConnectionRegistry();
    var responses = new CapturingResponses();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    registry.register(WORKER_ID, registration.build(), responses);

    var attempt = registry.dispatchProbe(incompatibleProbe(incompatibility));

    assertThat(attempt).isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.NO_COMPATIBLE_WORKER));
    assertThat(startCommands(responses)).isEmpty();
    assertThat(responses.errors).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(Incompatibility.class)
  @DisplayName(
      "Should refuse a probe as incompatible, not busy, when a stream fills the incompatible"
          + " worker")
  void shouldRefuseProbeAsIncompatibleNotBusyWhenStreamFillsIncompatibleWorker(
      Incompatibility incompatibility) {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    assertThat(registry.dispatch(stream())).isTrue();

    var attempt = registry.dispatchProbe(incompatibleProbe(incompatibility));

    assertThat(attempt)
        .as("A worker that can never run the probe must not make it wait as busy")
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.NO_COMPATIBLE_WORKER));
  }

  @Test
  @DisplayName("Should refuse a probe as invalid when its source is missing")
  void shouldRefuseProbeAsInvalidWhenItsSourceIsMissing() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    registry.register(WORKER_ID, registration.build(), new CapturingResponses());

    assertThat(registry.dispatchProbe(probe().clearSource().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.INVALID_REQUEST));
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
        probeFirst
            ? registry.dispatchProbe(probe().build()) instanceof ProbeDispatch.Dispatched
            : registry.dispatch(job);
    assertThat(accepted).isTrue();

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isZero();
    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(
            registry.dispatch(job.toBuilder().setJobAttemptId(toProto(UUID.randomUUID())).build()))
        .isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("compatibleAndIncompatibleVisitingOrders")
  @DisplayName(
      "Should refuse a probe as busy when a stream fills the compatible worker and another worker"
          + " lacks the probe version")
  void shouldRefuseProbeAsBusyWhenStreamFillsCompatibleWorkerAndAnotherLacksProbeVersion(
      UUID compatibleWorker, UUID incompatibleWorker) {
    var registry = new LiveWorkerConnectionRegistry();
    var compatible = registration(compatibleWorker);
    compatible.getCapabilitiesBuilder().addProbeVersions(1);
    var compatibleSession =
        registry.register(compatibleWorker, compatible.build(), new CapturingResponses());
    var incompatible = registration(incompatibleWorker).setAvailableSlots(2);
    incompatible.getCapabilitiesBuilder().addProbeVersions(2);
    registry.register(incompatibleWorker, incompatible.build(), new CapturingResponses());
    assertThat(registry.dispatchTo(new ExecutionTargetId(compatibleSession.toString()), stream()))
        .isTrue();

    var attempt = registry.dispatchProbe(probe().build());

    assertThat(attempt).isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fullAndFreeVisitingOrders")
  @DisplayName(
      "Should dispatch a probe to a free compatible worker when a stream fills another compatible"
          + " worker")
  void shouldDispatchProbeToFreeCompatibleWorkerWhenStreamFillsAnotherCompatibleWorker(
      UUID fullWorker, UUID freeWorker) {
    var registry = new LiveWorkerConnectionRegistry();
    var full = registration(fullWorker);
    full.getCapabilitiesBuilder().addProbeVersions(1);
    var fullSession = registry.register(fullWorker, full.build(), new CapturingResponses());
    var freeResponses = new CapturingResponses();
    var free = registration(freeWorker);
    free.getCapabilitiesBuilder().addProbeVersions(1);
    registry.register(freeWorker, free.build(), freeResponses);
    assertThat(registry.dispatchTo(new ExecutionTargetId(fullSession.toString()), stream()))
        .isTrue();
    var request = probe().build();

    var attempt = registry.dispatchProbe(request);

    assertThat(attempt).isInstanceOf(ProbeDispatch.Dispatched.class);
    assertThat(startCommands(freeResponses))
        .extracting(StartProbeCommand::getRequest)
        .containsExactly(request);
  }

  static Stream<Arguments> compatibleAndIncompatibleVisitingOrders() {
    return Stream.of(
        arguments(named("compatible worker visited first", WORKER_ID), LATER_VISITED_WORKER),
        arguments(named("incompatible worker visited first", LATER_VISITED_WORKER), WORKER_ID));
  }

  static Stream<Arguments> fullAndFreeVisitingOrders() {
    return Stream.of(
        arguments(named("full worker visited first", WORKER_ID), LATER_VISITED_WORKER),
        arguments(named("free worker visited first", LATER_VISITED_WORKER), WORKER_ID));
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
    var attempt = dispatched(registry.dispatchProbe(request));
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(2)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();

    assertThat(registry.completeProbe(WORKER_ID, sessionId, result)).isTrue();

    assertThat(attempt.get(1, TimeUnit.SECONDS)).isEqualTo(result);
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
    assertThat(registry.dispatchProbe(probe().setProbeVersion(2).build()))
        .isInstanceOf(ProbeDispatch.Dispatched.class);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "2|1|Worker probe reply version mismatch: expected 2, received 1",
        "-1|1|Worker probe reply version mismatch: expected 4294967295, received 1",
        "2|-1|Worker probe reply version mismatch: expected 2, received 4294967295"
      },
      delimiter = '|')
  @DisplayName(
      "Should fail the pending probe without accepting data when its reply has another version")
  void shouldFailPendingProbeWithoutAcceptingDataWhenItsReplyHasAnotherVersion(
      int requestedVersion, int reportedVersion, String diagnostic) {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(requestedVersion);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().setProbeVersion(requestedVersion).build();
    var attempt = dispatched(registry.dispatchProbe(request));
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(reportedVersion)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();

    assertThat(registry.completeProbe(WORKER_ID, sessionId, result)).isFalse();

    assertThatThrownBy(() -> attempt.get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeExecutionException.class)
        .hasRootCauseMessage(diagnostic);
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
  }

  @Test
  @DisplayName("Should fail a pending probe for retry when its worker session disconnects")
  void shouldFailPendingProbeForRetryWhenItsWorkerSessionDisconnects() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().build();
    var attempt = dispatched(registry.dispatchProbe(request));

    registry.disconnect(WORKER_ID, sessionId);

    assertThatThrownBy(() -> attempt.get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeExecutionException.class);
  }

  @Test
  @DisplayName(
      "Should refuse a probe as having no connected worker when the only worker disconnects")
  void shouldRefuseProbeAsHavingNoConnectedWorkerWhenTheOnlyWorkerDisconnects() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());

    registry.disconnect(WORKER_ID, sessionId);

    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.NO_CONNECTED_WORKER));
  }

  @Test
  @DisplayName("Should dispatch the same attempt again when its disconnected worker reconnects")
  void shouldDispatchSameAttemptAgainWhenItsDisconnectedWorkerReconnects() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().build();
    dispatched(registry.dispatchProbe(request));
    registry.disconnect(WORKER_ID, sessionId);

    registry.register(WORKER_ID, registration.build(), new CapturingResponses());

    assertThat(registry.dispatchProbe(request)).isInstanceOf(ProbeDispatch.Dispatched.class);
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
    var attempt = dispatched(registry.dispatchProbe(request));

    assertThat(attempt.cancel(true)).isTrue();

    var expected =
        CancelProbeCommand.newBuilder()
            .setTarget(registration.getWorker())
            .setProbeAttemptId(request.getProbeAttemptId())
            .build();
    assertThat(responses.values.getLast().getCancelProbe()).isEqualTo(expected);
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isZero();
    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
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
    var attempt = dispatched(registry.dispatchProbe(probe().build()));
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
    var original = dispatched(registry.dispatchProbe(request));

    assertThat(registry.dispatchProbe(request))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.ATTEMPT_IN_PROGRESS));

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
  @ValueSource(ints = {1, 2})
  @DisplayName(
      "Should start an attempt only once when the same probe is dispatched to eligible workers")
  void shouldStartAttemptOnlyOnceWhenSameProbeIsDispatchedToEligibleWorkers(int workerCount) {
    var registry = new LiveWorkerConnectionRegistry();
    var responses = new CapturingResponses();
    for (var workerIndex = 0; workerIndex < workerCount; workerIndex++) {
      var workerId = UUID.randomUUID();
      var registration = registration().setAvailableSlots(2);
      registration.getWorkerBuilder().setWorkerId(toProto(workerId));
      registration.getCapabilitiesBuilder().addProbeVersions(1);
      registry.register(workerId, registration.build(), responses);
    }

    var request = probe().build();
    var original = dispatched(registry.dispatchProbe(request));

    var duplicate = registry.dispatchProbe(request);

    assertThat(
            responses.values.stream()
                .filter(EstablishWorkerSessionResponse::hasStartProbe)
                .map(EstablishWorkerSessionResponse::getStartProbe))
        .as(
            "one start command across %s eligible worker(s) for attempt %s",
            workerCount, request.getProbeAttemptId())
        .hasSize(1);
    assertThat(duplicate).isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.ATTEMPT_IN_PROGRESS));
    assertThat(original).isNotDone();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(workerCount * 2 - 1);
  }

  @ParameterizedTest
  @EnumSource(ReplyMismatch.class)
  @DisplayName(
      "Should retain the pending probe when a reply belongs to another worker session or attempt")
  void shouldRetainPendingProbeWhenReplyBelongsToAnotherWorkerSessionOrAttempt(
      ReplyMismatch mismatch) throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().build();
    var pending = dispatched(registry.dispatchProbe(request));
    var reply = invalidMediaReply(request);

    var accepted =
        switch (mismatch) {
          case WORKER -> registry.completeProbe(UUID.randomUUID(), sessionId, reply);
          case SESSION -> registry.completeProbe(WORKER_ID, UUID.randomUUID(), reply);
          case ATTEMPT ->
              registry.completeProbe(
                  WORKER_ID,
                  sessionId,
                  reply.toBuilder().setProbeAttemptId(toProto(UUID.randomUUID())).build());
        };

    assertThat(accepted).isFalse();
    assertThat(pending).isNotDone();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isZero();
    assertThat(registry.completeProbe(WORKER_ID, sessionId, reply)).isTrue();
    assertThat(pending.get(1, TimeUnit.SECONDS)).isEqualTo(reply);
  }

  @Test
  @DisplayName("Should reject a repeated reply when its probe already completed")
  void shouldRejectRepeatedReplyWhenItsProbeAlreadyCompleted() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().build();
    dispatched(registry.dispatchProbe(request));
    var reply = invalidMediaReply(request);
    assertThat(registry.completeProbe(WORKER_ID, sessionId, reply)).isTrue();

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
    var request = probe().build();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var dispatch = executor.submit(() -> registry.dispatchProbe(request));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        registry.disconnect(WORKER_ID, sessionId);
      } finally {
        release.release();
      }

      assertThat(dispatch.get(5, TimeUnit.SECONDS))
          .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKER_UNREACHABLE));
    }

    registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    assertThat(registry.dispatchProbe(request)).isInstanceOf(ProbeDispatch.Dispatched.class);
  }

  @Test
  @DisplayName(
      "Should reserve a cancelled attempt across workers until termination is acknowledged")
  void shouldReserveCancelledAttemptAcrossWorkersUntilTerminationIsAcknowledged() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().build();
    var attempt = dispatched(registry.dispatchProbe(request));
    var otherWorker = UUID.randomUUID();
    registration.getWorkerBuilder().setWorkerId(toProto(otherWorker));
    registry.register(otherWorker, registration.build(), new CapturingResponses());

    assertThat(attempt.cancel(true)).isTrue();

    assertThat(registry.dispatchProbe(request))
        .as("A free worker must not start an attempt another worker is still cancelling")
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.ATTEMPT_IN_PROGRESS));
    var acknowledgement =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(1)
            .setFailure(ProbeFailure.PROBE_FAILURE_CANCELLED)
            .build();
    assertThat(registry.completeProbe(WORKER_ID, sessionId, acknowledgement)).isTrue();
    assertThat(registry.dispatchProbe(request)).isInstanceOf(ProbeDispatch.Dispatched.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  @DisplayName("Should release an attempt reservation when the worker replies with any version")
  void shouldReleaseAttemptReservationWhenWorkerRepliesWithAnyVersion(int replyVersion) {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var sessionId = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().build();
    assertThat(registry.dispatchProbe(request)).isInstanceOf(ProbeDispatch.Dispatched.class);
    var reply =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(replyVersion)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();

    assertThat(registry.completeProbe(WORKER_ID, sessionId, reply)).isEqualTo(replyVersion == 1);

    assertThat(registry.dispatchProbe(request)).isInstanceOf(ProbeDispatch.Dispatched.class);
  }

  @Test
  @DisplayName("Should release an attempt reservation when its first delivery fails")
  void shouldReleaseAttemptReservationWhenItsFirstDeliveryFails() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var failNextDelivery = new AtomicBoolean(true);
    var responses =
        new CapturingResponses(
            response -> {
              if (response.hasStartProbe() && failNextDelivery.getAndSet(false)) {
                throw new IllegalStateException("Simulated command delivery failure");
              }
            });
    registry.register(WORKER_ID, registration.build(), responses);
    var request = probe().build();

    assertThat(registry.dispatchProbe(request))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKER_UNREACHABLE));

    assertThat(registry.dispatchProbe(request)).isInstanceOf(ProbeDispatch.Dispatched.class);
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
  @DisplayName("Should fail an abandoned probe when its worker reconnects")
  void shouldFailAbandonedProbeWhenItsWorkerReconnects() {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var abandoned = dispatched(registry.dispatchProbe(probe().build()));

    registry.register(WORKER_ID, registration.build(), new CapturingResponses());

    assertThatThrownBy(() -> abandoned.get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeExecutionException.class);
  }

  @Test
  @DisplayName("Should ignore a reply from the old session when its worker reconnects")
  void shouldIgnoreReplyFromOldSessionWhenItsWorkerReconnects() throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    var oldSession = registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().build();
    dispatched(registry.dispatchProbe(request));
    var replacementSession =
        registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var replacement = dispatched(registry.dispatchProbe(request));
    var reply = invalidMediaReply(request);

    assertThat(registry.completeProbe(WORKER_ID, oldSession, reply)).isFalse();

    assertThat(replacement).isNotDone();
    assertThat(registry.completeProbe(WORKER_ID, replacementSession, reply)).isTrue();
    assertThat(replacement.get(1, TimeUnit.SECONDS)).isEqualTo(reply);
  }

  private WorkerRegistration.Builder registration(UUID workerId) {
    var registration = registration();
    registration.getWorkerBuilder().setWorkerId(toProto(workerId));
    return registration;
  }

  private VariantJob stream() {
    return VariantJob.newBuilder()
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(source())
        .build();
  }

  private ProbeRequest.Builder probe() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(1)
        .setSource(source());
  }

  @Test
  @DisplayName("Should fail the superseded probe before a replacement starts the same attempt")
  void shouldFailSupersededProbeBeforeReplacementStartsSameAttempt() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var trial = 0; trial < 2_000; trial++) {
        assertThat(replacementStartsBeforeSupersededProbeFails(executor))
            .as(
                "replacement must not start while the superseded future is pending (trial %s)",
                trial)
            .isFalse();
      }
    }
  }

  private boolean replacementStartsBeforeSupersededProbeFails(ExecutorService executor)
      throws Exception {
    var registry = new LiveWorkerConnectionRegistry();
    var registration = registration();
    registration.getCapabilitiesBuilder().addProbeVersions(1);
    registry.register(WORKER_ID, registration.build(), new CapturingResponses());
    var request = probe().build();
    var superseded = dispatched(registry.dispatchProbe(request));
    var acceptanceEntered = new CountDownLatch(1);
    var releaseAcceptance = new Semaphore(0);
    var dispatchStarted = new CountDownLatch(1);
    var startedBeforeFailure = new AtomicBoolean();
    var responses =
        new CapturingResponses(
            response -> {
              if (response.hasSessionAccepted()) {
                acceptanceEntered.countDown();
                releaseAcceptance.acquireUninterruptibly();
              }

              if (response.hasStartProbe()) {
                startedBeforeFailure.set(!superseded.isDone());
              }
            });
    var replacing =
        executor.submit(() -> registry.register(WORKER_ID, registration.build(), responses));
    try {
      assertThat(acceptanceEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var dispatching =
          executor.submit(
              () -> {
                dispatchStarted.countDown();
                return registry.dispatchProbe(request);
              });
      assertThat(dispatchStarted.await(5, TimeUnit.SECONDS)).isTrue();
      releaseAcceptance.release();
      var replacementSession = replacing.get(5, TimeUnit.SECONDS);
      assertThat(dispatching.get(5, TimeUnit.SECONDS)).isInstanceOf(ProbeDispatch.Dispatched.class);
      assertThatThrownBy(() -> superseded.get(1, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(ProbeExecutionException.class);
      registry.disconnect(WORKER_ID, replacementSession);
      return startedBeforeFailure.get();
    } finally {
      releaseAcceptance.release();
    }
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

    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKER_UNREACHABLE));

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

  private ProbeRequest incompatibleProbe(Incompatibility incompatibility) {
    return switch (incompatibility) {
      case PROBE_VERSION -> probe().setProbeVersion(2).build();
      case SOURCE_NAMESPACE ->
          probe()
              .setSource(source().toBuilder().setSourceNamespaceId(toProto(UUID.randomUUID())))
              .build();
    };
  }

  private static ProbeAttemptResult invalidMediaReply(ProbeRequest request) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion())
        .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
        .build();
  }

  private static List<StartProbeCommand> startCommands(CapturingResponses responses) {
    return responses.values.stream()
        .filter(EstablishWorkerSessionResponse::hasStartProbe)
        .map(EstablishWorkerSessionResponse::getStartProbe)
        .toList();
  }

  private enum Incompatibility {
    PROBE_VERSION,
    SOURCE_NAMESPACE
  }

  private enum ReplyMismatch {
    WORKER,
    SESSION,
    ATTEMPT
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
