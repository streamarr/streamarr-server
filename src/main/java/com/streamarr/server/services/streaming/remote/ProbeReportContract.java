package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import java.util.HashSet;
import java.util.Optional;

/**
 * The rules of the worker contract that the server checks on a decoded probe report before the
 * report completes its probe attempt. The mappers check what the report says about its source.
 */
final class ProbeReportContract {

  private ProbeReportContract() {}

  sealed interface Verdict {}

  /** The report keeps every rule. */
  record Kept() implements Verdict {}

  /**
   * The report names another probe version than the request. A wrongly versioned worker deployment
   * fails every probe at once, so this is not a contract violation, and the server checks no
   * further rule on a report that another version built.
   */
  record AnotherProbeVersion() implements Verdict {}

  /** The report breaks the rule. */
  record Broken(String rule) implements Verdict {}

  static Verdict check(ProbeAttemptReport report, ProbeRequest request, ProbeReportLimits limits) {
    if (!report.hasResult()) {
      return new Broken("Worker probe report holds no result");
    }

    var result = report.getResult();
    if (!result.getProbeAttemptId().equals(request.getProbeAttemptId())) {
      return new Broken("Worker probe report's result names another probe attempt");
    }

    if (result.getProbeVersion() != request.getProbeVersion()) {
      return new AnotherProbeVersion();
    }

    return brokenKeyframeRule(report, limits)
        .or(() -> brokenMeasurementRule(report, limits))
        .<Verdict>map(Broken::new)
        .orElseGet(Kept::new);
  }

  private static Optional<String> brokenKeyframeRule(
      ProbeAttemptReport report, ProbeReportLimits limits) {
    var keyframes = report.getKeyframeDecodeTimeDeltasCount();
    if (report.getKeyframePresentationTimeDeltasCount() != keyframes) {
      return Optional.of("Worker probe report's keyframe position lists differ in length");
    }

    if (keyframes > limits.keyframeLimit()) {
      return Optional.of("Worker probe report holds more keyframes than the keyframe limit");
    }

    return Optional.empty();
  }

  private static Optional<String> brokenMeasurementRule(
      ProbeAttemptReport report, ProbeReportLimits limits) {
    var streamIndexes = new HashSet<Integer>();
    var intervals = 0L;
    for (var stream : report.getStreamMeasurementIntervalsList()) {
      if (!hasCountsForTheSameIntervals(stream)) {
        return Optional.of(
            "Worker probe report's stream holds no keyframe intervals, or its interval lists"
                + " differ in length");
      }

      if (!streamIndexes.add(stream.getStreamIndex())) {
        return Optional.of("Worker probe report lists one stream's keyframe intervals twice");
      }

      intervals += stream.getKeyframeIntervalBytesCount();
    }

    if (intervals > limits.measuredKeyframeIntervalLimit()) {
      return Optional.of(
          "Worker probe report holds more measured keyframe intervals than the limit");
    }

    return Optional.empty();
  }

  private static boolean hasCountsForTheSameIntervals(StreamMeasurementIntervals stream) {
    var intervals = stream.getKeyframeIntervalBytesCount();
    return intervals > 0 && stream.getKeyframeIntervalPacketCountsCount() == intervals;
  }
}
