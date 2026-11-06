package com.streamarr.server.domain.streaming.headroom;

import lombok.Builder;

/**
 * How far one prediction missed the production speed that the worker then measured. The relative
 * error is the predicted speed divided by the measured speed, minus one, so an overprediction is
 * positive. The ratio of the job attempt's output pixel rate to its source pixel rate tells which
 * rung of a ladder the prediction covered.
 */
@Builder
public record PredictionCheck(
    double relativeError, boolean besideOtherEncodes, double outputToSourceRatio) {

  public PredictionCheck {
    if (!Double.isFinite(relativeError)) {
      throw new IllegalArgumentException("A prediction's error must be finite: " + relativeError);
    }

    if (!Double.isFinite(outputToSourceRatio) || outputToSourceRatio <= 0) {
      throw new IllegalArgumentException(
          "A prediction's ratio of output to source must be positive: " + outputToSourceRatio);
    }
  }
}
