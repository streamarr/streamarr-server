package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/** The inputs of ADR 0037's frame-rate rule for the chosen video stream. */
@Builder
public record FrameRateRuleInputs(
    @NonNull Optional<Fraction> baseRate,
    @NonNull Optional<Fraction> averageRate,
    @NonNull Optional<FieldOrder> fieldOrder,
    @NonNull Optional<Fraction> timeBase,
    @NonNull Optional<MatroskaStatistics> matroskaStatistics,
    @NonNull Optional<FrameRatePacketScan> packetScan) {

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class FrameRateRuleInputsBuilder {
    private Optional<Fraction> baseRate = Optional.empty();
    private Optional<Fraction> averageRate = Optional.empty();
    private Optional<FieldOrder> fieldOrder = Optional.empty();
    private Optional<Fraction> timeBase = Optional.empty();
    private Optional<MatroskaStatistics> matroskaStatistics = Optional.empty();
    private Optional<FrameRatePacketScan> packetScan = Optional.empty();
  }
}
