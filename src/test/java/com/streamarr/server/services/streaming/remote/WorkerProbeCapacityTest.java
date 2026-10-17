package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.ProbeCapacity;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeCapacityChanged;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Probe Capacity Tests")
class WorkerProbeCapacityTest {

  private static final UUID OTHER_WORKER_ID =
      UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final int PROBE_VERSION = 1;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LiveWorkerConnectionRegistry registry =
      new LiveWorkerConnectionRegistry(
          WorkerSessionServerConfiguration.builder().build(),
          meterRegistry,
          new HeldSegmentRequests(new SimpleMeterRegistry()));

  @Test
  @DisplayName(
      "Should dispatch probes up to the probe capacity when the worker advertises one playback"
          + " slot")
  void shouldDispatchProbesUpToTheProbeCapacityWhenTheWorkerAdvertisesOnePlaybackSlot() {
    var responses = register(Worker.builder().probeCapacity(4).playbackSlots(1).build());

    var dispatches = dispatchProbes(4);

    assertThat(dispatches).allSatisfy(this::assertDispatched);
    assertThat(startProbeCount(responses)).isEqualTo(4);
    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
  }

  @Test
  @DisplayName("Should dispatch probes up to the probe capacity when every playback slot is in use")
  void shouldDispatchProbesUpToTheProbeCapacityWhenEveryPlaybackSlotIsInUse() {
    register(Worker.builder().probeCapacity(2).playbackSlots(1).build());
    assertThat(registry.dispatch(jobAttempt())).isPresent();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isZero();

    var dispatches = dispatchProbes(2);

    assertThat(dispatches).allSatisfy(this::assertDispatched);
  }

  @Test
  @DisplayName("Should start a job attempt when every unit of the probe capacity is in use")
  void shouldStartAJobAttemptWhenEveryUnitOfTheProbeCapacityIsInUse() {
    register(Worker.builder().probeCapacity(1).playbackSlots(1).build());
    assertDispatched(registry.dispatchProbe(probe().build()));

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
    assertThat(registry.dispatch(jobAttempt())).isPresent();
  }

  @Test
  @DisplayName(
      "Should dispatch eight probes at once when two workers advertise probe capacities of three"
          + " and five")
  void shouldDispatchEightProbesAtOnceWhenTwoWorkersAdvertiseProbeCapacitiesOfThreeAndFive() {
    var first = register(Worker.builder().probeCapacity(3).playbackSlots(1).build());
    var second =
        register(
            Worker.builder().workerId(OTHER_WORKER_ID).probeCapacity(5).playbackSlots(1).build());

    var dispatches = dispatchProbes(8);

    assertThat(dispatches).allSatisfy(this::assertDispatched);
    assertThat(startProbeCount(first)).isEqualTo(3);
    assertThat(startProbeCount(second)).isEqualTo(5);
    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
  }

  @Test
  @DisplayName(
      "Should count a keyframe packet scan against the probe capacity, not a playback slot, when"
          + " the scan runs")
  void shouldCountAKeyframePacketScanAgainstTheProbeCapacityNotAPlaybackSlotWhenTheScanRuns() {
    register(Worker.builder().probeCapacity(1).playbackSlots(1).build());

    assertDispatched(registry.dispatchProbe(probe().setScanKeyframePackets(true).build()));

    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
    assertThat(registry.dispatch(jobAttempt())).isPresent();
  }

  @Test
  @DisplayName("Should send no probe when the worker advertises a probe capacity of zero")
  void shouldSendNoProbeWhenTheWorkerAdvertisesAProbeCapacityOfZero() {
    var responses = register(Worker.builder().probeCapacity(0).playbackSlots(1).build());

    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.NO_COMPATIBLE_WORKER));
    assertThat(startProbeCount(responses)).isZero();
    assertThat(registry.dispatch(jobAttempt())).isPresent();
  }

  @Test
  @DisplayName(
      "Should dispatch probes up to the raised probe capacity when the worker raises its capacity")
  void shouldDispatchProbesUpToTheRaisedProbeCapacityWhenTheWorkerRaisesItsCapacity() {
    var sessionId = registerSession(Worker.builder().probeCapacity(1).playbackSlots(1).build());
    assertDispatched(registry.dispatchProbe(probe().build()));

    assertThat(registry.tryChangeProbeCapacity(WORKER_ID, sessionId, capacityChanged(3))).isTrue();

    assertThat(dispatchProbes(2)).allSatisfy(this::assertDispatched);
    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(meterRegistry.get("streamarr.probes.capacity").gauge().value()).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "Should send no new probe until the running probes fall below the lowered probe capacity")
  void shouldSendNoNewProbeUntilTheRunningProbesFallBelowTheLoweredProbeCapacity() {
    var sessionId = registerSession(Worker.builder().probeCapacity(3).playbackSlots(1).build());
    var running = IntStream.range(0, 3).mapToObj(_ -> probe().build()).toList();
    running.forEach(request -> assertDispatched(registry.dispatchProbe(request)));

    assertThat(registry.tryChangeProbeCapacity(WORKER_ID, sessionId, capacityChanged(1))).isTrue();

    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(registry.completeProbe(WORKER_ID, sessionId, invalidMedia(running.get(0)))).isTrue();
    assertThat(registry.completeProbe(WORKER_ID, sessionId, invalidMedia(running.get(1)))).isTrue();
    assertThat(registry.dispatchProbe(probe().build()))
        .as("The one probe that still runs uses the whole lowered capacity")
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(registry.completeProbe(WORKER_ID, sessionId, invalidMedia(running.get(2)))).isTrue();
    assertDispatched(registry.dispatchProbe(probe().build()));
  }

  @Test
  @DisplayName("Should send no probe when the worker lowers its probe capacity to zero")
  void shouldSendNoProbeWhenTheWorkerLowersItsProbeCapacityToZero() {
    var sessionId = registerSession(Worker.builder().probeCapacity(2).playbackSlots(1).build());

    assertThat(registry.tryChangeProbeCapacity(WORKER_ID, sessionId, capacityChanged(0))).isTrue();

    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.NO_COMPATIBLE_WORKER));
    assertThat(registry.probeCapacity(SOURCE_NAMESPACE_ID, PROBE_VERSION).advertised()).isZero();
  }

  @Test
  @DisplayName(
      "Should keep the probe capacity when a replaced worker session reports a new capacity")
  void shouldKeepTheProbeCapacityWhenAReplacedWorkerSessionReportsANewCapacity() {
    var replacedSessionId =
        registerSession(Worker.builder().probeCapacity(1).playbackSlots(1).build());
    registerSession(Worker.builder().probeCapacity(2).playbackSlots(1).build());

    assertThat(registry.tryChangeProbeCapacity(WORKER_ID, replacedSessionId, capacityChanged(5)))
        .isFalse();

    assertThat(registry.probeCapacity(SOURCE_NAMESPACE_ID, PROBE_VERSION).advertised())
        .isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should report the probe capacity of compatible workers only when asked for the probe"
          + " capacity")
  void shouldReportTheProbeCapacityOfCompatibleWorkersOnlyWhenAskedForTheProbeCapacity() {
    register(Worker.builder().probeCapacity(3).playbackSlots(1).build());
    register(
        Worker.builder()
            .workerId(OTHER_WORKER_ID)
            .probeVersion(PROBE_VERSION + 1)
            .probeCapacity(5)
            .playbackSlots(1)
            .build());
    assertDispatched(registry.dispatchProbe(probe().build()));
    assertDispatched(registry.dispatchProbe(probe().setScanKeyframePackets(true).build()));

    assertThat(registry.probeCapacity(SOURCE_NAMESPACE_ID, PROBE_VERSION))
        .isEqualTo(ProbeCapacity.builder().advertised(3).running(2).runningPacketScans(1).build());
    assertThat(registry.probeCapacity(UUID.randomUUID(), PROBE_VERSION).advertised()).isZero();
  }

  @Test
  @DisplayName(
      "Should report the advertised probe capacity and the running probes when workers run probes")
  void shouldReportTheAdvertisedProbeCapacityAndTheRunningProbesWhenWorkersRunProbes() {
    register(Worker.builder().probeCapacity(3).playbackSlots(1).build());
    register(Worker.builder().workerId(OTHER_WORKER_ID).probeCapacity(5).playbackSlots(1).build());

    dispatchProbes(2);

    assertThat(meterRegistry.get("streamarr.probes.capacity").gauge().value()).isEqualTo(8);
    assertThat(meterRegistry.get("streamarr.probes.running").gauge().value()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should count deferred probes by reason when no worker can run them now")
  void shouldCountDeferredProbesByReasonWhenNoWorkerCanRunThemNow() {
    registry.dispatchProbe(probe().build());
    register(Worker.builder().probeCapacity(1).playbackSlots(1).build());
    registry.dispatchProbe(probe().setProbeVersion(PROBE_VERSION + 1).build());
    registry.dispatchProbe(probe().build());
    registry.dispatchProbe(probe().build());
    registry.dispatchProbe(probe().build());

    assertThat(deferred(ProbeRefusal.NO_CONNECTED_WORKER)).isEqualTo(1);
    assertThat(deferred(ProbeRefusal.NO_COMPATIBLE_WORKER)).isEqualTo(1);
    assertThat(deferred(ProbeRefusal.WORKERS_BUSY)).isEqualTo(2);
  }

  @Test
  @DisplayName("Should count no deferred probe when a probe request is invalid")
  void shouldCountNoDeferredProbeWhenAProbeRequestIsInvalid() {
    register(Worker.builder().probeCapacity(1).playbackSlots(1).build());

    assertThat(registry.dispatchProbe(probe().clearSource().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.INVALID_REQUEST));

    assertThat(meterRegistry.find("streamarr.probes.deferred").counters()).isEmpty();
  }

  private CapturingResponses register(Worker worker) {
    var responses = new CapturingResponses();
    registerSession(worker, responses);
    return responses;
  }

  private UUID registerSession(Worker worker) {
    return registerSession(worker, new CapturingResponses());
  }

  private UUID registerSession(Worker worker, CapturingResponses responses) {
    return registry.register(
        worker.workerId(),
        WorkerRegistration.newBuilder()
            .setWorker(
                WorkerIdentity.newBuilder()
                    .setWorkerId(toProto(worker.workerId()))
                    .setBootId(toProto(UUID.randomUUID())))
            .setCapabilities(
                WorkerCapabilities.newBuilder()
                    .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                    .addProbeVersions(worker.probeVersion()))
            .setAvailableSlots(worker.playbackSlots())
            .setProbeCapacity(worker.probeCapacity())
            .build(),
        responses);
  }

  private static ProbeCapacityChanged capacityChanged(int probeCapacity) {
    return ProbeCapacityChanged.newBuilder().setProbeCapacity(probeCapacity).build();
  }

  private static ProbeAttemptResult invalidMedia(ProbeRequest request) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion())
        .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
        .build();
  }

  private List<ProbeDispatch> dispatchProbes(int count) {
    return IntStream.range(0, count)
        .mapToObj(_ -> registry.dispatchProbe(probe().build()))
        .toList();
  }

  private void assertDispatched(ProbeDispatch dispatch) {
    assertThat(dispatch).isInstanceOf(ProbeDispatch.Dispatched.class);
  }

  private double deferred(ProbeRefusal reason) {
    return meterRegistry
        .get("streamarr.probes.deferred")
        .tag("reason", reason.name())
        .counter()
        .count();
  }

  private static ProbeRequest.Builder probe() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(PROBE_VERSION)
        .setSource(source());
  }

  private static VariantJob jobAttempt() {
    return VariantJob.newBuilder()
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(source())
        .build();
  }

  private static MediaSourceRef source() {
    return MediaSourceRef.newBuilder()
        .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
        .setRelativeKey("movie.mkv")
        .build();
  }

  private static long startProbeCount(CapturingResponses responses) {
    return responses.values.stream().filter(EstablishWorkerSessionResponse::hasStartProbe).count();
  }

  @Builder
  private record Worker(UUID workerId, Integer probeVersion, int probeCapacity, int playbackSlots) {

    private Worker {
      if (workerId == null) {
        workerId = WORKER_ID;
      }

      if (probeVersion == null) {
        probeVersion = PROBE_VERSION;
      }
    }
  }

  private static final class CapturingResponses
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private final List<EstablishWorkerSessionResponse> values = new CopyOnWriteArrayList<>();

    @Override
    public void onNext(EstablishWorkerSessionResponse response) {
      values.add(response);
    }

    @Override
    public void onError(Throwable error) {
      // A replaced worker session ends with an error. The tests read only the commands.
    }

    @Override
    public void onCompleted() {
      // The session stays open for the whole test.
    }
  }
}
