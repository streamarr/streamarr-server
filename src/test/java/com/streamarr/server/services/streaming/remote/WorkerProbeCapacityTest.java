package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.ProbeCapacity;
import com.streamarr.transcode.v1.BackgroundProbeCapacityControl;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeCapacityChanged;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbePriority;
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
import java.util.stream.Stream;
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
      LiveWorkerConnectionRegistry.builder()
          .configuration(WorkerSessionServerConfiguration.builder().build())
          .meterRegistry(meterRegistry)
          .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
          .eventPublisher(new CapturingEventPublisher())
          .build();

  @Test
  @DisplayName(
      "Should dispatch background probes up to the background probe capacity when the worker"
          + " grants one playback slot")
  void shouldDispatchBackgroundProbesUpToTheBackgroundProbeCapacityWhenTheWorkerGrantsOneSlot() {
    var responses = register(Worker.builder().backgroundProbeCapacity(4).playbackSlots(1).build());

    var dispatches = dispatchProbes(4);

    assertThat(dispatches).allSatisfy(this::assertDispatched);
    assertThat(startProbeCount(responses)).isEqualTo(4);
    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
  }

  @Test
  @DisplayName("Should dispatch probes up to their capacities when every playback slot is in use")
  void shouldDispatchProbesUpToTheirCapacitiesWhenEveryPlaybackSlotIsInUse() {
    register(Worker.builder().backgroundProbeCapacity(2).playbackSlots(1).build());
    assertThat(registry.dispatch(jobAttempt())).isPresent();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isZero();

    assertThat(dispatchProbes(2)).allSatisfy(this::assertDispatched);
    assertDispatched(registry.dispatchProbe(playbackProbe().build()));
    assertDispatched(registry.dispatchProbe(scan().build()));
  }

  @Test
  @DisplayName("Should start a job attempt when every unit of every probe capacity is in use")
  void shouldStartAJobAttemptWhenEveryUnitOfEveryProbeCapacityIsInUse() {
    register(Worker.builder().backgroundProbeCapacity(1).playbackSlots(1).build());
    assertDispatched(registry.dispatchProbe(probe().build()));
    assertDispatched(registry.dispatchProbe(playbackProbe().build()));
    assertDispatched(registry.dispatchProbe(playbackProbe().build()));
    assertDispatched(registry.dispatchProbe(scan().build()));

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
    assertThat(registry.dispatch(jobAttempt())).isPresent();
  }

  @Test
  @DisplayName(
      "Should dispatch eight background probes at once when two workers grant background probe"
          + " capacities of three and five")
  void shouldDispatchEightBackgroundProbesAtOnceWhenTwoWorkersGrantThreeAndFive() {
    var first = register(Worker.builder().backgroundProbeCapacity(3).playbackSlots(1).build());
    var second =
        register(
            Worker.builder()
                .workerId(OTHER_WORKER_ID)
                .backgroundProbeCapacity(5)
                .playbackSlots(1)
                .build());

    var dispatches = dispatchProbes(8);

    assertThat(dispatches).allSatisfy(this::assertDispatched);
    assertThat(startProbeCount(first)).isEqualTo(3);
    assertThat(startProbeCount(second)).isEqualTo(5);
    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
  }

  @Test
  @DisplayName(
      "Should start a playback probe when the background probe and keyframe packet scan"
          + " capacities are both in use")
  void shouldStartAPlaybackProbeWhenTheBackgroundAndScanCapacitiesAreBothInUse() {
    register(Worker.builder().backgroundProbeCapacity(1).playbackSlots(1).build());
    assertDispatched(registry.dispatchProbe(probe().build()));
    assertDispatched(registry.dispatchProbe(scan().build()));

    assertDispatched(registry.dispatchProbe(playbackProbe().build()));
    assertDispatched(registry.dispatchProbe(playbackProbe().build()));
  }

  @Test
  @DisplayName("Should refuse a background probe when only playback probe units are free")
  void shouldRefuseABackgroundProbeWhenOnlyPlaybackProbeUnitsAreFree() {
    register(Worker.builder().backgroundProbeCapacity(1).playbackSlots(1).build());
    assertDispatched(registry.dispatchProbe(probe().build()));

    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertDispatched(registry.dispatchProbe(playbackProbe().build()));
  }

  @Test
  @DisplayName(
      "Should count a playback probe beyond the playback probe capacity as a background unit in"
          + " use")
  void shouldCountAPlaybackProbeBeyondThePlaybackProbeCapacityAsABackgroundUnitInUse() {
    register(Worker.builder().backgroundProbeCapacity(1).playbackSlots(1).build());
    assertDispatched(registry.dispatchProbe(playbackProbe().build()));
    assertDispatched(registry.dispatchProbe(playbackProbe().build()));

    assertDispatched(registry.dispatchProbe(playbackProbe().build()));

    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(registry.dispatchProbe(playbackProbe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
  }

  @Test
  @DisplayName(
      "Should send a playback probe to a worker with a free playback unit when another worker has"
          + " only a background unit free")
  void shouldSendAPlaybackProbeToAWorkerWithAFreePlaybackUnitBeforeABackgroundUnit() {
    var backgroundOnly =
        register(
            Worker.builder()
                .backgroundProbeCapacity(1)
                .playbackProbeCapacity(0)
                .playbackSlots(1)
                .build());
    var playbackOnly =
        register(
            Worker.builder()
                .workerId(OTHER_WORKER_ID)
                .backgroundProbeCapacity(0)
                .playbackSlots(1)
                .build());

    assertDispatched(registry.dispatchProbe(playbackProbe().build()));
    assertDispatched(registry.dispatchProbe(probe().build()));

    assertThat(startProbeCount(playbackOnly)).isOne();
    assertThat(startProbeCount(backgroundOnly)).isOne();
  }

  @Test
  @DisplayName(
      "Should count a keyframe packet scan against the scan capacity only when the scan runs")
  void shouldCountAKeyframePacketScanAgainstTheScanCapacityOnlyWhenTheScanRuns() {
    register(Worker.builder().backgroundProbeCapacity(1).playbackSlots(1).build());

    assertDispatched(registry.dispatchProbe(scan().build()));

    assertThat(registry.dispatchProbe(scan().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertDispatched(registry.dispatchProbe(probe().build()));
    assertThat(registry.dispatch(jobAttempt())).isPresent();
  }

  @Test
  @DisplayName(
      "Should send keyframe packet scans to the worker with a free scan unit when another worker"
          + " grants none")
  void shouldSendKeyframePacketScansToTheWorkerWithAFreeScanUnitWhenAnotherWorkerGrantsNone() {
    var none =
        register(
            Worker.builder()
                .backgroundProbeCapacity(1)
                .keyframePacketScanCapacity(0)
                .playbackSlots(1)
                .build());
    var one =
        register(
            Worker.builder()
                .workerId(OTHER_WORKER_ID)
                .backgroundProbeCapacity(1)
                .playbackSlots(1)
                .build());

    assertDispatched(registry.dispatchProbe(scan().build()));

    assertThat(startProbeCount(none)).isZero();
    assertThat(startProbeCount(one)).isOne();
    assertThat(registry.dispatchProbe(scan().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
  }

  @Test
  @DisplayName(
      "Should dispatch up to the raised capacities when the worker raises its background probe and"
          + " keyframe packet scan capacities")
  void shouldDispatchUpToTheRaisedCapacitiesWhenTheWorkerRaisesThem() {
    var sessionId =
        registerSession(
            Worker.builder()
                .backgroundProbeCapacity(1)
                .keyframePacketScanCapacity(0)
                .playbackSlots(1)
                .build());
    assertDispatched(registry.dispatchProbe(probe().build()));

    assertThat(registry.tryChangeProbeCapacity(WORKER_ID, sessionId, capacityChanged(3, 1)))
        .isTrue();

    assertThat(dispatchProbes(2)).allSatisfy(this::assertDispatched);
    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertDispatched(registry.dispatchProbe(scan().build()));
    assertThat(capacityGauge(WORKER_ID, "background")).isEqualTo(3);
    assertThat(capacityGauge(WORKER_ID, "keyframe_packet_scan")).isOne();
  }

  @Test
  @DisplayName(
      "Should send no new probe until the running probes fall below the lowered capacities")
  void shouldSendNoNewProbeUntilTheRunningProbesFallBelowTheLoweredCapacities() {
    var sessionId =
        registerSession(Worker.builder().backgroundProbeCapacity(3).playbackSlots(1).build());
    var running = IntStream.range(0, 3).mapToObj(_ -> probe().build()).toList();
    running.forEach(request -> assertDispatched(registry.dispatchProbe(request)));
    var runningScan = scan().build();
    assertDispatched(registry.dispatchProbe(runningScan));

    assertThat(registry.tryChangeProbeCapacity(WORKER_ID, sessionId, capacityChanged(1, 0)))
        .isTrue();

    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(registry.completeProbe(WORKER_ID, sessionId, invalidMedia(running.get(0)))).isTrue();
    assertThat(registry.completeProbe(WORKER_ID, sessionId, invalidMedia(running.get(1)))).isTrue();
    assertThat(registry.dispatchProbe(probe().build()))
        .as("The one probe that still runs uses the whole lowered capacity")
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(registry.completeProbe(WORKER_ID, sessionId, invalidMedia(running.get(2)))).isTrue();
    assertDispatched(registry.dispatchProbe(probe().build()));
    assertThat(registry.completeProbe(WORKER_ID, sessionId, invalidMedia(runningScan))).isTrue();
    assertThat(registry.dispatchProbe(scan().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
  }

  @Test
  @DisplayName(
      "Should send no background probe but still send playback probes when the worker grants a"
          + " background probe capacity of zero")
  void shouldSendNoBackgroundProbeButStillSendPlaybackProbesWhenTheBackgroundCapacityIsZero() {
    var responses = register(Worker.builder().backgroundProbeCapacity(0).playbackSlots(1).build());

    assertThat(registry.dispatchProbe(probe().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertDispatched(registry.dispatchProbe(playbackProbe().build()));
    assertThat(startProbeCount(responses)).isOne();
  }

  @Test
  @DisplayName("Should keep the capacities when a replaced worker session reports new capacities")
  void shouldKeepTheCapacitiesWhenAReplacedWorkerSessionReportsNewCapacities() {
    var replacedSessionId =
        registerSession(Worker.builder().backgroundProbeCapacity(1).playbackSlots(1).build());
    registerSession(Worker.builder().backgroundProbeCapacity(2).playbackSlots(1).build());

    assertThat(registry.tryChangeProbeCapacity(WORKER_ID, replacedSessionId, capacityChanged(5, 1)))
        .isFalse();

    assertThat(registry.probeCapacity(SOURCE_NAMESPACE_ID, PROBE_VERSION).backgroundProbeCapacity())
        .isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should report the capacities and free units of compatible workers only when asked for the"
          + " probe capacity")
  void shouldReportTheCapacitiesAndFreeUnitsOfCompatibleWorkersOnly() {
    register(Worker.builder().backgroundProbeCapacity(3).playbackSlots(1).build());
    register(
        Worker.builder()
            .workerId(OTHER_WORKER_ID)
            .probeVersion(PROBE_VERSION + 1)
            .backgroundProbeCapacity(5)
            .playbackSlots(1)
            .build());
    assertDispatched(registry.dispatchProbe(probe().build()));
    assertDispatched(registry.dispatchProbe(playbackProbe().build()));
    assertDispatched(registry.dispatchProbe(scan().build()));

    assertThat(registry.probeCapacity(SOURCE_NAMESPACE_ID, PROBE_VERSION))
        .isEqualTo(
            ProbeCapacity.builder()
                .backgroundProbeCapacity(3)
                .freePlaybackProbeUnits(1)
                .freeBackgroundProbeUnits(2)
                .freeKeyframePacketScanUnits(0)
                .runningProbes(2)
                .runningKeyframePacketScans(1)
                .build());
    assertThat(registry.probeCapacity(UUID.randomUUID(), PROBE_VERSION))
        .isEqualTo(ProbeCapacity.NONE);
  }

  @Test
  @DisplayName(
      "Should report each worker's granted probe capacities and running probes by kind when its"
          + " probes run")
  void shouldReportEachWorkersGrantedProbeCapacitiesAndRunningProbesByKindWhenItsProbesRun() {
    register(Worker.builder().backgroundProbeCapacity(3).playbackSlots(1).build());
    register(
        Worker.builder()
            .workerId(OTHER_WORKER_ID)
            .backgroundProbeCapacity(5)
            .keyframePacketScanCapacity(0)
            .playbackSlots(1)
            .build());
    assertDispatched(registry.dispatchProbe(probe().build()));
    assertDispatched(registry.dispatchProbe(playbackProbe().build()));
    assertDispatched(registry.dispatchProbe(scan().build()));

    var running =
        Stream.of("playback", "background", "keyframe_packet_scan")
            .map(kind -> runningGauge(WORKER_ID, kind) + runningGauge(OTHER_WORKER_ID, kind))
            .toList();

    assertThat(running).containsExactly(1.0, 1.0, 1.0);
    assertThat(capacityGauge(WORKER_ID, "playback")).isEqualTo(2);
    assertThat(capacityGauge(WORKER_ID, "background")).isEqualTo(3);
    assertThat(capacityGauge(WORKER_ID, "keyframe_packet_scan")).isOne();
    assertThat(capacityGauge(OTHER_WORKER_ID, "background")).isEqualTo(5);
    assertThat(capacityGauge(OTHER_WORKER_ID, "keyframe_packet_scan")).isZero();
    assertThat(
            meterRegistry
                .get("streamarr.probes.background_capacity_control")
                .tag("worker", WORKER_ID.toString())
                .tag("control", "adaptive")
                .gauge()
                .value())
        .isOne();
  }

  @Test
  @DisplayName("Should remove a worker's probe meters when the worker disconnects")
  void shouldRemoveAWorkersProbeMetersWhenTheWorkerDisconnects() {
    var sessionId =
        registerSession(Worker.builder().backgroundProbeCapacity(3).playbackSlots(1).build());

    registry.disconnect(WORKER_ID, sessionId);

    assertThat(meterRegistry.find("streamarr.probes.capacity").meters()).isEmpty();
    assertThat(meterRegistry.find("streamarr.probes.running").meters()).isEmpty();
    assertThat(meterRegistry.find("streamarr.probes.background_capacity_control").meters())
        .isEmpty();
  }

  @Test
  @DisplayName(
      "Should report the capacities of the replacing session when a worker session is replaced")
  void shouldReportTheCapacitiesOfTheReplacingSessionWhenAWorkerSessionIsReplaced() {
    var replacedSessionId =
        registerSession(Worker.builder().backgroundProbeCapacity(3).playbackSlots(1).build());
    registerSession(Worker.builder().backgroundProbeCapacity(6).playbackSlots(1).build());

    registry.disconnect(WORKER_ID, replacedSessionId);

    assertThat(capacityGauge(WORKER_ID, "background")).isEqualTo(6);
  }

  @Test
  @DisplayName("Should count deferred probes by reason when no worker can run them now")
  void shouldCountDeferredProbesByReasonWhenNoWorkerCanRunThemNow() {
    registry.dispatchProbe(probe().build());
    register(Worker.builder().backgroundProbeCapacity(1).playbackSlots(1).build());
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
    register(Worker.builder().backgroundProbeCapacity(1).playbackSlots(1).build());

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
            .setBackgroundProbeCapacity(worker.backgroundProbeCapacity())
            .setPlaybackProbeCapacity(worker.playbackProbeCapacity())
            .setKeyframePacketScanCapacity(worker.keyframePacketScanCapacity())
            .setBackgroundProbeCapacityControl(
                BackgroundProbeCapacityControl.BACKGROUND_PROBE_CAPACITY_CONTROL_ADAPTIVE)
            .build(),
        responses);
  }

  private static ProbeCapacityChanged capacityChanged(
      int backgroundProbeCapacity, int keyframePacketScanCapacity) {
    return ProbeCapacityChanged.newBuilder()
        .setBackgroundProbeCapacity(backgroundProbeCapacity)
        .setKeyframePacketScanCapacity(keyframePacketScanCapacity)
        .build();
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

  private double capacityGauge(UUID workerId, String kind) {
    return probeGauge("streamarr.probes.capacity", workerId, kind);
  }

  private double runningGauge(UUID workerId, String kind) {
    return probeGauge("streamarr.probes.running", workerId, kind);
  }

  private double probeGauge(String name, UUID workerId, String kind) {
    return meterRegistry
        .get(name)
        .tag("worker", workerId.toString())
        .tag("kind", kind)
        .gauge()
        .value();
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

  private static ProbeRequest.Builder playbackProbe() {
    return probe().setPriority(ProbePriority.PROBE_PRIORITY_PLAYBACK);
  }

  private static ProbeRequest.Builder scan() {
    return probe().setScanKeyframePackets(true);
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

  // Unset, the playback probe capacity is 2 and the keyframe packet scan capacity is 1, as a worker
  // grants them while it runs no job attempt.
  @Builder
  private record Worker(
      UUID workerId,
      Integer probeVersion,
      int backgroundProbeCapacity,
      Integer playbackProbeCapacity,
      Integer keyframePacketScanCapacity,
      int playbackSlots) {

    private Worker {
      if (workerId == null) {
        workerId = WORKER_ID;
      }

      if (probeVersion == null) {
        probeVersion = PROBE_VERSION;
      }

      if (playbackProbeCapacity == null) {
        playbackProbeCapacity = 2;
      }

      if (keyframePacketScanCapacity == null) {
        keyframePacketScanCapacity = 1;
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
