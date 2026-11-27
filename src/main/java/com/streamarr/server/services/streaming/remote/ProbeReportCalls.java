package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.ReportProbeAttemptRequest;
import com.streamarr.transcode.v1.ReportProbeAttemptResponse;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * The report calls that workers hold open. One worker holds at most {@link
 * ProbeReportLimits#REPORT_CALLS_PER_WORKER} of them, so that its report calls, its segment uploads
 * and its worker session fit the calls of one connection together.
 */
@Slf4j
final class ProbeReportCalls {

  private static final StreamObserver<ReportProbeAttemptRequest> REFUSED_CALL =
      new StreamObserver<>() {
        @Override
        public void onNext(ReportProbeAttemptRequest request) {
          // The call has already ended with an error.
        }

        @Override
        public void onError(Throwable failure) {
          // The call has already ended with an error.
        }

        @Override
        public void onCompleted() {
          // The call has already ended with an error.
        }
      };

  private final LiveWorkerConnectionRegistry workerConnections;
  private final ProbeReportLimits limits;
  private final ProbeReportMemoryBudget memoryBudget;
  private final Map<UUID, Integer> openCallsByWorker = new HashMap<>();

  ProbeReportCalls(LiveWorkerConnectionRegistry workerConnections) {
    this.workerConnections = workerConnections;
    limits = workerConnections.probeReportLimits();
    memoryBudget = new ProbeReportMemoryBudget(limits.memoryBudgetBytes());
  }

  /** Opens the worker's report call, or refuses it with a status. */
  StreamObserver<ReportProbeAttemptRequest> open(
      UUID authenticatedWorkerId, StreamObserver<ReportProbeAttemptResponse> responseObserver) {
    if (authenticatedWorkerId == null) {
      // The identity interceptor rejects before reaching here, so this means the server was wired
      // without it. Fail closed rather than read a report that no worker's limit can bound.
      log.error("Rejecting probe report call with no authenticated worker identity in context");
      responseObserver.onError(
          Status.UNAUTHENTICATED
              .withDescription("Worker identity is required")
              .asRuntimeException());
      return REFUSED_CALL;
    }

    if (!tryTakePlace(authenticatedWorkerId)) {
      log.warn(
          "Rejecting probe report call from worker {}: its probe report limit is reached",
          authenticatedWorkerId);
      responseObserver.onError(
          Status.RESOURCE_EXHAUSTED
              .withDescription("Probe report limit reached")
              .asRuntimeException());
      return REFUSED_CALL;
    }

    var observer =
        ProbeReportObserver.builder()
            .authenticatedWorkerId(authenticatedWorkerId)
            .workerConnections(workerConnections)
            .limits(limits)
            .memoryBudget(memoryBudget)
            // gRPC hands a service the response observer of a server call.
            .call((ServerCallStreamObserver<ReportProbeAttemptResponse>) responseObserver)
            .whenClosed(() -> freePlace(authenticatedWorkerId))
            .build();
    observer.start();
    return observer;
  }

  private synchronized boolean tryTakePlace(UUID workerId) {
    var open = openCallsByWorker.getOrDefault(workerId, 0);
    if (open == ProbeReportLimits.REPORT_CALLS_PER_WORKER) {
      return false;
    }

    openCallsByWorker.put(workerId, open + 1);
    return true;
  }

  private synchronized void freePlace(UUID workerId) {
    openCallsByWorker.computeIfPresent(workerId, (_, open) -> open == 1 ? null : open - 1);
  }
}
