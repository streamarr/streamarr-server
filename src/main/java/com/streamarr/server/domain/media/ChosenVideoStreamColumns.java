package com.streamarr.server.domain.media;

import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.FieldOrder;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.FrameRatePacketScan;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.FrameRateRuleInputs;
import com.streamarr.server.domain.streaming.MatroskaStatistics;
import com.streamarr.server.domain.streaming.OutputFrameRateBasis;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * The columns of a probe outcome that name its chosen video stream and keep the frame-rate rule's
 * result. Hibernate reads every column as NULL for an outcome of an earlier probe version, so such
 * an outcome holds no instance.
 */
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChosenVideoStreamColumns {

  private Integer chosenVideoStreamIndex;

  @Enumerated(EnumType.STRING)
  private OutputFrameRateBasis outputFrameRateBasis;

  private Integer outputFrameRateNumerator;
  private Integer outputFrameRateDenominator;
  private Integer baseRateNumerator;
  private Integer baseRateDenominator;
  private Integer averageRateNumerator;
  private Integer averageRateDenominator;

  @Enumerated(EnumType.STRING)
  private FieldOrder fieldOrder;

  private Integer timeBaseNumerator;
  private Integer timeBaseDenominator;
  private Long matroskaNumberOfFrames;
  private Long matroskaDurationSeconds;
  private Integer matroskaDurationNanos;
  private Integer packetScanFrameCount;
  private Double packetScanMotionRate;
  private Long packetScanDurationSeconds;
  private Integer packetScanDurationNanos;

  public static ChosenVideoStreamColumns of(@NonNull ChosenVideoStream stream) {
    var columns = new ChosenVideoStreamColumns();
    var rule = stream.frameRateRule();
    var inputs = rule.inputs();
    columns.chosenVideoStreamIndex = stream.streamIndex();
    columns.outputFrameRateBasis = rule.basis();
    columns.outputFrameRateNumerator = numerator(rule.outputFrameRate());
    columns.outputFrameRateDenominator = denominator(rule.outputFrameRate());
    columns.baseRateNumerator = numerator(inputs.baseRate());
    columns.baseRateDenominator = denominator(inputs.baseRate());
    columns.averageRateNumerator = numerator(inputs.averageRate());
    columns.averageRateDenominator = denominator(inputs.averageRate());
    columns.fieldOrder = inputs.fieldOrder().orElse(null);
    columns.timeBaseNumerator = numerator(inputs.timeBase());
    columns.timeBaseDenominator = denominator(inputs.timeBase());
    inputs.matroskaStatistics().ifPresent(columns::matroskaStatistics);
    inputs.packetScan().ifPresent(columns::packetScan);
    return columns;
  }

  public ChosenVideoStream toChosenVideoStream() {
    return new ChosenVideoStream(
        chosenVideoStreamIndex,
        FrameRateRule.builder()
            .outputFrameRate(fraction(outputFrameRateNumerator, outputFrameRateDenominator))
            .basis(outputFrameRateBasis)
            .inputs(
                FrameRateRuleInputs.builder()
                    .baseRate(fraction(baseRateNumerator, baseRateDenominator))
                    .averageRate(fraction(averageRateNumerator, averageRateDenominator))
                    .fieldOrder(Optional.ofNullable(fieldOrder))
                    .timeBase(fraction(timeBaseNumerator, timeBaseDenominator))
                    .matroskaStatistics(matroskaStatistics())
                    .packetScan(packetScan())
                    .build())
            .build());
  }

  private void matroskaStatistics(MatroskaStatistics statistics) {
    matroskaNumberOfFrames =
        statistics.numberOfFrames().isPresent() ? statistics.numberOfFrames().getAsLong() : null;
    matroskaDurationSeconds = statistics.duration().map(Duration::getSeconds).orElse(null);
    matroskaDurationNanos = statistics.duration().map(Duration::getNano).orElse(null);
  }

  private void packetScan(FrameRatePacketScan scan) {
    packetScanFrameCount = scan.scannedFrameCount();
    packetScanMotionRate = scan.motionRate().isPresent() ? scan.motionRate().getAsDouble() : null;
    packetScanDurationSeconds = scan.scannedDuration().map(Duration::getSeconds).orElse(null);
    packetScanDurationNanos = scan.scannedDuration().map(Duration::getNano).orElse(null);
  }

  private Optional<MatroskaStatistics> matroskaStatistics() {
    if (matroskaNumberOfFrames == null && matroskaDurationSeconds == null) {
      return Optional.empty();
    }

    return Optional.of(
        new MatroskaStatistics(
            matroskaNumberOfFrames == null
                ? OptionalLong.empty()
                : OptionalLong.of(matroskaNumberOfFrames),
            duration(matroskaDurationSeconds, matroskaDurationNanos)));
  }

  private Optional<FrameRatePacketScan> packetScan() {
    if (packetScanFrameCount == null) {
      return Optional.empty();
    }

    return Optional.of(
        FrameRatePacketScan.builder()
            .motionRate(
                packetScanMotionRate == null
                    ? OptionalDouble.empty()
                    : OptionalDouble.of(packetScanMotionRate))
            .scannedFrameCount(packetScanFrameCount)
            .scannedDuration(duration(packetScanDurationSeconds, packetScanDurationNanos))
            .build());
  }

  private static Optional<Duration> duration(Long seconds, Integer nanos) {
    return seconds == null ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds, nanos));
  }

  private static Optional<Fraction> fraction(Integer numerator, Integer denominator) {
    return numerator == null ? Optional.empty() : Optional.of(new Fraction(numerator, denominator));
  }

  private static Integer numerator(Optional<Fraction> fraction) {
    return fraction.map(Fraction::numerator).orElse(null);
  }

  private static Integer denominator(Optional<Fraction> fraction) {
    return fraction.map(Fraction::denominator).orElse(null);
  }
}
