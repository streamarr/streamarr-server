package com.streamarr.server.services.streaming.headroom;

import com.streamarr.server.domain.streaming.headroom.CpuLimit;
import com.streamarr.server.domain.streaming.headroom.PixelRate;
import com.streamarr.server.domain.streaming.headroom.DecodeBackend;
import com.streamarr.server.domain.streaming.headroom.SourceDecoder;
import com.streamarr.server.domain.streaming.headroom.EncoderAndPreset;
import com.streamarr.server.domain.streaming.headroom.ToneMapping;
import com.streamarr.server.domain.streaming.headroom.JobAttemptLoad;
import com.streamarr.server.domain.streaming.headroom.HeadroomConstants;
import com.streamarr.server.domain.streaming.headroom.PredictionCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.EncoderPreset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Calibration Tests")
class WorkerCalibrationTest {

  private static final Duration SEGMENT = Duration.ofSeconds(3);
  // 25 megapixels per second of output and 50 of source keep each cost a round number.
  private static final PixelRate SMALL_OUTPUT = PixelRate.of(1000, 1000, 25);
  private static final PixelRate LARGE_OUTPUT = PixelRate.of(2000, 1000, 25);
  private static final PixelRate SOURCE = PixelRate.of(2000, 1000, 25);
  private static final EncoderBackend LIBX264 =
      EncoderBackend.builder()
          .codecFamily("h264")
          .encoder("libx264")
          .kind(EncoderBackend.Kind.SOFTWARE)
          .build();
  private static final EncoderAndPreset LIBX264_FASTER =
      new EncoderAndPreset(LIBX264, EncoderPreset.FASTER);
  private static final SourceDecoder SOFTWARE_H264 =
      new SourceDecoder("h264", DecodeBackend.SOFTWARE);

  // Small output: 0.04 x 25 + 0.01 x 50 = 1.5 CPU s per media s. Large output: 2.5.
  private static final HeadroomConstants ACCURATE =
      HeadroomConstants.builder()
          .cpuSecondsPerMegapixel(Map.of(LIBX264_FASTER, 0.04, SOFTWARE_H264, 0.01))
          .coresAlone(Map.of(LIBX264_FASTER, 6.0))
          .build();

  private static JobAttemptLoad.JobAttemptLoadBuilder libx264FasterLoad() {
    return JobAttemptLoad.builder()
        .encoderAndPreset(LIBX264_FASTER)
        .outputPixelRate(SMALL_OUTPUT)
        .sourceDecoder(SOFTWARE_H264)
        .sourcePixelRate(SOURCE)
        .toneMapping(ToneMapping.NONE);
  }

  private static WorkerCalibration.WorkerCalibrationBuilder tenCoreCalibration() {
    return WorkerCalibration.builder()
        .cpuLimit(new CpuLimit(10))
        .startingConstants(ACCURATE);
  }

  private static Duration productionTimeAtSpeed(double productionSpeed) {
    return Duration.ofNanos(Math.round(SEGMENT.toNanos() / productionSpeed));
  }

  /** A measurement whose speed matches the true cost when the worker runs that many encodes. */
  private static ProductionMeasurement measuredAtCost(
      JobAttemptLoad load, double cpuSecondsPerMediaSecond, int encodesRunning) {
    var share = Math.min(10.0 / encodesRunning, 6.0);
    return ProductionMeasurement.builder()
        .load(load)
        .otherEncodes(Collections.nCopies(encodesRunning - 1, load))
        .mediaDuration(SEGMENT)
        .productionTime(productionTimeAtSpeed(share / cpuSecondsPerMediaSecond))
        .build();
  }

  private static double predictedAloneSpeed(WorkerCalibration calibration, JobAttemptLoad load) {
    var attempt = UUID.randomUUID();
    return calibration.predictProductionSpeeds(Map.of(attempt, load)).orElseThrow().get(attempt);
  }

  @Nested
  @DisplayName("Speed Prediction Tests")
  class SpeedPredictionTests {

    @Test
    @DisplayName(
        "Should give each job attempt an equal part of the CPU limit when several encodes run on the worker")
    void shouldGiveEachJobAttemptAnEqualPartOfTheCpuLimitWhenSeveralEncodesRunOnTheWorker() {
      var calibration = tenCoreCalibration().cpuLimit(new CpuLimit(9)).build();
      var small = UUID.randomUUID();
      var large = UUID.randomUUID();
      var other = UUID.randomUUID();

      var speeds =
          calibration.predictProductionSpeeds(
              Map.of(
                  small, libx264FasterLoad().build(),
                  large, libx264FasterLoad().outputPixelRate(LARGE_OUTPUT).build(),
                  other, libx264FasterLoad().build()));

      // 9 cores over three job attempts: 3 cores each, divided by each cost.
      assertThat(speeds)
          .hasValueSatisfying(
              predicted -> {
                assertThat(predicted.get(small)).isCloseTo(3 / 1.5, within(1e-9));
                assertThat(predicted.get(large)).isCloseTo(3 / 2.5, within(1e-9));
                assertThat(predicted.get(other)).isCloseTo(3 / 1.5, within(1e-9));
              });
    }

    @Test
    @DisplayName(
        "Should cap a job attempt's share at the cores that one job attempt uses alone when it runs alone")
    void shouldCapAJobAttemptsShareAtTheCoresThatOneJobAttemptUsesAloneWhenItRunsAlone() {
      var calibration = tenCoreCalibration().build();

      assertThat(predictedAloneSpeed(calibration, libx264FasterLoad().build()))
          .isCloseTo(6 / 1.5, within(1e-9));
    }

    @Test
    @DisplayName("Should predict no speeds when a running job attempt's constant is unknown")
    void shouldPredictNoSpeedsWhenARunningJobAttemptsConstantIsUnknown() {
      var calibration = tenCoreCalibration().build();
      var veryfast = new EncoderAndPreset(LIBX264, EncoderPreset.VERYFAST);

      var speeds =
          calibration.predictProductionSpeeds(
              Map.of(
                  UUID.randomUUID(), libx264FasterLoad().build(),
                  UUID.randomUUID(), libx264FasterLoad().encoderAndPreset(veryfast).build()));

      assertThat(speeds).isEmpty();
    }
  }

  @Nested
  @DisplayName("Continuous Fit Tests")
  class ContinuousFitTests {

    @Test
    @DisplayName(
        "Should predict the recorded production speeds when the constants are fitted from the recorded production times")
    void shouldPredictTheRecordedProductionSpeedsWhenTheConstantsAreFittedFromTheRecordedProductionTimes() {
      var runs = RecordedCapacityRuns.load();
      var calibration =
          WorkerCalibration.builder()
              .cpuLimit(RecordedCapacityRuns.CPU_LIMIT)
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(Map.of())
                      .coresAlone(RecordedCapacityRuns.CORES_ALONE)
                      .build())
              .build();
      for (var run : runs) {
        for (var variant : run.variants()) {
          calibration.fit(
              ProductionMeasurement.builder()
                  .load(variant.load())
                  .otherEncodes(run.loadsBeside(variant))
                  .mediaDuration(variant.segment())
                  .productionTime(variant.productionTime())
                  .build());
        }
      }

      var errors = new ArrayList<Double>();
      for (var run : runs) {
        var loads = new LinkedHashMap<UUID, RecordedCapacityRuns.RecordedVariant>();
        run.variants().forEach(variant -> loads.put(UUID.randomUUID(), variant));
        var predicted =
            calibration
                .predictProductionSpeeds(
                    loads.entrySet().stream()
                        .collect(
                            Collectors.toMap(
                                Map.Entry::getKey, entry -> entry.getValue().load())))
                .orElseThrow();
        loads.forEach(
            (attempt, variant) ->
                errors.add(
                    Math.abs(predicted.get(attempt) - variant.productionSpeed())
                        / variant.productionSpeed()));
      }

      errors.sort(Comparator.naturalOrder());
      // ADR 0044's evidence: equal parts of the cores predict these runs with a median error of
      // 5 % and a largest error of 21 %.
      assertThat(errors).hasSize(180);
      assertThat(errors.get(errors.size() / 2)).as("median relative error").isLessThan(0.10);
      assertThat(errors.getLast()).as("largest relative error").isLessThan(0.25);
    }

    @Test
    @DisplayName("Should keep the starting constants when no measurement has arrived")
    void shouldKeepTheStartingConstantsWhenNoMeasurementHasArrived() {
      var calibration = tenCoreCalibration().build();

      assertThat(calibration.constants()).isEqualTo(ACCURATE);
    }

    @Test
    @DisplayName(
        "Should predict between the benchmark and the measurement when one measurement disagrees with the benchmark")
    void shouldPredictBetweenTheBenchmarkAndTheMeasurementWhenOneMeasurementDisagreesWithTheBenchmark() {
      var calibration = tenCoreCalibration().build();
      var load = libx264FasterLoad().build();

      calibration.fit(measuredAtCost(load, 3.0, 1));

      // The benchmark predicts 6 / 1.5 = 4.0 alone; the worker measured 6 / 3.0 = 2.0.
      assertThat(predictedAloneSpeed(calibration, load)).isStrictlyBetween(2.0, 4.0);
    }

    @Test
    @DisplayName(
        "Should predict the measured speed when many measurements disagree with the benchmark")
    void shouldPredictTheMeasuredSpeedWhenManyMeasurementsDisagreeWithTheBenchmark() {
      var calibration = tenCoreCalibration().build();
      var small = libx264FasterLoad().build();
      var large = libx264FasterLoad().outputPixelRate(LARGE_OUTPUT).build();

      for (var i = 0; i < 300; i++) {
        calibration.fit(measuredAtCost(small, 3.0, 1));
        calibration.fit(measuredAtCost(large, 5.0, 2));
      }

      assertThat(predictedAloneSpeed(calibration, small)).isCloseTo(6 / 3.0, within(0.1));
      assertThat(predictedAloneSpeed(calibration, large)).isCloseTo(6 / 5.0, within(0.06));
    }

    @Test
    @DisplayName(
        "Should follow the worker's newer measurements when the content it encodes turns costlier")
    void shouldFollowTheWorkersNewerMeasurementsWhenTheContentItEncodesTurnsCostlier() {
      var calibration = tenCoreCalibration().build();
      var load = libx264FasterLoad().build();
      for (var i = 0; i < 300; i++) {
        calibration.fit(measuredAtCost(load, 1.5, 2));
      }

      for (var i = 0; i < 600; i++) {
        calibration.fit(measuredAtCost(load, 2.25, 2));
      }

      // Weighing every measurement alike would predict a cost near 1.9 and a speed near 3.2.
      assertThat(predictedAloneSpeed(calibration, load)).isCloseTo(6 / 2.25, within(0.1));
    }
  }

  @Nested
  @DisplayName("Unequal Ceiling Tests")
  class UnequalCeilingTests {

    @Test
    @DisplayName(
        "Should give the other job attempt the cores that a job attempt with a lower ceiling leaves when both run")
    void shouldGiveTheOtherJobAttemptTheCoresThatAJobAttemptWithALowerCeilingLeavesWhenBothRun() {
      var nvenc =
          new EncoderAndPreset(
              EncoderBackend.builder()
                  .codecFamily("h264")
                  .encoder("h264_nvenc")
                  .kind(EncoderBackend.Kind.HARDWARE)
                  .build(),
              EncoderPreset.FASTER);
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(
                          Map.of(LIBX264_FASTER, 0.04, nvenc, 0.002, SOFTWARE_H264, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 8.0, nvenc, 2.0))
                      .build())
              .build();
      var software = UUID.randomUUID();
      var hardware = UUID.randomUUID();

      var speeds =
          calibration.predictProductionSpeeds(
              Map.of(
                  software, libx264FasterLoad().build(),
                  hardware, libx264FasterLoad().encoderAndPreset(nvenc).build()));

      // 10 cores: the NVENC attempt uses at most 2 alone, so libx264 gets the other 8, not 5.
      assertThat(speeds)
          .hasValueSatisfying(
              predicted -> {
                assertThat(predicted.get(software)).isCloseTo(8 / 1.5, within(1e-9));
                assertThat(predicted.get(hardware)).isCloseTo(2 / (0.002 * 25 + 0.5), within(1e-9));
              });
    }
  }

  @Nested
  @DisplayName("Dormant Constant Tests")
  class DormantConstantTests {

    @Test
    @DisplayName(
        "Should keep following the active constants when a constant that no starting value names stops appearing")
    void shouldKeepFollowingTheActiveConstantsWhenAConstantThatNoStartingValueNamesStopsAppearing() {
      var libx264Medium = new EncoderAndPreset(LIBX264, EncoderPreset.MEDIUM);
      // The benchmark measured medium's cores alone but gave it no cost constant.
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(Map.of(LIBX264_FASTER, 0.04, SOFTWARE_H264, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0, libx264Medium, 6.0))
                      .build())
              .build();
      var faster = libx264FasterLoad().build();
      var medium = libx264FasterLoad().encoderAndPreset(libx264Medium).build();
      calibration.fit(measuredAtCost(medium, 2.5, 1));
      for (var i = 0; i < 2000; i++) {
        calibration.fit(measuredAtCost(faster, 1.5, 2));
      }

      for (var i = 0; i < 1000; i++) {
        calibration.fit(measuredAtCost(faster, 3.0, 2));
      }

      assertThat(predictedAloneSpeed(calibration, faster)).isCloseTo(6 / 3.0, within(0.1));
    }
  }

  @Nested
  @DisplayName("Unseparated Constant Tests")
  class UnseparatedConstantTests {

    private WorkerCalibration calibrationWithoutBenchmark() {
      return tenCoreCalibration()
          .startingConstants(
              HeadroomConstants.builder()
                  .cpuSecondsPerMegapixel(Map.of())
                  .coresAlone(Map.of(LIBX264_FASTER, 6.0))
                  .build())
          .build();
    }

    @Test
    @DisplayName(
        "Should predict no speeds when one load's measurements cannot separate its encode and decode constants")
    void shouldPredictNoSpeedsWhenOneLoadsMeasurementsCannotSeparateItsEncodeAndDecodeConstants() {
      var calibration = calibrationWithoutBenchmark();
      var load = libx264FasterLoad().build();

      for (var i = 0; i < 10; i++) {
        calibration.fit(measuredAtCost(load, 1.5, 2));
      }

      assertThat(calibration.predictProductionSpeeds(Map.of(UUID.randomUUID(), load))).isEmpty();
    }

    @Test
    @DisplayName("Should keep no constants when the measurements imply a negative decode constant")
    void shouldKeepNoConstantsWhenTheMeasurementsImplyANegativeDecodeConstant() {
      var calibration = calibrationWithoutBenchmark();
      var small = libx264FasterLoad().build();
      var large = libx264FasterLoad().outputPixelRate(LARGE_OUTPUT).build();

      // Doubling the output pixel rate triples the cost, so the decode constant would be negative.
      for (var i = 0; i < 10; i++) {
        calibration.fit(measuredAtCost(small, 1.0, 2));
        calibration.fit(measuredAtCost(large, 3.0, 2));
      }

      assertThat(calibration.constants().cpuSecondsPerMegapixel()).isEmpty();
    }
  }

  @Nested
  @DisplayName("Validation Tests")
  class ValidationTests {

    @Test
    @DisplayName(
        "Should count the constants as validated when they predict twenty measured speeds that include concurrent encodes")
    void shouldCountTheConstantsAsValidatedWhenTheyPredictTwentyMeasuredSpeedsThatIncludeConcurrentEncodes() {
      var calibration = tenCoreCalibration().build();
      var load = libx264FasterLoad().build();
      for (var i = 0; i < 19; i++) {
        calibration.fit(measuredAtCost(load, 1.5, 1 + i % 3));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).as("after 19 measurements").isFalse();

      calibration.fit(measuredAtCost(load, 1.5, 2));

      assertThat(calibration.isValidatedFor(List.of(load))).as("after 20 measurements").isTrue();
    }

    @Test
    @DisplayName(
        "Should leave the constants unvalidated when no measured job attempt ran beside another encode")
    void shouldLeaveTheConstantsUnvalidatedWhenNoMeasuredJobAttemptRanBesideAnotherEncode() {
      var calibration = tenCoreCalibration().build();
      var load = libx264FasterLoad().build();

      for (var i = 0; i < 40; i++) {
        calibration.fit(measuredAtCost(load, 1.5, 1));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).isFalse();
    }

    @Test
    @DisplayName(
        "Should leave the constants unvalidated when the measured speeds of one load scatter beyond any prediction")
    void shouldLeaveTheConstantsUnvalidatedWhenTheMeasuredSpeedsOfOneLoadScatterBeyondAnyPrediction() {
      var calibration = tenCoreCalibration().build();
      var load = libx264FasterLoad().build();

      for (var i = 0; i < 100; i++) {
        calibration.fit(measuredAtCost(load, i % 2 == 0 ? 1.5 : 3.0, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).isFalse();
    }

    @Test
    @DisplayName(
        "Should validate the constants when the continuous fit has learned the cost that the benchmark missed")
    void shouldValidateTheConstantsWhenTheContinuousFitHasLearnedTheCostThatTheBenchmarkMissed() {
      var calibration = tenCoreCalibration().build();
      var load = libx264FasterLoad().build();

      for (var i = 0; i < 200; i++) {
        calibration.fit(measuredAtCost(load, 3.0, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).isTrue();
    }

    @Test
    @DisplayName(
        "Should lose the validation when the constants start missing the measured speeds")
    void shouldLoseTheValidationWhenTheConstantsStartMissingTheMeasuredSpeeds() {
      var calibration = tenCoreCalibration().build();
      var load = libx264FasterLoad().build();
      for (var i = 0; i < 20; i++) {
        calibration.fit(measuredAtCost(load, 1.5, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).as("while the constants predict").isTrue();

      for (var i = 0; i < 20; i++) {
        calibration.fit(measuredAtCost(load, 3.0, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).as("after the content turned costlier").isFalse();
    }

    @Test
    @DisplayName(
        "Should keep the validation when a calibration starts from a kept calibration's latest predictions")
    void shouldKeepTheValidationWhenACalibrationStartsFromAKeptCalibrationsLatestPredictions() {
      var kept = tenCoreCalibration().build();
      var load = libx264FasterLoad().build();
      for (var i = 0; i < 20; i++) {
        kept.fit(measuredAtCost(load, 1.5, 2));
      }

      var restarted =
          tenCoreCalibration()
              .startingConstants(kept.constants())
              .latestPredictions(kept.latestPredictions())
              .build();

      assertThat(restarted.isValidatedFor(List.of(load))).isTrue();
    }

    @Test
    @DisplayName(
        "Should check each prediction against constants that had not yet fitted that measurement")
    void shouldCheckEachPredictionAgainstConstantsThatHadNotYetFittedThatMeasurement() {
      var calibration = tenCoreCalibration().build();

      calibration.fit(measuredAtCost(libx264FasterLoad().build(), 3.0, 1));

      // The benchmark predicted 6 / 1.5 = 4.0, and the worker measured 6 / 3.0 = 2.0.
      assertThat(calibration.latestPredictions().get(LIBX264_FASTER))
          .singleElement()
          .satisfies(
              check -> {
                assertThat(check.relativeError()).isCloseTo(1.0, within(1e-6));
                assertThat(check.besideOtherEncodes()).isFalse();
              });
    }

    @Test
    @DisplayName(
        "Should keep only a constant's latest 20 predictions when a kept calibration brings more")
    void shouldKeepOnlyAConstantsLatest20PredictionsWhenAKeptCalibrationBringsMore() {
      var kept = new ArrayList<PredictionCheck>();
      for (var i = 0; i < 25; i++) {
        kept.add(new PredictionCheck(i / 100.0, true));
      }

      var predictions =
          tenCoreCalibration()
              .latestPrediction(LIBX264_FASTER, kept)
              .build()
              .latestPredictions()
              .get(LIBX264_FASTER);

      assertThat(predictions).hasSize(20);
      assertThat(predictions.getFirst().relativeError()).isCloseTo(0.05, within(1e-12));
      assertThat(predictions.getLast().relativeError()).isCloseTo(0.24, within(1e-12));
    }

    @Test
    @DisplayName(
        "Should leave another encoder's constant unvalidated when only libx264 measurements arrived")
    void shouldLeaveAnotherEncodersConstantUnvalidatedWhenOnlyLibx264MeasurementsArrived() {
      var libx265 =
          EncoderBackend.builder()
              .codecFamily("hevc")
              .encoder("libx265")
              .kind(EncoderBackend.Kind.SOFTWARE)
              .build();
      var libx265Veryfast = new EncoderAndPreset(libx265, EncoderPreset.VERYFAST);
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(
                          Map.of(LIBX264_FASTER, 0.04, libx265Veryfast, 0.12, SOFTWARE_H264, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0, libx265Veryfast, 6.0))
                      .build())
              .build();
      var libx264Load = libx264FasterLoad().build();
      var libx265Load = libx264FasterLoad().encoderAndPreset(libx265Veryfast).build();

      for (var i = 0; i < 20; i++) {
        calibration.fit(measuredAtCost(libx264Load, 1.5, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(libx264Load))).as("libx264").isTrue();
      assertThat(calibration.isValidatedFor(List.of(libx265Load))).as("libx265").isFalse();
      assertThat(calibration.isValidatedFor(List.of(libx264Load, libx265Load)))
          .as("both running")
          .isFalse();
    }

    @Test
    @DisplayName("Should start without validation when the calibration starts from a benchmark")
    void shouldStartWithoutValidationWhenTheCalibrationStartsFromABenchmark() {
      assertThat(tenCoreCalibration().build().isValidatedFor(List.of(libx264FasterLoad().build())))
          .isFalse();
    }
  }
}
