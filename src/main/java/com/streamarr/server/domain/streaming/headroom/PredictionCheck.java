package com.streamarr.server.domain.streaming.headroom;

import java.time.Instant;
import lombok.Builder;
import lombok.NonNull;

/**
 * How far one prediction missed the production speed that the worker then measured. The relative
 * error is the predicted speed divided by the measured speed, minus one, so an overprediction is
 * positive. The pixel rates and the measured cost let the server check later constants against the
 * same measurement. The measurement time tells when the prediction leaves its encode
 * configuration's window.
 */
@Builder(toBuilder = true)
public record PredictionCheck(
    double relativeError,
    boolean besideOtherEncodes,
    @NonNull PixelRate outputPixelRate,
    @NonNull PixelRate sourcePixelRate,
    double measuredCpuSecondsPerMediaSecond,
    @NonNull Instant measuredAt) {

  public PredictionCheck {
    if (!Double.isFinite(relativeError)) {
      throw new IllegalArgumentException("A prediction's error must be finite: " + relativeError);
    }

    if (!Double.isFinite(measuredCpuSecondsPerMediaSecond)
        || measuredCpuSecondsPerMediaSecond <= 0) {
      throw new IllegalArgumentException(
          "A measured cost must be positive: " + measuredCpuSecondsPerMediaSecond);
    }
  }

  /** Which rung of a ladder the prediction covered. */
  public double outputToSourceRatio() {
    return outputPixelRate.pixelsPerSecond() / sourcePixelRate.pixelsPerSecond();
  }

  /** How far constants that price the measured job attempt at this cost miss its measured speed. */
  public double relativeErrorAtCost(double cpuSecondsPerMediaSecond) {
    return measuredCpuSecondsPerMediaSecond / cpuSecondsPerMediaSecond - 1;
  }
}
