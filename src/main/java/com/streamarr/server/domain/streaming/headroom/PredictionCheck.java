package com.streamarr.server.domain.streaming.headroom;

/**
 * How far one prediction missed the production speed that the worker then measured. The relative
 * error is the predicted speed divided by the measured speed, minus one, so an overprediction is
 * positive.
 */
public record PredictionCheck(double relativeError, boolean besideOtherEncodes) {

  public PredictionCheck {
    if (!Double.isFinite(relativeError)) {
      throw new IllegalArgumentException("A prediction's error must be finite: " + relativeError);
    }
  }
}
