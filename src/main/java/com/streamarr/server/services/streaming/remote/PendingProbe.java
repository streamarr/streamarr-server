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

/**
 * One probe attempt that a worker session runs, from its start command until the worker's reply or
 * the end of the worker session ends it.
 */
final class PendingProbe {

  private final ProbeRequest request;
  private final CompletableFuture<ProbeAttemptReport> result = new CompletableFuture<>();
  private final CompletableFuture<Void> terminated = new CompletableFuture<>();
  private final ReceivedKeyframePositionsParts keyframePositionsParts =
      new ReceivedKeyframePositionsParts();
  private final ReceivedStreamMeasurementParts streamMeasurementParts =
      new ReceivedStreamMeasurementParts();

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
    terminated.complete(null);
    // Not a contract violation: a wrongly versioned worker deployment fails every probe at once,
    // and fixing the deployment changes neither a source nor its probe version.
    if (reported.getProbeVersion() != request.getProbeVersion()) {
      result.completeExceptionally(
          new ProbeExecutionException(
              ItemFailureReason.MISCONFIGURED,
              "Worker probe reply version mismatch: expected %s, received %s"
                  .formatted(
                      Integer.toUnsignedString(request.getProbeVersion()),
                      Integer.toUnsignedString(reported.getProbeVersion()))));
      return false;
    }

    if (keyframePositionsParts.violatesContract()) {
      result.completeExceptionally(
          new ProbeContractViolationException(
              "Worker probe sent keyframe positions parts that break the contract"));
      return false;
    }

    // The contract discards the parts of an attempt whose result reports no measurement.
    if (reportsMeasurement(reported) && streamMeasurementParts.violatesContract()) {
      result.completeExceptionally(
          new ProbeContractViolationException(
              "Worker probe sent stream measurement parts that break the contract"));
      return false;
    }

    result.complete(reportOf(reported));
    return true;
  }

  /** Ends the attempt with a failure that the caller retries, as when its worker is lost. */
  void failForRetry(String reason) {
    terminated.complete(null);
    result.completeExceptionally(new ProbeExecutionException(ItemFailureReason.TEMPORARY, reason));
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
