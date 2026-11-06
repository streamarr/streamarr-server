package com.streamarr.server.services.streaming.headroom;

import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.headroom.CostConstant;
import com.streamarr.server.domain.streaming.headroom.CpuLimit;
import com.streamarr.server.domain.streaming.headroom.EncodeConfiguration;
import com.streamarr.server.domain.streaming.headroom.EncoderAndPreset;
import com.streamarr.server.domain.streaming.headroom.HeadroomConstants;
import com.streamarr.server.domain.streaming.headroom.JobAttemptLoad;
import com.streamarr.server.domain.streaming.headroom.PredictionCheck;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;

/**
 * One worker's calibration (ADR 0044): constants that the server fits continuously from the
 * production times the worker reports, and each encode configuration's latest predictions, which
 * tell whether that configuration is validated. The server predicts each measured speed before it
 * fits that measurement.
 */
public final class WorkerCalibration {

  // A measurement's weight halves after about 70 later measurements.
  private static final double FORGETTING = 0.99;
  // A constant's relative change from its starting value first costs the fit as much as a tenth of
  // a measurement's relative miss, so the starting constants settle only what the measurements
  // cannot separate. That pull fades as the measurements do.
  private static final double STARTING_WEIGHT = 0.1;
  // A constant that no measurement supports any longer keeps its fitted value.
  private static final double CURRENT_WEIGHT = 0.01;

  private final CpuLimit cpuLimit;
  private final HeadroomConstants startingConstants;
  private final ConstantFit fit = new ConstantFit(FORGETTING);
  private final Clock clock;
  private final Map<EncodeConfiguration, PredictionWindow> windows = new HashMap<>();
  private HeadroomConstants constants;

  /**
   * Starts from a benchmark's constants with no predictions, or from a kept calibration's constants
   * with each encode configuration's latest predictions.
   */
  @Builder
  private WorkerCalibration(
      @NonNull CpuLimit cpuLimit,
      @NonNull HeadroomConstants startingConstants,
      @NonNull Clock clock,
      @Singular Map<EncodeConfiguration, List<PredictionCheck>> latestPredictions) {
    this.cpuLimit = cpuLimit;
    this.startingConstants = startingConstants;
    this.constants = startingConstants;
    this.clock = clock;
    latestPredictions.forEach(
        (configuration, checks) -> checks.forEach(check -> windowOf(configuration).keep(check)));
  }

  /**
   * Checks the current constants against the measurement, then fits the measurement. A measurement
   * whose job attempts include one whose cores alone are unknown cannot give a share of the cores,
   * so the fit skips it.
   */
  public synchronized void fit(@NonNull ProductionMeasurement measurement) {
    var running = new ArrayList<JobAttemptLoad>();
    running.add(measurement.load());
    running.addAll(measurement.otherEncodes());
    var shares = sharesOfCores(running);
    if (shares.isEmpty()) {
      return;
    }

    var share = shares.get()[0];
    var measuredSpeed = measurement.productionSpeed();
    var terms = measurement.load().costTerms();
    constants
        .cpuSecondsPerMediaSecond(measurement.load())
        .ifPresent(
            cost -> {
              var load = measurement.load();
              windowOf(load.configuration())
                  .keep(
                      PredictionCheck.builder()
                          .relativeError(share / cost / measuredSpeed - 1)
                          .besideOtherEncodes(!measurement.otherEncodes().isEmpty())
                          .outputToSourceRatio(
                              load.outputPixelRate().pixelsPerSecond()
                                  / load.sourcePixelRate().pixelsPerSecond())
                          .measuredAt(clock.instant())
                          .build());
            });
    fit.add(terms, share / measuredSpeed);
    fit.solve(
            startingConstants.cpuSecondsPerMegapixel(),
            STARTING_WEIGHT,
            constants.cpuSecondsPerMegapixel(),
            CURRENT_WEIGHT)
        .ifPresent(
            solved ->
                constants =
                    HeadroomConstants.builder()
                        .cpuSecondsPerMegapixel(withUnmeasuredPresetsFollowingAutomatic(solved))
                        .coresAlone(startingConstants.coresAlone())
                        .build());
  }

  public synchronized HeadroomConstants constants() {
    return constants;
  }

  /**
   * Each encode configuration's latest predictions that have not yet left its window, oldest first,
   * as a kept calibration stores them.
   */
  public synchronized Map<EncodeConfiguration, List<PredictionCheck>> latestPredictions() {
    var now = clock.instant();
    var copy = new HashMap<EncodeConfiguration, List<PredictionCheck>>();
    windows.forEach((configuration, window) -> copy.put(configuration, window.live(now)));
    return Map.copyOf(copy);
  }

  /** True when the encode configuration of every one of these job attempts is validated. */
  public synchronized boolean isValidatedFor(@NonNull Collection<JobAttemptLoad> loads) {
    var now = clock.instant();
    return loads.stream()
        .map(JobAttemptLoad::configuration)
        .allMatch(configuration -> isValidated(configuration, now));
  }

  /**
   * The margin by which a decision keeps these job attempts' predicted speeds above the speed
   * floor, or empty while one of their encode configurations is not validated. An encode
   * configuration's margin is the largest overprediction among its live predictions and, while it
   * shares the validation of the configuration with its encoder's Automatic preset, that
   * configuration's live predictions (ADR 0044).
   */
  public synchronized OptionalDouble overpredictionMarginFor(
      @NonNull Collection<JobAttemptLoad> loads) {
    var now = clock.instant();
    var configurations = loads.stream().map(JobAttemptLoad::configuration).distinct().toList();
    if (!configurations.stream().allMatch(configuration -> isValidated(configuration, now))) {
      return OptionalDouble.empty();
    }

    return OptionalDouble.of(
        configurations.stream()
            .mapToDouble(configuration -> marginOf(configuration, now))
            .max()
            .orElse(0));
  }

  /**
   * The production speed of each job attempt when exactly these job attempts encode on the worker,
   * or empty while a constant that one of them needs is unknown.
   */
  public synchronized Optional<Map<UUID, Double>> predictProductionSpeeds(
      @NonNull Map<UUID, JobAttemptLoad> running) {
    var attempts = List.copyOf(running.keySet());
    var loads = attempts.stream().map(running::get).toList();
    var shares = sharesOfCores(loads);
    if (shares.isEmpty()) {
      return Optional.empty();
    }

    var speeds = new HashMap<UUID, Double>();
    for (var index = 0; index < attempts.size(); index++) {
      var cost = constants.cpuSecondsPerMediaSecond(loads.get(index));
      if (cost.isEmpty()) {
        return Optional.empty();
      }

      speeds.put(attempts.get(index), shares.get()[index] / cost.getAsDouble());
    }

    return Optional.of(Map.copyOf(speeds));
  }

  /**
   * An encode configuration whose own predictions are too little evidence either way shares the
   * validation that the configuration with its encoder's Automatic preset has from its own
   * predictions (ADR 0044).
   */
  private boolean isValidated(EncodeConfiguration configuration, Instant now) {
    return switch (verdictOf(configuration, now)) {
      case VALIDATED -> true;
      case FAILED -> false;
      case TOO_LITTLE_EVIDENCE ->
          automaticPresetOf(configuration.encoderAndPreset())
              .map(configuration::withEncoderAndPreset)
              .map(automatic -> verdictOf(automatic, now) == PredictionWindow.Verdict.VALIDATED)
              .orElse(false);
    };
  }

  private double marginOf(EncodeConfiguration configuration, Instant now) {
    var own = largestOverprediction(configuration, now);
    if (verdictOf(configuration, now) == PredictionWindow.Verdict.VALIDATED) {
      return own;
    }

    return automaticPresetOf(configuration.encoderAndPreset())
        .map(configuration::withEncoderAndPreset)
        .map(automatic -> Math.max(own, largestOverprediction(automatic, now)))
        .orElse(own);
  }

  private double largestOverprediction(EncodeConfiguration configuration, Instant now) {
    var window = windows.get(configuration);
    if (window == null) {
      return 0;
    }

    var largest =
        window.live(now).stream().mapToDouble(PredictionCheck::relativeError).max().orElse(0);
    return Math.max(0, largest);
  }

  private PredictionWindow.Verdict verdictOf(EncodeConfiguration configuration, Instant now) {
    var window = windows.get(configuration);
    if (window == null) {
      return PredictionWindow.Verdict.TOO_LITTLE_EVIDENCE;
    }

    return window.verdict(now);
  }

  private PredictionWindow windowOf(EncodeConfiguration configuration) {
    return windows.computeIfAbsent(configuration, _ -> new PredictionWindow());
  }

  /**
   * A preset's constant that no measurement has priced keeps the benchmark's ratio to the constant
   * of its encoder's Automatic preset.
   */
  private Map<CostConstant, Double> withUnmeasuredPresetsFollowingAutomatic(
      Map<CostConstant, Double> solved) {
    var starting = startingConstants.cpuSecondsPerMegapixel();
    var adjusted = new HashMap<>(solved);
    for (var constant : starting.keySet()) {
      if (fit.isMeasured(constant)) {
        continue;
      }

      if (!(constant instanceof EncoderAndPreset encoderAndPreset)) {
        continue;
      }

      automaticPresetOf(encoderAndPreset)
          .filter(automatic -> starting.containsKey(automatic) && solved.containsKey(automatic))
          .ifPresent(
              automatic ->
                  adjusted.put(
                      constant,
                      starting.get(constant) * solved.get(automatic) / starting.get(automatic)));
    }

    return adjusted;
  }

  private static Optional<EncoderAndPreset> automaticPresetOf(EncoderAndPreset encoderAndPreset) {
    var automatic =
        new EncoderAndPreset(
            encoderAndPreset.backend(),
            EncoderPreset.automaticFor(encoderAndPreset.backend().codecFamily()));
    if (automatic.equals(encoderAndPreset)) {
      return Optional.empty();
    }

    return Optional.of(automatic);
  }

  /**
   * Each job attempt's share of the CPU limit: an equal part, never more than the job attempt uses
   * alone, with the cores that a capped job attempt leaves divided among the others. Empty while a
   * job attempt's cores alone are unknown.
   */
  private Optional<double[]> sharesOfCores(List<JobAttemptLoad> loads) {
    var ceilings = new double[loads.size()];
    for (var index = 0; index < loads.size(); index++) {
      var ceiling = constants.coresAlone(loads.get(index));
      if (ceiling.isEmpty()) {
        return Optional.empty();
      }

      ceilings[index] = ceiling.getAsDouble();
    }

    var byCeiling =
        IntStream.range(0, ceilings.length)
            .boxed()
            .sorted(Comparator.comparingDouble(index -> ceilings[index]))
            .toList();
    var shares = new double[ceilings.length];
    var cores = cpuLimit.cores();
    var uncapped = ceilings.length;
    for (var index : byCeiling) {
      shares[index] = Math.min(ceilings[index], cores / uncapped);
      cores -= shares[index];
      uncapped--;
    }

    return Optional.of(shares);
  }
}
