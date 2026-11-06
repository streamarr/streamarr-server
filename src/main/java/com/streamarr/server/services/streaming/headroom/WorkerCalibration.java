package com.streamarr.server.services.streaming.headroom;

import com.streamarr.server.domain.streaming.headroom.CostConstant;
import com.streamarr.server.domain.streaming.headroom.CostTerm;
import com.streamarr.server.domain.streaming.headroom.CpuLimit;
import com.streamarr.server.domain.streaming.headroom.HeadroomConstants;
import com.streamarr.server.domain.streaming.headroom.JobAttemptLoad;
import com.streamarr.server.domain.streaming.headroom.PredictionCheck;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;

/**
 * One worker's calibration (ADR 0044): constants that the server fits continuously from the
 * production times the worker reports, and each constant's latest predictions, which tell whether
 * that constant is validated. The server predicts each measured speed before it fits that
 * measurement.
 */
public final class WorkerCalibration {

  static final int VALIDATION_PREDICTIONS = 20;
  private static final int CONCURRENT_PREDICTIONS_REQUIRED = 5;
  private static final double VALIDATED_MEDIAN_ERROR = 0.10;
  // A measurement's weight halves after about 70 later measurements.
  private static final double FORGETTING = 0.99;
  // A constant's relative change from its starting value costs the fit as much as a tenth of a
  // measurement's relative miss, so the starting constants settle only what the measurements
  // cannot separate.
  private static final double STARTING_WEIGHT = 0.1;
  // A constant that no measurement supports any longer keeps its fitted value.
  private static final double CURRENT_WEIGHT = 0.01;

  private final CpuLimit cpuLimit;
  private final HeadroomConstants startingConstants;
  private final ConstantFit fit = new ConstantFit(FORGETTING);
  private final Map<CostConstant, ArrayDeque<PredictionCheck>> latestPredictions = new HashMap<>();
  private HeadroomConstants constants;

  /**
   * Starts from a benchmark's constants with no predictions, or from a kept calibration's constants
   * with each constant's latest predictions.
   */
  @Builder
  private WorkerCalibration(
      @NonNull CpuLimit cpuLimit,
      @NonNull HeadroomConstants startingConstants,
      @Singular Map<CostConstant, List<PredictionCheck>> latestPredictions) {
    this.cpuLimit = cpuLimit;
    this.startingConstants = startingConstants;
    this.constants = startingConstants;
    latestPredictions.forEach(
        (constant, checks) -> checks.forEach(check -> keepPrediction(constant, check)));
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
              var check =
                  new PredictionCheck(
                      share / cost / measuredSpeed - 1, !measurement.otherEncodes().isEmpty());
              terms.forEach(term -> keepPrediction(term.constant(), check));
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
                        .cpuSecondsPerMegapixel(solved)
                        .coresAlone(startingConstants.coresAlone())
                        .build());
  }

  public synchronized HeadroomConstants constants() {
    return constants;
  }

  /** Each constant's latest predictions, oldest first, as a kept calibration stores them. */
  public synchronized Map<CostConstant, List<PredictionCheck>> latestPredictions() {
    var copy = new HashMap<CostConstant, List<PredictionCheck>>();
    latestPredictions.forEach((constant, checks) -> copy.put(constant, List.copyOf(checks)));
    return Map.copyOf(copy);
  }

  /**
   * True when every constant in these job attempts' costs is validated. A constant is validated
   * while the median error of its latest 20 predictions is at most 10 %, and at least 5 of those
   * predictions were of job attempts that ran beside other encodes.
   */
  public synchronized boolean isValidatedFor(@NonNull Collection<JobAttemptLoad> loads) {
    return loads.stream()
        .flatMap(load -> load.costTerms().stream())
        .map(CostTerm::constant)
        .allMatch(this::isValidated);
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

  private boolean isValidated(CostConstant constant) {
    var checks = latestPredictions.get(constant);
    if (checks == null || checks.size() < VALIDATION_PREDICTIONS) {
      return false;
    }

    var concurrent = checks.stream().filter(PredictionCheck::besideOtherEncodes).count();
    if (concurrent < CONCURRENT_PREDICTIONS_REQUIRED) {
      return false;
    }

    var errors =
        checks.stream().mapToDouble(check -> Math.abs(check.relativeError())).sorted().toArray();
    var median = (errors[errors.length / 2 - 1] + errors[errors.length / 2]) / 2;
    return median <= VALIDATED_MEDIAN_ERROR;
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

  private void keepPrediction(CostConstant constant, PredictionCheck check) {
    var checks = latestPredictions.computeIfAbsent(constant, _ -> new ArrayDeque<>());
    checks.addLast(check);
    if (checks.size() > VALIDATION_PREDICTIONS) {
      checks.removeFirst();
    }
  }
}
