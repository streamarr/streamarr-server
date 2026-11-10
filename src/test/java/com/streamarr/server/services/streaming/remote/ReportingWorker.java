package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
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
