package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptEndedWithoutReport;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

/**
 * A worker whose session and report calls a test drives against the service without a socket. The
 * worker runs probes of version 1, measures streams at measurement version 1, and grants 8 playback
 * probes, 8 background probes and 8 keyframe packet scans unless the test registers other
 * capacities.
 */
final class ReportingWorker {

  static final int PROBE_VERSION = 1;
  static final int MEASUREMENT_VERSION = 1;

  private final LiveWorkerConnectionRegistry registry;
  private final WorkerSessionGrpcService service;
  private final UUID workerId = UUID.randomUUID();
  private final UUID sourceNamespaceId = UUID.randomUUID();
  private final WorkerIdentity identity =
      WorkerIdentity.newBuilder()
          .setWorkerId(toProto(workerId))
          .setBootId(toProto(UUID.randomUUID()))
          .build();
  private SessionCommands commands;
  private StreamObserver<EstablishWorkerSessionRequest> session;
  // The worker session that the last reconnect replaced.
  private StreamObserver<EstablishWorkerSessionRequest> earlierSession;

  private ReportingWorker(
      LiveWorkerConnectionRegistry registry,
      WorkerSessionGrpcService service,
      WorkerRegistration.Builder registration)
      throws Exception {
    this.registry = registry;
    this.service = service;
    register(registration);
  }

  /** A worker of its own server, which enforces the limits. */
  static ReportingWorker connectedTo(ProbeReportLimits limits) throws Exception {
    return connectedTo(WorkerSessionServerConfiguration.builder().probeReportLimits(limits));
  }

  /** A worker of its own server with the configuration. */
  static ReportingWorker connectedTo(
      WorkerSessionServerConfiguration.WorkerSessionServerConfigurationBuilder configuration)
      throws Exception {
    return connectedTo(configuration, capacities());
  }

  /** A worker of its own server with the configuration, which grants the capacities. */
  static ReportingWorker connectedTo(
      WorkerSessionServerConfiguration.WorkerSessionServerConfigurationBuilder configuration,
      WorkerRegistration.Builder capacities)
      throws Exception {
    var registry = LiveWorkerConnectionRegistryFixture.registryWith(configuration.build());
    return new ReportingWorker(
        registry, new WorkerSessionGrpcService(registry, new FakeSegmentStore()), capacities);
  }

  /** Another worker of this worker's server. */
  ReportingWorker anotherWorker() throws Exception {
    return new ReportingWorker(registry, service, capacities());
  }

  static WorkerRegistration.Builder capacities() {
    return WorkerRegistration.newBuilder()
        .setPlaybackProbeCapacity(8)
        .setBackgroundProbeCapacity(8)
        .setKeyframePacketScanCapacity(8);
  }

  private void register(WorkerRegistration.Builder registration) throws Exception {
    var sessionCommands = new SessionCommands();
    commands = sessionCommands;
    session =
        Context.current()
            .withValue(WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID, workerId)
            .call(() -> service.establishWorkerSession(sessionCommands));
    registration
        .setWorker(identity)
        .setAvailableSlots(1)
        .getCapabilitiesBuilder()
        .mergeFrom(
            WorkerCapabilities.newBuilder()
                .addSourceNamespaceIds(toProto(sourceNamespaceId))
                .addProbeVersions(PROBE_VERSION)
                .addMeasurementVersions(MEASUREMENT_VERSION)
                .build());
    session.onNext(
        EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build());
  }

  /**
   * Registers again on a new worker session, as a worker that reconnects does. Returns the id of
   * the worker session that the new one replaces.
   */
  Uuid reconnect() throws Exception {
    var earlier = sessionId();
    earlierSession = session;
    register(capacities());
    return earlier;
  }

  Uuid sessionId() {
    return commands.responses.getFirst().getSessionAccepted().getWorkerSessionId();
  }

  WorkerIdentity identity() {
    return identity;
  }

  /** A background probe request for a source that the worker can read. */
  ProbeRequest.Builder probe() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(PROBE_VERSION)
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(sourceNamespaceId))
                .setRelativeKey("movie.mkv"));
  }

  DispatchedProbe dispatch() {
    return dispatch(probe());
  }

  DispatchedProbe dispatch(ProbeRequest.Builder probe) {
    var request = probe.build();
    return new DispatchedProbe(request, dispatched(registry.dispatchProbe(request)));
  }

  ProbeDispatch tryDispatch() {
    return registry.dispatchProbe(probe().build());
  }

  /** The first message of a report call of this worker's session for the probe attempt. */
  ProbeAttemptReportMetadata.Builder metadata(ProbeRequest request) {
    return ProbeAttemptReportMetadata.newBuilder()
        .setWorkerSessionId(sessionId())
        .setWorker(identity)
        .setProbeAttemptId(request.getProbeAttemptId());
  }

  ReportCall openReportCall() throws Exception {
    return ReportCall.open(service, workerId);
  }

  /** A report call that reaches the service without the identity that the interceptor sets. */
  ReportCall openReportCallWithoutWorkerIdentity() throws Exception {
    return ReportCall.openWithoutWorkerIdentity(service);
  }

  /** Reports the probe attempt in a report call of its own, to its last message. */
  ReportCall report(ProbeRequest request, ProbeAttemptReport report) throws Exception {
    return openReportCall().report(metadata(request), report);
  }

  /** A probe report that keeps the contract: the worker could not read the source. */
  static ProbeAttemptReport.Builder invalidMedia(ProbeRequest request) {
    return ProbeAttemptReport.newBuilder()
        .setResult(
            ProbeAttemptResult.newBuilder()
                .setProbeAttemptId(request.getProbeAttemptId())
                .setProbeVersion(request.getProbeVersion())
                .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA));
  }

  /**
   * A probe report whose result takes exactly this many encoded bytes. The result names a container
   * format of the length that makes up the rest.
   */
  static ProbeAttemptReport.Builder reportWithResultOfLength(
      ProbeRequest request, int resultLength) {
    var formatLength = resultLength - resultWithFormat(request, "").getSerializedSize();
    var result = resultWithFormat(request, "x".repeat(formatLength));
    // A longer length of the format, or of a message around it, takes another byte.
    while (result.getSerializedSize() > resultLength) {
      formatLength--;
      result = resultWithFormat(request, "x".repeat(formatLength));
    }

    assertThat(result.getSerializedSize()).isEqualTo(resultLength);
    return ProbeAttemptReport.newBuilder().setResult(result);
  }

  private static ProbeAttemptResult resultWithFormat(ProbeRequest request, String format) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion())
        .setMedia(
            ProbeMediaInfo.newBuilder()
                .setContainer(ProbeContainerInfo.newBuilder().setFormat(format)))
        .build();
  }

  /** Adds this many keyframes to the report, each 90,000 ticks after the one before. */
  static ProbeAttemptReport.Builder withKeyframes(
      ProbeAttemptReport.Builder report, int keyframes) {
    return report
        .addAllKeyframePresentationTimeDeltas(Collections.nCopies(keyframes, 90_000L))
        .addAllKeyframeDecodeTimeDeltas(Collections.nCopies(keyframes, 90_000L));
  }

  /** One stream with this many keyframe intervals, each of 1,000 bytes in one packet. */
  static StreamMeasurementIntervals intervals(int streamIndex, int intervals) {
    return StreamMeasurementIntervals.newBuilder()
        .setStreamIndex(streamIndex)
        .addAllKeyframeIntervalBytes(Collections.nCopies(intervals, 1_000))
        .addAllKeyframeIntervalPacketCounts(Collections.nCopies(intervals, 1))
        .build();
  }

  /** Sends the event on the worker session. */
  void send(EstablishWorkerSessionRequest event) {
    session.onNext(event);
  }

  /** Sends the event on the worker session that the reconnect replaced, as a late message of it. */
  void sendOnEarlierSession(EstablishWorkerSessionRequest event) {
    earlierSession.onNext(event);
  }

  /** Tells the server that the probe attempt has ended on the worker without a probe report. */
  void endWithoutReport(ProbeRequest request) {
    send(endedWithoutReport(request.getProbeAttemptId()));
  }

  /** The event that ends the probe attempt on the worker without a probe report. */
  static EstablishWorkerSessionRequest endedWithoutReport(Uuid probeAttemptId) {
    return EstablishWorkerSessionRequest.newBuilder()
        .setProbeAttemptEndedWithoutReport(
            ProbeAttemptEndedWithoutReport.newBuilder().setProbeAttemptId(probeAttemptId))
        .build();
  }

  /** Ends the worker session as gRPC does when the worker's connection is lost. */
  void disconnect() {
    session.onError(Status.UNAVAILABLE.asRuntimeException());
  }

  /** Every message that the server sent on the worker session, the acceptance first. */
  List<EstablishWorkerSessionResponse> commands() {
    return List.copyOf(commands.responses);
  }

  /** Completes with the status that the server ended the worker session with. */
  CompletableFuture<Status> sessionEnd() {
    return commands.end;
  }

  /** The probe attempts that the server told the worker to cancel, in the order of the commands. */
  List<Uuid> canceledProbeAttempts() {
    return commands.responses.stream()
        .filter(EstablishWorkerSessionResponse::hasCancelProbe)
        .map(command -> command.getCancelProbe().getProbeAttemptId())
        .toList();
  }

  boolean isConnected() {
    return registry.hasConnectedWorker(sourceNamespaceId);
  }

  record DispatchedProbe(ProbeRequest request, Future<ProbeAttemptReport> outcome) {}

  private static final class SessionCommands
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private final List<EstablishWorkerSessionResponse> responses = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Status> end = new CompletableFuture<>();

    @Override
    public void onNext(EstablishWorkerSessionResponse response) {
      responses.add(response);
    }

    @Override
    public void onError(Throwable failure) {
      end.complete(Status.fromThrowable(failure));
    }

    @Override
    public void onCompleted() {
      end.complete(Status.OK);
    }
  }
}
