package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.WorkerIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;

final class LiveWorkerConnectionRegistryFixture {

  private LiveWorkerConnectionRegistryFixture() {}

  static LiveWorkerConnectionRegistry defaultRegistry() {
    return registryWaking(new HeldSegmentRequests(new SimpleMeterRegistry()));
  }

  static LiveWorkerConnectionRegistry registryWaking(HeldSegmentRequests heldRequests) {
    return registry(WorkerSessionServerConfiguration.builder().build(), heldRequests);
  }

  static LiveWorkerConnectionRegistry registryWith(WorkerSessionServerConfiguration configuration) {
    return registry(configuration, new HeldSegmentRequests(new SimpleMeterRegistry()));
  }

  /** The first message of a report call, before it names a probe attempt and a length. */
  static ProbeAttemptReportMetadata.Builder reportingSession(
      UUID workerSessionId, WorkerIdentity worker) {
    return ProbeAttemptReportMetadata.newBuilder()
        .setWorkerSessionId(toProto(workerSessionId))
        .setWorker(worker);
  }

  /** Sends a probe report that holds the result alone, as the other overload sends a report. */
  static ReportCall report(
      LiveWorkerConnectionRegistry registry,
      ProbeAttemptReportMetadata.Builder session,
      ProbeAttemptResult result)
      throws Exception {
    return report(registry, session, ProbeAttemptReport.newBuilder().setResult(result).build());
  }

  /**
   * Sends the probe report to the registry as its worker does: in a report call of the worker that
   * the metadata names, for the probe attempt that the report's result names.
   */
  static ReportCall report(
      LiveWorkerConnectionRegistry registry,
      ProbeAttemptReportMetadata.Builder session,
      ProbeAttemptReport report)
      throws Exception {
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    return ReportCall.open(service, fromProto(session.getWorker().getWorkerId()))
        .report(session.clone().setProbeAttemptId(report.getResult().getProbeAttemptId()), report);
  }

  static LiveWorkerConnectionRegistry registryMeteredBy(MeterRegistry meterRegistry) {
    return LiveWorkerConnectionRegistry.builder()
        .eventPublisher(new CapturingEventPublisher())
        .configuration(WorkerSessionServerConfiguration.builder().build())
        .meterRegistry(meterRegistry)
        .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
        .build();
  }

  private static LiveWorkerConnectionRegistry registry(
      WorkerSessionServerConfiguration configuration, HeldSegmentRequests heldRequests) {
    return LiveWorkerConnectionRegistry.builder()
        .eventPublisher(new CapturingEventPublisher())
        .configuration(configuration)
        .meterRegistry(new SimpleMeterRegistry())
        .heldRequests(heldRequests)
        .build();
  }
}
