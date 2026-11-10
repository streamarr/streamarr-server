package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One probe attempt that a worker session runs, from its start command until the worker's probe
 * report or the end of the worker session ends it.
 */
final class PendingProbe {

  private final ProbeRequest request;
  private final CompletableFuture<ProbeAttemptReport> result = new CompletableFuture<>();
  private final CompletableFuture<Void> terminated = new CompletableFuture<>();
  private final ReceivedKeyframePositionsParts keyframePositionsParts =
      new ReceivedKeyframePositionsParts();
  private final ReceivedStreamMeasurementParts streamMeasurementParts =
      new ReceivedStreamMeasurementParts();
  // How to end the one report call that reports the attempt. Unset until that call names it.
  private final AtomicReference<Runnable> reportCallEnd = new AtomicReference<>();

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

  /** Completes when the probe has ended on the worker, or when the attempt ends without it. */
  CompletableFuture<Void> terminated() {
    return terminated;
  }

  void add(ProbeKeyframePositionsPart part) {
    keyframePositionsParts.add(part);
  }

  void add(ProbeStreamMeasurementPart part) {
    streamMeasurementParts.add(part);
  }

  /**
   * Ends the attempt with the worker's result and the parts that arrived before it. Returns false,
   * and fails the attempt, when the result names another probe version or the parts break the
   * contract.
   */
  boolean tryComplete(ProbeAttemptResult reported) {
    if (reported.getProbeVersion() != request.getProbeVersion()) {
      failForAnotherVersion(reported);
      return false;
    }

    if (keyframePositionsParts.violatesContract()) {
      failForContractViolation(
          "Worker probe sent keyframe positions parts that break the contract");
      return false;
    }

    // The contract discards the parts of an attempt whose result reports no measurement.
    if (reportsMeasurement(reported) && streamMeasurementParts.violatesContract()) {
      failForContractViolation(
          "Worker probe sent stream measurement parts that break the contract");
      return false;
    }

    complete(reportOf(reported));
    return true;
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
  boolean tryStartReportCall(Runnable endReportCall) {
    return reportCallEnd.compareAndSet(null, endReportCall);
  }

  /** Ends the attempt's report call, when one reports it, because the attempt ended without it. */
  void endReportCall() {
    var end = reportCallEnd.get();
    if (end != null) {
      end.run();
    }
  }

  // The probe report that the result and the joined parts make.
  private ProbeAttemptReport reportOf(ProbeAttemptResult reported) {
    var report = ProbeAttemptReport.newBuilder().setResult(reported);
    keyframePositionsParts
        .parts()
        .forEach(
            part ->
                report
                    .addAllKeyframePresentationTimeDeltas(part.getPresentationTimeDeltasList())
                    .addAllKeyframeDecodeTimeDeltas(part.getDecodeTimeDeltasList()));
    if (reportsMeasurement(reported)) {
      streamMeasurementParts
          .parts()
          .forEach(part -> report.addStreamMeasurementIntervals(intervalsOf(part)));
    }

    return report.build();
  }

  private static StreamMeasurementIntervals intervalsOf(ProbeStreamMeasurementPart part) {
    return StreamMeasurementIntervals.newBuilder()
        .setStreamIndex(part.getStreamIndex())
        .addAllKeyframeIntervalBytes(part.getKeyframeIntervalBytesList())
        .addAllKeyframeIntervalPacketCounts(part.getKeyframeIntervalPacketCountsList())
        .build();
  }

  private static boolean reportsMeasurement(ProbeAttemptResult reported) {
    return reported.getMedia().hasStreamMeasurements()
        || reported.getBitrateScanChunk().hasMeasured();
  }
}
