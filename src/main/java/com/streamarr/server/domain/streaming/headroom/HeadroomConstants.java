package com.streamarr.server.domain.streaming.headroom;

import java.util.Map;
import java.util.OptionalDouble;
import lombok.Builder;
import lombok.NonNull;

/**
 * One worker's constants of the headroom model (ADR 0044): each constant's CPU seconds per
 * megapixel, and the cores that one job attempt of each encoder and preset uses when it runs alone
 * on the worker.
 */
@Builder
public record HeadroomConstants(
    @NonNull Map<CostConstant, Double> cpuSecondsPerMegapixel,
    @NonNull Map<EncoderAndPreset, Double> coresAlone) {

  public HeadroomConstants {
    cpuSecondsPerMegapixel = Map.copyOf(cpuSecondsPerMegapixel);
    coresAlone = Map.copyOf(coresAlone);
    cpuSecondsPerMegapixel.values().forEach(HeadroomConstants::requirePositive);
    coresAlone.values().forEach(HeadroomConstants::requirePositive);
  }

  /**
   * The job attempt's cost in CPU seconds per media second, or empty while a constant that the job
   * attempt needs is unknown.
   */
  public OptionalDouble cpuSecondsPerMediaSecond(@NonNull JobAttemptLoad load) {
    var cost = 0.0;
    for (var term : load.costTerms()) {
      var constant = cpuSecondsPerMegapixel.get(term.constant());
      if (constant == null) {
        return OptionalDouble.empty();
      }

      cost += constant * term.megapixelsPerSecond();
    }

    return OptionalDouble.of(cost);
  }

  /** The cores that the job attempt uses when it runs alone, or empty while that is unknown. */
  public OptionalDouble coresAlone(@NonNull JobAttemptLoad load) {
    var cores = coresAlone.get(load.encoderAndPreset());
    return cores == null ? OptionalDouble.empty() : OptionalDouble.of(cores);
  }

  private static void requirePositive(double value) {
    if (!Double.isFinite(value) || value <= 0) {
      throw new IllegalArgumentException("A headroom constant must be positive: " + value);
    }
  }
}
