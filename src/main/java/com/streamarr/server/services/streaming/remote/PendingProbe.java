package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeRequest;
import io.grpc.Status;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One probe attempt that a worker session runs, from its start command until the worker's probe
 * report, the worker's event that the attempt ended without a probe report, or the end of the
 * worker session ends it.
 */
final class PendingProbe {

  private final ProbeRequest request;
  private final CompletableFuture<ProbeAttemptReport> result = new CompletableFuture<>();
  private final CompletableFuture<Void> terminated = new CompletableFuture<>();
  // How to end the one report call that reports the attempt, with a status. Unset until that call
  // names it.
  private final AtomicReference<Consumer<Status>> reportCallEnd = new AtomicReference<>();

  PendingProbe(ProbeRequest request) {
    this.request = request;
  }

  ProbeRequest request() {
    return request;
  }

  /** The outcome that the caller of the dispatch waits for. */
  CompletableFuture<ProbeAttemptReport> result() {
    return result;
  }

  /**
   * Completes when the probe has ended on the worker, or when the attempt ends without it. The
   * probe has ended on the worker when the first message of its report call arrives, or when the
   * worker's event ends the attempt without a probe report.
   */
  CompletableFuture<Void> terminated() {
    return terminated;
  }

  /**
   * Fails the attempt with a {@link java.util.concurrent.TimeoutException} unless the probe ends on
   * the worker within the time. The time that the worker then takes to send its probe report does
   * not count.
   */
  void timeOutUnlessEndedOnWorkerWithin(long timeoutNanos) {
    var timeout = new CompletableFuture<Void>().orTimeout(timeoutNanos, TimeUnit.NANOSECONDS);
    timeout.exceptionally(
        failure -> {
          result.completeExceptionally(failure);
          return null;
        });
    terminated.whenComplete((_, _) -> timeout.complete(null));
  }

  /**
   * The first message of the attempt's report call has arrived, so the probe has ended on the
   * worker. From here on only the bounds of the report call apply.
   */
  void endOnWorker() {
    terminated.complete(null);
  }

  /** Ends the attempt with the probe report. */
  void complete(ProbeAttemptReport report) {
    terminated.complete(null);
    result.complete(report);
  }

  /**
   * Ends the attempt as misconfigured. Not a contract violation: a wrongly versioned worker
   * deployment fails every probe at once, and fixing the deployment changes neither a source nor
   * its probe version.
   */
  void failForAnotherVersion(ProbeAttemptResult reported) {
    terminated.complete(null);
    result.completeExceptionally(
        new ProbeExecutionException(
            ItemFailureReason.MISCONFIGURED,
            "Worker probe reply version mismatch: expected %s, received %s"
                .formatted(
                    Integer.toUnsignedString(request.getProbeVersion()),
                    Integer.toUnsignedString(reported.getProbeVersion()))));
  }

  /** Ends the attempt with a contract violation, which counts against its probe. */
  void failForContractViolation(String message) {
    terminated.complete(null);
    result.completeExceptionally(new ProbeContractViolationException(message));
  }

  /** Ends the attempt with a failure that the caller retries, as when its worker is lost. */
  void failForRetry(String reason) {
    terminated.complete(null);
    result.completeExceptionally(new ProbeExecutionException(ItemFailureReason.TEMPORARY, reason));
  }

  /**
   * Gives the attempt its one report call. Returns false, and keeps the earlier call, when a report
   * call already reports the attempt.
   */
  boolean tryStartReportCall(Consumer<Status> endReportCall) {
    return reportCallEnd.compareAndSet(null, endReportCall);
  }

  /**
   * Ends the attempt's report call with the status, when one reports it, because the attempt ended
   * without it.
   */
  void endReportCall(Status status) {
    var end = reportCallEnd.get();
    if (end != null) {
      end.accept(status);
    }
  }
}
