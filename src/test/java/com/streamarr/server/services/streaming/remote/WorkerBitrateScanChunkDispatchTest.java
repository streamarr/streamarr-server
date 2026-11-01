package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.BitrateScanChunk;
import com.streamarr.transcode.v1.BitrateScanChunkResult;
import com.streamarr.transcode.v1.BitrateScanChunkStreams;
import com.streamarr.transcode.v1.BitrateScanCopyChangesPackets;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Worker Bitrate Scan Chunk Dispatch Tests")
class WorkerBitrateScanChunkDispatchTest {

  private static final int PROBE_VERSION = 1;
  private static final int MEASUREMENT_VERSION = 2;

  private final LiveWorkerConnectionRegistry registry =
      LiveWorkerConnectionRegistryFixture.defaultRegistry();
  private final CapturingResponses responses = new CapturingResponses();

  @ParameterizedTest(name = "listed measurement versions={0}")
  @ValueSource(strings = {"", "1", "3"})
  @DisplayName(
      "Should refuse a chunk as incompatible when the worker does not list the chunk's measurement"
          + " version")
  void shouldRefuseAChunkAsIncompatibleWhenTheWorkerDoesNotListItsMeasurementVersion(
      String listedVersions) {
    var capabilities = WorkerCapabilities.newBuilder();
    if (!listedVersions.isEmpty()) {
      capabilities.addMeasurementVersions(Integer.parseInt(listedVersions));
    }

    register(capabilities);

    assertThat(registry.dispatchProbe(chunk().build()))
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.NO_COMPATIBLE_WORKER));
    assertThat(startProbeCount()).isZero();
    assertThat(registry.dispatchProbe(probe().build()))
        .as("The worker still takes probes")
        .isInstanceOf(ProbeDispatch.Dispatched.class);
  }

  @Test
  @DisplayName(
      "Should send a chunk on the keyframe packet scan capacity when the worker lists its"
          + " measurement version")
  void shouldSendAChunkOnTheKeyframePacketScanCapacityWhenTheWorkerListsItsMeasurementVersion() {
    register(WorkerCapabilities.newBuilder().addMeasurementVersions(MEASUREMENT_VERSION));

    assertThat(registry.dispatchProbe(chunk().build()))
        .isInstanceOf(ProbeDispatch.Dispatched.class);

    assertThat(registry.dispatchProbe(probe().setScanKeyframePackets(true).build()))
        .as("The chunk holds the worker's only keyframe packet scan unit")
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(registry.dispatchProbe(probe().build()))
        .as("The chunk leaves the background probe unit free")
        .isInstanceOf(ProbeDispatch.Dispatched.class);
  }

  @Test
  @DisplayName(
      "Should return each measured stream's joined parts when the worker measures the chunk")
  void shouldReturnEachMeasuredStreamsJoinedPartsWhenTheWorkerMeasuresTheChunk() throws Exception {
    var session =
        register(WorkerCapabilities.newBuilder().addMeasurementVersions(MEASUREMENT_VERSION));
    var request = chunk().build();
    var pending = dispatched(registry.dispatchProbe(request));

    registry.acceptStreamMeasurementPart(WORKER_ID, session, part(request, 0, 700));
    registry.acceptStreamMeasurementPart(WORKER_ID, session, part(request, 0, 300));
    var result = measured(request);

    assertThat(registry.completeProbe(WORKER_ID, session, result)).isTrue();
    assertThat(pending.get(1, TimeUnit.SECONDS))
        .isEqualTo(
            new ProbeAttemptReport(
                result,
                List.of(),
                List.of(
                    ProbeStreamMeasurementPart.newBuilder()
                        .setProbeAttemptId(request.getProbeAttemptId())
                        .setStreamIndex(0)
                        .addAllKeyframeIntervalBytes(List.of(700, 300))
                        .addAllKeyframeIntervalPacketCounts(List.of(1, 1))
                        .build())));
  }

  @Test
  @DisplayName(
      "Should fail the chunk as a contract violation when its parts break the contract and the"
          + " worker measures the chunk")
  void shouldFailTheChunkAsAContractViolationWhenItsPartsBreakTheContract() {
    var session =
        register(WorkerCapabilities.newBuilder().addMeasurementVersions(MEASUREMENT_VERSION));
    var request = chunk().build();
    var pending = dispatched(registry.dispatchProbe(request));

    registry.acceptStreamMeasurementPart(
        WORKER_ID,
        session,
        part(request, 0, 700).toBuilder().clearKeyframeIntervalPacketCounts().build());

    assertThat(registry.completeProbe(WORKER_ID, session, measured(request))).isFalse();
    assertThatThrownBy(() -> pending.get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeContractViolationException.class);
  }

  @Test
  @DisplayName(
      "Should discard the chunk's parts when the worker reports that a copy changes a stream's"
          + " packets")
  void shouldDiscardTheChunksPartsWhenTheWorkerReportsThatACopyChangesAStreamsPackets()
      throws Exception {
    var session =
        register(WorkerCapabilities.newBuilder().addMeasurementVersions(MEASUREMENT_VERSION));
    var request = chunk().build();
    var pending = dispatched(registry.dispatchProbe(request));

    registry.acceptStreamMeasurementPart(WORKER_ID, session, part(request, 0, 700));
    var result =
        answer(request)
            .setBitrateScanChunk(
                BitrateScanChunkResult.newBuilder()
                    .setCopyChangesPackets(
                        BitrateScanCopyChangesPackets.newBuilder().setStreamIndex(0)))
            .build();

    assertThat(registry.completeProbe(WORKER_ID, session, result)).isTrue();
    assertThat(pending.get(1, TimeUnit.SECONDS).streamMeasurementParts()).isEmpty();
  }

  private UUID register(WorkerCapabilities.Builder capabilities) {
    return registry.register(
        WORKER_ID,
        WorkerRegistration.newBuilder()
            .setWorker(
                WorkerIdentity.newBuilder()
                    .setWorkerId(toProto(WORKER_ID))
                    .setBootId(toProto(UUID.randomUUID())))
            .setCapabilities(
                capabilities
                    .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                    .addProbeVersions(PROBE_VERSION))
            .setAvailableSlots(1)
            .setBackgroundProbeCapacity(1)
            .setKeyframePacketScanCapacity(1)
            .build(),
        responses);
  }

  private long startProbeCount() {
    return responses.values.stream().filter(EstablishWorkerSessionResponse::hasStartProbe).count();
  }

  private static ProbeRequest.Builder probe() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(PROBE_VERSION)
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                .setRelativeKey("movie.mkv"));
  }

  private static ProbeRequest.Builder chunk() {
    return probe()
        .setBitrateScanChunk(
            BitrateScanChunk.newBuilder()
                .setMeasurementVersion(MEASUREMENT_VERSION)
                .addMeasuredStreamIndexes(0)
                .setTimeBaseNumerator(1)
                .setTimeBaseDenominator(90_000)
                .addAllPresentationTimeDeltas(List.of(0L, 90_000L))
                .addAllDecodeTimeDeltas(List.of(0L, 90_000L))
                .setFirstChunk(true));
  }

  private static ProbeStreamMeasurementPart part(ProbeRequest request, int streamIndex, int bytes) {
    return ProbeStreamMeasurementPart.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setStreamIndex(streamIndex)
        .addKeyframeIntervalBytes(bytes)
        .addKeyframeIntervalPacketCounts(1)
        .build();
  }

  private static ProbeAttemptResult measured(ProbeRequest request) {
    return answer(request)
        .setBitrateScanChunk(
            BitrateScanChunkResult.newBuilder().setMeasured(BitrateScanChunkStreams.newBuilder()))
        .build();
  }

  private static ProbeAttemptResult.Builder answer(ProbeRequest request) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion());
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
      // The tests read only the commands that the registry sends.
    }

    @Override
    public void onCompleted() {
      // The tests read only the commands that the registry sends.
    }
  }
}
