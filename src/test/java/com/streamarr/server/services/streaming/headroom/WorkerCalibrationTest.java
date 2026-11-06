package com.streamarr.server.services.streaming.headroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.headroom.CpuLimit;
import com.streamarr.server.domain.streaming.headroom.DecodeBackend;
import com.streamarr.server.domain.streaming.headroom.EncoderAndPreset;
import com.streamarr.server.domain.streaming.headroom.HeadroomConstants;
import com.streamarr.server.domain.streaming.headroom.JobAttemptLoad;
import com.streamarr.server.domain.streaming.headroom.PixelRate;
import com.streamarr.server.domain.streaming.headroom.PredictionCheck;
import com.streamarr.server.domain.streaming.headroom.SourceDecoder;
import com.streamarr.server.domain.streaming.headroom.ToneMapping;
import com.streamarr.server.fakes.MutableClock;
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
        .startingConstants(ACCURATE)
        .clock(new MutableClock());
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
    void
        shouldPredictTheRecordedProductionSpeedsWhenTheConstantsAreFittedFromTheRecordedProductionTimes() {
      var runs = RecordedCapacityRuns.load();
      var calibration =
          WorkerCalibration.builder()
              .cpuLimit(RecordedCapacityRuns.CPU_LIMIT)
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(Map.of())
                      .coresAlone(RecordedCapacityRuns.CORES_ALONE)
                      .build())
              .clock(new MutableClock())
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
                            Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().load())))
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
    void
        shouldPredictBetweenTheBenchmarkAndTheMeasurementWhenOneMeasurementDisagreesWithTheBenchmark() {
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
    void
        shouldKeepFollowingTheActiveConstantsWhenAConstantThatNoStartingValueNamesStopsAppearing() {
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

    @Test
    @DisplayName(
        "Should keep a starting constant's learned value when only another preset's measurements follow for a long time")
    void
        shouldKeepAStartingConstantsLearnedValueWhenOnlyAnotherPresetsMeasurementsFollowForALongTime() {
      var libx264Veryfast = new EncoderAndPreset(LIBX264, EncoderPreset.VERYFAST);
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(
                          Map.of(LIBX264_FASTER, 0.04, libx264Veryfast, 0.02, SOFTWARE_H264, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0, libx264Veryfast, 6.0))
                      .build())
              .build();
      var veryfast = libx264FasterLoad().encoderAndPreset(libx264Veryfast);
      // veryfast truly costs 0.06 CPU s per megapixel, three times what the benchmark measured.
      for (var i = 0; i < 200; i++) {
        calibration.fit(measuredAtCost(veryfast.outputPixelRate(SMALL_OUTPUT).build(), 2.0, 2));
        calibration.fit(measuredAtCost(veryfast.outputPixelRate(LARGE_OUTPUT).build(), 3.5, 2));
      }

      for (var i = 0; i < 2000; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      assertThat(calibration.constants().cpuSecondsPerMegapixel().get(libx264Veryfast))
          .isCloseTo(0.06, withinPercentage(5));
    }
  }

  @Nested
  @DisplayName("New Constant Tests")
  class NewConstantTests {

    @Test
    @DisplayName(
        "Should keep fitting the known constants when a new constant's first measurement implies a negative cost")
    void shouldKeepFittingTheKnownConstantsWhenANewConstantsFirstMeasurementImpliesANegativeCost() {
      var calibration = tenCoreCalibration().build();
      // The known constants already price this load at 1.5, so PQ would cost less than nothing.
      calibration.fit(
          measuredAtCost(libx264FasterLoad().toneMapping(ToneMapping.PQ).build(), 1.4, 1));

      for (var i = 0; i < 300; i++) {
        calibration.fit(rungMeasurement(i, 2, 2));
      }

      assertThat(predictedAloneSpeed(calibration, libx264FasterLoad().build()))
          .isCloseTo(6 / 3.0, within(0.1));
      assertThat(calibration.constants().cpuSecondsPerMegapixel())
          .doesNotContainKey(ToneMapping.PQ);
    }

    @Test
    @DisplayName(
        "Should keep fitting the known constants when a new constant has learned a cost close to zero")
    void shouldKeepFittingTheKnownConstantsWhenANewConstantHasLearnedACostCloseToZero() {
      var calibration = tenCoreCalibration().build();
      // PQ adds 2e-9 CPU s per media s, so its constant comes out near 4e-11.
      calibration.fit(
          measuredAtCost(libx264FasterLoad().toneMapping(ToneMapping.PQ).build(), 1.5 + 2e-9, 1));

      for (var i = 0; i < 300; i++) {
        calibration.fit(rungMeasurement(i, 2, 2));
      }

      assertThat(predictedAloneSpeed(calibration, libx264FasterLoad().build()))
          .isCloseTo(6 / 3.0, within(0.1));
    }

    @Test
    @DisplayName(
        "Should learn a new constant's real cost when it first came out close to zero and later measurements separate it")
    void
        shouldLearnANewConstantsRealCostWhenItFirstCameOutCloseToZeroAndLaterMeasurementsSeparateIt() {
      var calibration = tenCoreCalibration().build();
      var pq = libx264FasterLoad().toneMapping(ToneMapping.PQ);
      calibration.fit(measuredAtCost(pq.build(), 1.5 + 2e-9, 1));

      // PQ truly costs 0.02 CPU s per megapixel: 1.0 more at either rung.
      for (var i = 0; i < 1000; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
        calibration.fit(
            measuredAtCost(
                pq.outputPixelRate(i % 2 == 0 ? SMALL_OUTPUT : LARGE_OUTPUT).build(),
                i % 2 == 0 ? 2.5 : 3.5,
                2));
      }

      assertThat(calibration.constants().cpuSecondsPerMegapixel().get(ToneMapping.PQ))
          .isCloseTo(0.02, withinPercentage(5));
    }

    @Test
    @DisplayName(
        "Should keep fitting the known constants when two new constants have only appeared together")
    void shouldKeepFittingTheKnownConstantsWhenTwoNewConstantsHaveOnlyAppearedTogether() {
      var calibration = tenCoreCalibration().build();
      var softwareHevc = new SourceDecoder("hevc", DecodeBackend.SOFTWARE);
      calibration.fit(
          measuredAtCost(
              libx264FasterLoad().sourceDecoder(softwareHevc).toneMapping(ToneMapping.PQ).build(),
              3.0,
              1));

      for (var i = 0; i < 300; i++) {
        calibration.fit(rungMeasurement(i, 2, 2));
      }

      assertThat(predictedAloneSpeed(calibration, libx264FasterLoad().build()))
          .isCloseTo(6 / 3.0, within(0.1));
      assertThat(calibration.constants().cpuSecondsPerMegapixel())
          .doesNotContainKeys(softwareHevc, ToneMapping.PQ);
    }
  }

  @Nested
  @DisplayName("Ambiguous Measurement Tests")
  class AmbiguousMeasurementTests {

    @Test
    @DisplayName(
        "Should keep the known constants accurate when measurements that two new constants cannot separate keep arriving")
    void
        shouldKeepTheKnownConstantsAccurateWhenMeasurementsThatTwoNewConstantsCannotSeparateKeepArriving() {
      var calibration = tenCoreCalibration().build();
      var hevcPq =
          libx264FasterLoad()
              .sourceDecoder(new SourceDecoder("hevc", DecodeBackend.SOFTWARE))
              .toneMapping(ToneMapping.PQ)
              .build();

      for (var i = 0; i < 200; i++) {
        calibration.fit(measuredAtCost(hevcPq, 3.0, 2));
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      assertThat(calibration.constants().cpuSecondsPerMegapixel().get(LIBX264_FASTER))
          .isCloseTo(0.04, withinPercentage(2));
      assertThat(predictedAloneSpeed(calibration, libx264FasterLoad().build()))
          .isCloseTo(6 / 1.5, within(0.1));
    }
  }

  @Nested
  @DisplayName("Waiting Measurement Tests")
  class WaitingMeasurementTests {

    private final SourceDecoder softwareHevc = new SourceDecoder("hevc", DecodeBackend.SOFTWARE);

    @Test
    @DisplayName(
        "Should keep another preset following the Automatic preset when its only measurement also prices constants without values")
    void
        shouldKeepAnotherPresetFollowingTheAutomaticPresetWhenItsOnlyMeasurementAlsoPricesConstantsWithoutValues() {
      var libx264Veryfast = new EncoderAndPreset(LIBX264, EncoderPreset.VERYFAST);
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(
                          Map.of(LIBX264_FASTER, 0.04, libx264Veryfast, 0.02, SOFTWARE_H264, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0, libx264Veryfast, 6.0))
                      .build())
              .build();
      for (var i = 0; i < 200; i++) {
        calibration.fit(rungMeasurement(i, 2, 2));
      }

      calibration.fit(
          measuredAtCost(
              libx264FasterLoad()
                  .encoderAndPreset(libx264Veryfast)
                  .sourceDecoder(softwareHevc)
                  .toneMapping(ToneMapping.PQ)
                  .build(),
              3.0,
              2));
      for (var i = 0; i < 50; i++) {
        calibration.fit(rungMeasurement(i, 2, 2));
      }

      var constants = calibration.constants().cpuSecondsPerMegapixel();
      assertThat(constants.get(libx264Veryfast) / constants.get(LIBX264_FASTER))
          .isCloseTo(0.5, within(1e-6));
    }

    @Test
    @DisplayName(
        "Should price a new constant against another preset's followed constant when one measurement prices both")
    void
        shouldPriceANewConstantAgainstAnotherPresetsFollowedConstantWhenOneMeasurementPricesBoth() {
      var libx264Veryfast = new EncoderAndPreset(LIBX264, EncoderPreset.VERYFAST);
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(
                          Map.of(LIBX264_FASTER, 0.04, libx264Veryfast, 0.02, SOFTWARE_H264, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0, libx264Veryfast, 6.0))
                      .build())
              .build();
      for (var i = 0; i < 300; i++) {
        calibration.fit(rungMeasurement(i, 2, 2));
      }

      // veryfast follows faster to 0.04, so this PQ attempt costs 0.04 x 25 + 0.02 x 50 + 0.02 x
      // 50.
      calibration.fit(
          measuredAtCost(
              libx264FasterLoad()
                  .encoderAndPreset(libx264Veryfast)
                  .toneMapping(ToneMapping.PQ)
                  .build(),
              3.0,
              2));

      var constants = calibration.constants().cpuSecondsPerMegapixel();
      assertThat(constants.get(libx264Veryfast)).isCloseTo(0.04, withinPercentage(2));
      assertThat(constants.get(ToneMapping.PQ)).isCloseTo(0.02, withinPercentage(5));
    }

    @Test
    @DisplayName(
        "Should leave a new constant unknown when every measurement that prices it also prices a new constant that comes out negative")
    void
        shouldLeaveANewConstantUnknownWhenEveryMeasurementThatPricesItAlsoPricesANewConstantThatComesOutNegative() {
      var libx264Medium = new EncoderAndPreset(LIBX264, EncoderPreset.MEDIUM);
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(Map.of(LIBX264_FASTER, 0.04, SOFTWARE_H264, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0, libx264Medium, 6.0))
                      .build())
              .build();
      var medium = libx264FasterLoad().encoderAndPreset(libx264Medium).sourceDecoder(softwareHevc);

      // Doubling the output pixel rate triples the cost, so the HEVC decode constant would be
      // negative.
      calibration.fit(measuredAtCost(medium.outputPixelRate(SMALL_OUTPUT).build(), 1.0, 1));
      calibration.fit(measuredAtCost(medium.outputPixelRate(LARGE_OUTPUT).build(), 3.0, 1));

      assertThat(calibration.constants().cpuSecondsPerMegapixel())
          .doesNotContainKeys(libx264Medium, softwareHevc);
    }

    @Test
    @DisplayName(
        "Should give a new constant its value when other waiting measurements cannot separate two other new constants")
    void
        shouldGiveANewConstantItsValueWhenOtherWaitingMeasurementsCannotSeparateTwoOtherNewConstants() {
      var calibration = tenCoreCalibration().build();
      var softwareVp9 = new SourceDecoder("vp9", DecodeBackend.SOFTWARE);
      calibration.fit(
          measuredAtCost(
              libx264FasterLoad().sourceDecoder(softwareHevc).toneMapping(ToneMapping.PQ).build(),
              3.0,
              1));

      // 0.04 x 25 + 0.02 x 50 = 2.0 CPU s per media s.
      calibration.fit(
          measuredAtCost(libx264FasterLoad().sourceDecoder(softwareVp9).build(), 2.0, 1));

      assertThat(calibration.constants().cpuSecondsPerMegapixel())
          .doesNotContainKeys(softwareHevc, ToneMapping.PQ)
          .hasEntrySatisfying(
              softwareVp9, constant -> assertThat(constant).isCloseTo(0.02, within(1e-3)));
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
    @DisplayName(
        "Should leave the decode constant unknown when the measurements imply a negative decode constant")
    void shouldLeaveTheDecodeConstantUnknownWhenTheMeasurementsImplyANegativeDecodeConstant() {
      var calibration = calibrationWithoutBenchmark();
      var small = libx264FasterLoad().build();
      var large = libx264FasterLoad().outputPixelRate(LARGE_OUTPUT).build();

      // Doubling the output pixel rate triples the cost, so the decode constant would be negative.
      for (var i = 0; i < 10; i++) {
        calibration.fit(measuredAtCost(small, 1.0, 2));
        calibration.fit(measuredAtCost(large, 3.0, 2));
      }

      assertThat(calibration.constants().cpuSecondsPerMegapixel()).doesNotContainKey(SOFTWARE_H264);
      assertThat(calibration.predictProductionSpeeds(Map.of(UUID.randomUUID(), small))).isEmpty();
    }
  }

  /**
   * The index-th of measurements that alternate between the small rung (output half the source's
   * pixel rate, true cost 1.5 x costFactor) and the large rung (output equal to it, 2.5 x
   * costFactor).
   */
  private static ProductionMeasurement rungMeasurement(
      int index, double costFactor, int encodesRunning) {
    if (index % 2 == 0) {
      return measuredAtCost(libx264FasterLoad().build(), 1.5 * costFactor, encodesRunning);
    }

    return measuredAtCost(
        libx264FasterLoad().outputPixelRate(LARGE_OUTPUT).build(),
        2.5 * costFactor,
        encodesRunning);
  }

  @Nested
  @DisplayName("Validation Tests")
  class ValidationTests {

    private final JobAttemptLoad load = libx264FasterLoad().build();

    @Test
    @DisplayName(
        "Should count the encode configuration as validated when its constants predict twenty measured speeds of two rungs that include concurrent encodes")
    void
        shouldCountTheEncodeConfigurationAsValidatedWhenItsConstantsPredictTwentyMeasuredSpeedsOfTwoRungsThatIncludeConcurrentEncodes() {
      var calibration = tenCoreCalibration().build();
      for (var i = 0; i < 19; i++) {
        calibration.fit(rungMeasurement(i, 1, 1 + i % 3));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).as("after 19 measurements").isFalse();

      calibration.fit(rungMeasurement(19, 1, 2));

      assertThat(calibration.isValidatedFor(List.of(load))).as("after 20 measurements").isTrue();
    }

    @Test
    @DisplayName(
        "Should leave the encode configuration unvalidated when no measured job attempt ran beside another encode")
    void
        shouldLeaveTheEncodeConfigurationUnvalidatedWhenNoMeasuredJobAttemptRanBesideAnotherEncode() {
      var calibration = tenCoreCalibration().build();

      for (var i = 0; i < 40; i++) {
        calibration.fit(rungMeasurement(i, 1, 1));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).isFalse();
    }

    @Test
    @DisplayName(
        "Should leave the encode configuration unvalidated when every measurement priced the same ratio of output to source")
    void
        shouldLeaveTheEncodeConfigurationUnvalidatedWhenEveryMeasurementPricedTheSameRatioOfOutputToSource() {
      var calibration = tenCoreCalibration().build();

      for (var i = 0; i < 40; i++) {
        calibration.fit(measuredAtCost(load, 1.5, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).isFalse();
    }

    @Test
    @DisplayName(
        "Should leave the encode configuration unvalidated when the measured speeds of one load scatter beyond any prediction")
    void
        shouldLeaveTheEncodeConfigurationUnvalidatedWhenTheMeasuredSpeedsOfOneLoadScatterBeyondAnyPrediction() {
      var calibration = tenCoreCalibration().build();

      for (var i = 0; i < 100; i++) {
        calibration.fit(rungMeasurement(i, i % 4 < 2 ? 1 : 2, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).isFalse();
    }

    @Test
    @DisplayName(
        "Should validate the encode configuration when the continuous fit has learned the cost that the benchmark missed")
    void
        shouldValidateTheEncodeConfigurationWhenTheContinuousFitHasLearnedTheCostThatTheBenchmarkMissed() {
      var calibration = tenCoreCalibration().build();

      for (var i = 0; i < 200; i++) {
        calibration.fit(rungMeasurement(i, 2, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).isTrue();
    }

    @Test
    @DisplayName("Should lose the validation when the constants start missing the measured speeds")
    void shouldLoseTheValidationWhenTheConstantsStartMissingTheMeasuredSpeeds() {
      var calibration = tenCoreCalibration().build();
      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load)))
          .as("while the constants predict")
          .isTrue();

      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 2, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load)))
          .as("after the content turned costlier")
          .isFalse();
    }

    @Test
    @DisplayName(
        "Should keep the validation when a calibration starts from a kept calibration's latest predictions")
    void shouldKeepTheValidationWhenACalibrationStartsFromAKeptCalibrationsLatestPredictions() {
      var kept = tenCoreCalibration().build();
      for (var i = 0; i < 20; i++) {
        kept.fit(rungMeasurement(i, 1, 2));
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

      calibration.fit(measuredAtCost(load, 3.0, 1));

      // The benchmark predicted 6 / 1.5 = 4.0, and the worker measured 6 / 3.0 = 2.0.
      assertThat(calibration.latestPredictions().get(load.configuration()))
          .singleElement()
          .satisfies(
              check -> {
                assertThat(check.relativeError()).isCloseTo(1.0, within(1e-6));
                assertThat(check.besideOtherEncodes()).isFalse();
                assertThat(check.outputToSourceRatio()).isCloseTo(0.5, within(1e-12));
              });
    }

    @Test
    @DisplayName(
        "Should keep only an encode configuration's latest 20 predictions when a kept calibration brings more")
    void shouldKeepOnlyAnEncodeConfigurationsLatest20PredictionsWhenAKeptCalibrationBringsMore() {
      var clock = new MutableClock();
      var kept = new ArrayList<PredictionCheck>();
      for (var i = 0; i < 25; i++) {
        kept.add(
            PredictionCheck.builder()
                .relativeError(i / 100.0)
                .besideOtherEncodes(true)
                .outputPixelRate(SMALL_OUTPUT)
                .sourcePixelRate(SOURCE)
                .measuredCpuSecondsPerMediaSecond(1.5)
                .measuredAt(clock.instant())
                .build());
      }

      var predictions =
          tenCoreCalibration()
              .clock(clock)
              .latestPrediction(load.configuration(), kept)
              .build()
              .latestPredictions()
              .get(load.configuration());

      assertThat(predictions).hasSize(20);
      assertThat(predictions.getFirst().relativeError()).isCloseTo(0.05, within(1e-12));
      assertThat(predictions.getLast().relativeError()).isCloseTo(0.24, within(1e-12));
    }

    @Test
    @DisplayName(
        "Should leave another encoder's encode configuration unvalidated when only libx264 measurements arrived")
    void
        shouldLeaveAnotherEncodersEncodeConfigurationUnvalidatedWhenOnlyLibx264MeasurementsArrived() {
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
      var libx265Load = libx264FasterLoad().encoderAndPreset(libx265Veryfast).build();

      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).as("libx264").isTrue();
      assertThat(calibration.isValidatedFor(List.of(libx265Load))).as("libx265").isFalse();
      assertThat(calibration.isValidatedFor(List.of(load, libx265Load)))
          .as("both running")
          .isFalse();
    }

    @Test
    @DisplayName(
        "Should leave an encode configuration unvalidated when only other configurations priced its constants at a second ratio")
    void
        shouldLeaveAnEncodeConfigurationUnvalidatedWhenOnlyOtherConfigurationsPricedItsConstantsAtASecondRatio() {
      var libx265Veryfast =
          new EncoderAndPreset(
              EncoderBackend.builder()
                  .codecFamily("hevc")
                  .encoder("libx265")
                  .kind(EncoderBackend.Kind.SOFTWARE)
                  .build(),
              EncoderPreset.VERYFAST);
      var softwareHevc = new SourceDecoder("hevc", DecodeBackend.SOFTWARE);
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(
                          Map.of(
                              LIBX264_FASTER, 0.04,
                              libx265Veryfast, 0.12,
                              SOFTWARE_H264, 0.01,
                              softwareHevc, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0, libx265Veryfast, 6.0))
                      .build())
              .build();
      var x264FromH264 = libx264FasterLoad().outputPixelRate(SMALL_OUTPUT).build();
      var x264FromHevc =
          libx264FasterLoad().outputPixelRate(LARGE_OUTPUT).sourceDecoder(softwareHevc).build();
      var x265FromH264 =
          libx264FasterLoad()
              .encoderAndPreset(libx265Veryfast)
              .outputPixelRate(LARGE_OUTPUT)
              .build();
      var x265FromHevc =
          libx264FasterLoad()
              .encoderAndPreset(libx265Veryfast)
              .outputPixelRate(SMALL_OUTPUT)
              .sourceDecoder(softwareHevc)
              .build();

      // Each constant sees output-to-source ratios of 0.5 and 1, but each configuration only one.
      for (var i = 0; i < 10; i++) {
        calibration.fit(measuredAtCost(x264FromH264, 1.5, 2));
        calibration.fit(measuredAtCost(x264FromHevc, 2.5, 2));
        calibration.fit(measuredAtCost(x265FromH264, 6.5, 2));
        calibration.fit(measuredAtCost(x265FromHevc, 3.5, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(x264FromH264))).isFalse();
    }

    @Test
    @DisplayName("Should lose the validation when its predictions are seven days old")
    void shouldLoseTheValidationWhenItsPredictionsAreSevenDaysOld() {
      var clock = new MutableClock();
      var calibration = tenCoreCalibration().clock(clock).build();
      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).as("on the day").isTrue();

      clock.advance(Duration.ofDays(7));

      assertThat(calibration.isValidatedFor(List.of(load))).as("seven days later").isFalse();
      assertThat(calibration.latestPredictions().get(load.configuration())).isEmpty();
    }

    @Test
    @DisplayName(
        "Should lose the validation when another configuration's measurements move a constant that both configurations share")
    void
        shouldLoseTheValidationWhenAnotherConfigurationsMeasurementsMoveAConstantThatBothConfigurationsShare() {
      var softwareHevc = new SourceDecoder("hevc", DecodeBackend.SOFTWARE);
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(
                          Map.of(LIBX264_FASTER, 0.04, SOFTWARE_H264, 0.01, softwareHevc, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0))
                      .build())
              .build();
      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).as("before").isTrue();

      // From HEVC sources, faster costs 0.016 CPU s per megapixel: 0.4 + 0.5 and 0.8 + 0.5.
      var fromHevc = libx264FasterLoad().sourceDecoder(softwareHevc);
      for (var i = 0; i < 150; i++) {
        calibration.fit(measuredAtCost(fromHevc.outputPixelRate(SMALL_OUTPUT).build(), 0.9, 2));
        calibration.fit(measuredAtCost(fromHevc.outputPixelRate(LARGE_OUTPUT).build(), 1.3, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(load))).as("after").isFalse();
    }

    @Test
    @DisplayName("Should start without validation when the calibration starts from a benchmark")
    void shouldStartWithoutValidationWhenTheCalibrationStartsFromABenchmark() {
      assertThat(tenCoreCalibration().build().isValidatedFor(List.of(load))).isFalse();
    }
  }

  @Nested
  @DisplayName("Unmeasured Preset Tests")
  class UnmeasuredPresetTests {

    private static final EncoderAndPreset LIBX264_VERYFAST =
        new EncoderAndPreset(LIBX264, EncoderPreset.VERYFAST);

    private final JobAttemptLoad veryfast =
        libx264FasterLoad().encoderAndPreset(LIBX264_VERYFAST).build();

    private WorkerCalibration benchmarkedAtFasterAndVeryfast() {
      return benchmarkedAtFasterAndVeryfastBuilder().build();
    }

    private WorkerCalibration.WorkerCalibrationBuilder benchmarkedAtFasterAndVeryfastBuilder() {
      return tenCoreCalibration()
          .startingConstants(
              HeadroomConstants.builder()
                  .cpuSecondsPerMegapixel(
                      Map.of(LIBX264_FASTER, 0.04, LIBX264_VERYFAST, 0.02, SOFTWARE_H264, 0.01))
                  .coresAlone(Map.of(LIBX264_FASTER, 6.0, LIBX264_VERYFAST, 6.0))
                  .build());
    }

    @Test
    @DisplayName(
        "Should count another preset as validated while the Automatic preset is validated and the other preset has no predictions of its own")
    void
        shouldCountAnotherPresetAsValidatedWhileTheAutomaticPresetIsValidatedAndTheOtherPresetHasNoPredictionsOfItsOwn() {
      var calibration = benchmarkedAtFasterAndVeryfast();

      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(veryfast))).isTrue();
    }

    @Test
    @DisplayName(
        "Should move another preset's constant with the Automatic preset's constant at the benchmark's ratio when no measurement priced the other preset")
    void
        shouldMoveAnotherPresetsConstantWithTheAutomaticPresetsConstantAtTheBenchmarksRatioWhenNoMeasurementPricedTheOtherPreset() {
      var calibration = benchmarkedAtFasterAndVeryfast();

      for (var i = 0; i < 300; i++) {
        calibration.fit(rungMeasurement(i, 2, 2));
      }

      var constants = calibration.constants().cpuSecondsPerMegapixel();
      assertThat(constants.get(LIBX264_VERYFAST) / constants.get(LIBX264_FASTER))
          .isCloseTo(0.02 / 0.04, within(1e-9));
    }

    @Test
    @DisplayName(
        "Should keep the Automatic preset's validation for another preset when that preset's own accurate predictions cover one ratio")
    void
        shouldKeepTheAutomaticPresetsValidationForAnotherPresetWhenThatPresetsOwnAccuratePredictionsCoverOneRatio() {
      var calibration = benchmarkedAtFasterAndVeryfast();
      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      // One running variant keeps one ratio: 0.02 x 25 + 0.01 x 50 = 1.0 CPU s per media s.
      for (var i = 0; i < 20; i++) {
        calibration.fit(measuredAtCost(veryfast, 1.0, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(veryfast))).isTrue();
    }

    @Test
    @DisplayName(
        "Should leave another preset unvalidated when five or more of its own predictions miss")
    void shouldLeaveAnotherPresetUnvalidatedWhenFiveOrMoreOfItsOwnPredictionsMiss() {
      var calibration = benchmarkedAtFasterAndVeryfast();
      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      for (var i = 0; i < 6; i++) {
        calibration.fit(measuredAtCost(veryfast, i % 2 == 0 ? 1.0 : 3.0, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(veryfast))).isFalse();
    }

    @Test
    @DisplayName(
        "Should share the Automatic preset's validation with a failed preset again when its failed predictions are seven days old")
    void
        shouldShareTheAutomaticPresetsValidationWithAFailedPresetAgainWhenItsFailedPredictionsAreSevenDaysOld() {
      var clock = new MutableClock();
      var calibration = benchmarkedAtFasterAndVeryfastBuilder().clock(clock).build();
      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      for (var i = 0; i < 6; i++) {
        calibration.fit(measuredAtCost(veryfast, i % 2 == 0 ? 1.0 : 3.0, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(veryfast))).as("after the misses").isFalse();

      clock.advance(Duration.ofDays(7));
      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(veryfast))).as("seven days later").isTrue();
    }

    @Test
    @DisplayName(
        "Should keep a measured preset's constant when a kept calibration restarts and only the Automatic preset's measurements follow")
    void
        shouldKeepAMeasuredPresetsConstantWhenAKeptCalibrationRestartsAndOnlyTheAutomaticPresetsMeasurementsFollow() {
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(
                          Map.of(LIBX264_FASTER, 0.04, LIBX264_VERYFAST, 0.06, SOFTWARE_H264, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0, LIBX264_VERYFAST, 6.0))
                      .build())
              .measuredConstant(LIBX264_VERYFAST)
              .build();

      for (var i = 0; i < 300; i++) {
        calibration.fit(rungMeasurement(i, 0.5, 2));
      }

      assertThat(calibration.constants().cpuSecondsPerMegapixel().get(LIBX264_VERYFAST))
          .isCloseTo(0.06, withinPercentage(5));
      assertThat(calibration.measuredConstants()).contains(LIBX264_VERYFAST, LIBX264_FASTER);
    }

    @Test
    @DisplayName(
        "Should leave another preset unvalidated when the constants cannot price it, although the Automatic preset is validated")
    void
        shouldLeaveAnotherPresetUnvalidatedWhenTheConstantsCannotPriceItAlthoughTheAutomaticPresetIsValidated() {
      var calibration = tenCoreCalibration().build();
      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(veryfast))).isFalse();
    }

    @Test
    @DisplayName(
        "Should leave another preset unvalidated when the current constants miss its few live measurements, although the Automatic preset is validated")
    void
        shouldLeaveAnotherPresetUnvalidatedWhenTheCurrentConstantsMissItsFewLiveMeasurementsAlthoughTheAutomaticPresetIsValidated() {
      var softwareHevc = new SourceDecoder("hevc", DecodeBackend.SOFTWARE);
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(
                          Map.of(
                              LIBX264_FASTER, 0.04,
                              LIBX264_VERYFAST, 0.02,
                              SOFTWARE_H264, 0.01,
                              softwareHevc, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0, LIBX264_VERYFAST, 6.0))
                      .build())
              .build();
      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      for (var i = 0; i < 2; i++) {
        calibration.fit(measuredAtCost(veryfast, 1.0, 2));
      }

      // From HEVC sources, veryfast costs 0.002 CPU s per megapixel: 0.05 + 0.5 and 0.1 + 0.5.
      var fromHevc =
          libx264FasterLoad().encoderAndPreset(LIBX264_VERYFAST).sourceDecoder(softwareHevc);
      for (var i = 0; i < 100; i++) {
        calibration.fit(measuredAtCost(fromHevc.outputPixelRate(SMALL_OUTPUT).build(), 0.55, 2));
        calibration.fit(measuredAtCost(fromHevc.outputPixelRate(LARGE_OUTPUT).build(), 0.6, 2));
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(veryfast))).isFalse();
    }

    @Test
    @DisplayName("Should let another preset's own predictions decide once it has twenty of them")
    void shouldLetAnotherPresetsOwnPredictionsDecideOnceItHasTwentyOfThem() {
      var calibration = benchmarkedAtFasterAndVeryfast();
      for (var i = 0; i < 20; i++) {
        calibration.fit(rungMeasurement(i, 1, 2));
      }

      for (var i = 0; i < 40; i++) {
        calibration.fit(measuredAtCost(veryfast, i % 2 == 0 ? 1.0 : 2.0, 2));
      }

      assertThat(calibration.isValidatedFor(List.of(veryfast))).isFalse();
    }
  }

  @Nested
  @DisplayName("Overprediction Margin Tests")
  class OverpredictionMarginTests {

    private static final EncoderAndPreset LIBX264_VERYFAST =
        new EncoderAndPreset(LIBX264, EncoderPreset.VERYFAST);

    private final MutableClock clock = new MutableClock();
    private final JobAttemptLoad faster = libx264FasterLoad().build();
    private final JobAttemptLoad veryfast =
        libx264FasterLoad().encoderAndPreset(LIBX264_VERYFAST).build();

    /**
     * Twenty concurrent predictions that alternate between the small and large rungs, whose errors
     * cycle through these, and whose measured costs the current constants predict exactly.
     */
    private List<PredictionCheck> twentyPredictionsMissingBy(double... relativeErrors) {
      var checks = new ArrayList<PredictionCheck>();
      for (var i = 0; i < 20; i++) {
        var check = smallRungPredictionMissingBy(relativeErrors[i % relativeErrors.length]);
        if (i % 2 == 1) {
          check =
              check.toBuilder()
                  .outputPixelRate(LARGE_OUTPUT)
                  .measuredCpuSecondsPerMediaSecond(2.5)
                  .build();
        }

        checks.add(check);
      }

      return checks;
    }

    private PredictionCheck smallRungPredictionMissingBy(double relativeError) {
      return PredictionCheck.builder()
          .relativeError(relativeError)
          .besideOtherEncodes(true)
          .outputPixelRate(SMALL_OUTPUT)
          .sourcePixelRate(SOURCE)
          .measuredCpuSecondsPerMediaSecond(1.5)
          .measuredAt(clock.instant())
          .build();
    }

    @Test
    @DisplayName(
        "Should give the largest overprediction among the live predictions as the margin when the encode configuration is validated")
    void
        shouldGiveTheLargestOverpredictionAmongTheLivePredictionsAsTheMarginWhenTheEncodeConfigurationIsValidated() {
      var calibration =
          tenCoreCalibration()
              .clock(clock)
              .latestPrediction(
                  faster.configuration(), twentyPredictionsMissingBy(0.02, -0.04, 0.08, -0.01))
              .build();

      assertThat(calibration.overpredictionMarginFor(List.of(faster)))
          .hasValueCloseTo(0.08, within(1e-12));
    }

    @Test
    @DisplayName("Should give a margin of zero when no live prediction overpredicted")
    void shouldGiveAMarginOfZeroWhenNoLivePredictionOverpredicted() {
      var calibration =
          tenCoreCalibration()
              .clock(clock)
              .latestPrediction(faster.configuration(), twentyPredictionsMissingBy(-0.02, -0.06))
              .build();

      assertThat(calibration.overpredictionMarginFor(List.of(faster)))
          .hasValueCloseTo(0.0, within(1e-12));
    }

    @Test
    @DisplayName("Should give no margin when an encode configuration is not validated")
    void shouldGiveNoMarginWhenAnEncodeConfigurationIsNotValidated() {
      var calibration =
          tenCoreCalibration()
              .clock(clock)
              .latestPrediction(faster.configuration(), twentyPredictionsMissingBy(0.02, -0.04))
              .build();
      var otherSource =
          libx264FasterLoad()
              .sourceDecoder(new SourceDecoder("hevc", DecodeBackend.SOFTWARE))
              .build();

      assertThat(calibration.overpredictionMarginFor(List.of(faster, otherSource))).isEmpty();
    }

    @Test
    @DisplayName(
        "Should take the Automatic preset's predictions into the margin when another preset shares its validation")
    void
        shouldTakeTheAutomaticPresetsPredictionsIntoTheMarginWhenAnotherPresetSharesItsValidation() {
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(
                          Map.of(LIBX264_FASTER, 0.04, LIBX264_VERYFAST, 0.02, SOFTWARE_H264, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0, LIBX264_VERYFAST, 6.0))
                      .build())
              .clock(clock)
              .latestPrediction(faster.configuration(), twentyPredictionsMissingBy(0.09, -0.03))
              .latestPrediction(
                  veryfast.configuration(),
                  List.of(
                      smallRungPredictionMissingBy(0.02).toBuilder()
                          .measuredCpuSecondsPerMediaSecond(1.0)
                          .build(),
                      smallRungPredictionMissingBy(-0.01).toBuilder()
                          .measuredCpuSecondsPerMediaSecond(1.0)
                          .build()))
              .build();

      assertThat(calibration.overpredictionMarginFor(List.of(veryfast)))
          .hasValueCloseTo(0.09, within(1e-12));
    }

    @Test
    @DisplayName(
        "Should give the largest margin among the encode configurations when several variants run")
    void shouldGiveTheLargestMarginAmongTheEncodeConfigurationsWhenSeveralVariantsRun() {
      var softwareHevc = new SourceDecoder("hevc", DecodeBackend.SOFTWARE);
      var fromHevc = libx264FasterLoad().sourceDecoder(softwareHevc).build();
      var calibration =
          tenCoreCalibration()
              .startingConstants(
                  HeadroomConstants.builder()
                      .cpuSecondsPerMegapixel(
                          Map.of(LIBX264_FASTER, 0.04, SOFTWARE_H264, 0.01, softwareHevc, 0.01))
                      .coresAlone(Map.of(LIBX264_FASTER, 6.0))
                      .build())
              .clock(clock)
              .latestPrediction(faster.configuration(), twentyPredictionsMissingBy(0.03, -0.03))
              .latestPrediction(fromHevc.configuration(), twentyPredictionsMissingBy(0.07, -0.02))
              .build();

      assertThat(calibration.overpredictionMarginFor(List.of(faster, fromHevc)))
          .hasValueCloseTo(0.07, within(1e-12));
    }
  }
}
