package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * The output frame rate that ADR 0037's frame-rate rule chose for the chosen video stream, with its
 * basis and inputs. The output frame rate is empty exactly when the rule refused to choose one.
 */
@Builder
public record FrameRateRule(
    @NonNull Optional<Fraction> outputFrameRate,
    @NonNull OutputFrameRateBasis basis,
    @NonNull FrameRateRuleInputs inputs) {

  public FrameRateRule {
    if (outputFrameRate.isEmpty() != (basis == OutputFrameRateBasis.REFUSED)) {
      throw new IllegalArgumentException(
          "Only a refused frame-rate rule lacks an output frame rate, not one with basis " + basis);
    }
  }
}
