package com.streamarr.server.services.streaming.headroom;

import com.streamarr.server.domain.streaming.headroom.PredictionCheck;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** One encode configuration's latest predictions, and what they say about its validation. */
final class PredictionWindow {

  enum Verdict {
    VALIDATED,
    FAILED,
    TOO_LITTLE_EVIDENCE
  }

  private static final int SIZE = 20;
  private static final Duration LIFETIME = Duration.ofDays(7);
  private static final int CONCURRENT_REQUIRED = 5;
  private static final int FAILING_REQUIRED = 5;
  private static final double VALIDATED_MEDIAN_ERROR = 0.10;
  // Within one encode configuration, two rungs this far apart separate the encode term from the
  // source terms.
  private static final double REQUIRED_RATIO_SPREAD = 2;

  private final ArrayDeque<PredictionCheck> checks = new ArrayDeque<>();

  void keep(PredictionCheck check) {
    checks.addLast(check);
    if (checks.size() > SIZE) {
      checks.removeFirst();
    }
  }

  /** The predictions measured within the lifetime before this time, oldest first. */
  List<PredictionCheck> live(Instant now) {
    var oldest = now.minus(LIFETIME);
    return checks.stream().filter(check -> check.measuredAt().isAfter(oldest)).toList();
  }

  /**
   * Failed when the current constants miss the live predictions' measurements by a median above 10
   * %, or when at least 5 live predictions have a median error above 10 %. Validated when 20 live
   * predictions pass both checks, at least 5 of them ran beside other encodes, and two of their
   * ratios of output to source differ by a factor of 2 or more. Otherwise the predictions are too
   * little evidence either way.
   */
  Verdict verdict(Instant now, ToDoubleFunction<PredictionCheck> currentError) {
    var live = live(now);
    if (!live.isEmpty() && medianError(live, currentError) > VALIDATED_MEDIAN_ERROR) {
      return Verdict.FAILED;
    }

    if (live.size() >= FAILING_REQUIRED
        && medianError(live, PredictionCheck::relativeError) > VALIDATED_MEDIAN_ERROR) {
      return Verdict.FAILED;
    }

    if (live.size() < SIZE || !isConcurrentAndSpread(live)) {
      return Verdict.TOO_LITTLE_EVIDENCE;
    }

    return Verdict.VALIDATED;
  }

  private static boolean isConcurrentAndSpread(List<PredictionCheck> live) {
    var concurrent = live.stream().filter(PredictionCheck::besideOtherEncodes).count();
    var ratios = live.stream().mapToDouble(PredictionCheck::outputToSourceRatio).toArray();
    var spread =
        Arrays.stream(ratios).max().orElseThrow() / Arrays.stream(ratios).min().orElseThrow();
    return concurrent >= CONCURRENT_REQUIRED && spread >= REQUIRED_RATIO_SPREAD;
  }

  /**
   * The largest overprediction among the live predictions and the current constants' predictions of
   * their measurements, or zero when none overpredicted.
   */
  double largestOverprediction(Instant now, ToDoubleFunction<PredictionCheck> currentError) {
    var largest =
        live(now).stream()
            .mapToDouble(
                check -> Math.max(check.relativeError(), currentError.applyAsDouble(check)))
            .max()
            .orElse(0);
    return Math.max(0, largest);
  }

  private static double medianError(
      List<PredictionCheck> live, ToDoubleFunction<PredictionCheck> error) {
    var errors =
        live.stream().mapToDouble(check -> Math.abs(error.applyAsDouble(check))).sorted().toArray();
    var middle = errors.length / 2;
    if (errors.length % 2 == 1) {
      return errors[middle];
    }

    return (errors[middle - 1] + errors[middle]) / 2;
  }
}
