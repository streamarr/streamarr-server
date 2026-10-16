package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.jooq.generated.tables.records.MediaFileContainerInfoRecord;
import java.time.Duration;
import java.util.Optional;

// Writes the chosen video stream and the frame-rate rule's result into an outcome row.
final class ChosenVideoStreamRow {

  private ChosenVideoStreamRow() {}

  static void set(MediaFileContainerInfoRecord row, ChosenVideoStream stream) {
    var rule = stream.frameRateRule();
    var inputs = rule.inputs();
    row.setChosenVideoStreamIndex(stream.streamIndex());
    row.setOutputFrameRateBasis(rule.basis().name());
    row.setOutputFrameRateNumerator(numerator(rule.outputFrameRate()));
    row.setOutputFrameRateDenominator(denominator(rule.outputFrameRate()));
    row.setBaseRateNumerator(numerator(inputs.baseRate()));
    row.setBaseRateDenominator(denominator(inputs.baseRate()));
    row.setAverageRateNumerator(numerator(inputs.averageRate()));
    row.setAverageRateDenominator(denominator(inputs.averageRate()));
    row.setFieldOrder(inputs.fieldOrder().map(Enum::name).orElse(null));
    row.setTimeBaseNumerator(numerator(inputs.timeBase()));
    row.setTimeBaseDenominator(denominator(inputs.timeBase()));
    inputs
        .matroskaStatistics()
        .ifPresent(
            statistics -> {
              var frames = statistics.numberOfFrames();
              row.setMatroskaNumberOfFrames(frames.isPresent() ? frames.getAsLong() : null);
              row.setMatroskaDurationSeconds(
                  statistics.duration().map(Duration::getSeconds).orElse(null));
              row.setMatroskaDurationNanos(
                  statistics.duration().map(Duration::getNano).orElse(null));
            });
    inputs
        .packetScan()
        .ifPresent(
            scan -> {
              row.setPacketScanFrameCount(scan.scannedFrameCount());
              row.setPacketScanMotionRate(
                  scan.motionRate().isPresent() ? scan.motionRate().getAsDouble() : null);
              row.setPacketScanDurationSeconds(
                  scan.scannedDuration().map(Duration::getSeconds).orElse(null));
              row.setPacketScanDurationNanos(
                  scan.scannedDuration().map(Duration::getNano).orElse(null));
            });
  }

  private static Integer numerator(Optional<Fraction> fraction) {
    return fraction.map(Fraction::numerator).orElse(null);
  }

  private static Integer denominator(Optional<Fraction> fraction) {
    return fraction.map(Fraction::denominator).orElse(null);
  }
}
