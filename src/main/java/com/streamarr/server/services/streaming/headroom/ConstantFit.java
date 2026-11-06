package com.streamarr.server.services.streaming.headroom;

import com.streamarr.server.domain.streaming.headroom.CostConstant;
import com.streamarr.server.domain.streaming.headroom.CostTerm;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A weighted least-squares fit of the headroom model's constants to measured costs. Each residual
 * counts relative to its measured cost, so a cheap 360p encode weighs as much as a costly 1080p
 * encode. Older measurements fade, and so does the pull of the starting constants, as if the
 * starting constants were measurements taken before the first one. A measurement that prices a
 * constant without a value waits apart until every constant it prices has one.
 */
final class ConstantFit {

  // A held constant at zero adds nothing to any cost, so it stays out of the solved constants.
  private static final double UNKNOWN = 0;
  // CPU seconds per megapixel. Measured constants range from about 0.002 to 0.1.
  private static final double ANCHOR_FLOOR = 0.01;

  private final double forgetting;
  private final List<CostConstant> fitted = new ArrayList<>();
  private final Map<CostConstant, Integer> indexes = new HashMap<>();
  private double[][] normal = new double[0][0];
  private double[] moments = new double[0];
  private final PendingMeasurements pending;
  private final UnaryOperator<Map<CostConstant, Double>> follow;
  private final Set<CostConstant> measured = new HashSet<>();
  private double startingFade = 1;

  /**
   * Follow adjusts the known constants before the waiting measurements are solved against them, so
   * a constant that follows another keeps its followed value there.
   */
  ConstantFit(double forgetting, UnaryOperator<Map<CostConstant, Double>> follow) {
    this.forgetting = forgetting;
    this.follow = follow;
    this.pending = new PendingMeasurements(forgetting);
  }

  /** Adds a measured cost. Valued names the constants that have a value now. */
  void add(List<CostTerm> terms, double measuredCost, Set<CostConstant> valued) {
    startingFade *= forgetting;
    for (var row = 0; row < moments.length; row++) {
      moments[row] *= forgetting;
      for (var column = 0; column < moments.length; column++) {
        normal[row][column] *= forgetting;
      }
    }

    pending.fade();
    if (terms.stream().allMatch(term -> valued.contains(term.constant()))) {
      accumulate(terms, measuredCost, 1);
      return;
    }

    pending.add(terms, measuredCost);
  }

  /**
   * The constants that a measurement in the fit has priced, or that waiting measurements have
   * determined. A waiting measurement does not count for a constant that already had a value.
   */
  Set<CostConstant> measuredConstants() {
    return Set.copyOf(measured);
  }

  private void accumulate(List<CostTerm> terms, double measuredCost, double fade) {
    terms.forEach(
        term -> {
          indexOf(term.constant());
          measured.add(term.constant());
        });
    var weight = fade / (measuredCost * measuredCost);
    for (var term : terms) {
      var row = indexes.get(term.constant());
      moments[row] += weight * term.megapixelsPerSecond() * measuredCost;
      for (var other : terms) {
        normal[row][indexes.get(other.constant())] +=
            weight * term.megapixelsPerSecond() * other.megapixelsPerSecond();
      }
    }
  }

  /**
   * Solves for every constant that a measurement or a starting constant names. A constant with a
   * starting value pays for its relative change from that value, with a weight that fades as the
   * measurements do. A constant with a current value also pays, lightly and without fading, for its
   * relative change from that value, so a constant that the measurements no longer support keeps
   * it.
   *
   * <p>A constant that comes out not positive keeps its current value while the others are solved
   * again. The waiting measurements then give a value to each new constant that they determine,
   * with the known constants held, and join the fit once every constant they price has a value.
   * Empty when the measurements cannot separate the known constants.
   */
  Optional<Map<CostConstant, Double>> solve(
      Map<CostConstant, Double> starting,
      double startingWeight,
      Map<CostConstant, Double> current,
      double currentWeight) {
    var known = solveKnown(starting, startingWeight, current, currentWeight);
    if (known.isEmpty()) {
      return Optional.empty();
    }

    var solved = new HashMap<>(follow.apply(known.get()));
    var determined = pending.solve(solved);
    measured.addAll(determined.keySet());
    solved.putAll(determined);
    pending.release(
        solved.keySet(),
        waiting -> accumulate(waiting.terms(), waiting.measuredCost(), waiting.fade()));
    return Optional.of(solved);
  }

  private Optional<Map<CostConstant, Double>> solveKnown(
      Map<CostConstant, Double> starting,
      double startingWeight,
      Map<CostConstant, Double> current,
      double currentWeight) {
    var unknowns = new ArrayList<CostConstant>(fitted);
    Stream.concat(starting.keySet().stream(), current.keySet().stream())
        .filter(constant -> !indexes.containsKey(constant))
        .distinct()
        .forEach(unknowns::add);
    var size = unknowns.size();
    var matrix = new double[size][size + 1];
    for (var row = 0; row < size; row++) {
      copyMeasuredRow(matrix, unknowns, row);
      var constant = unknowns.get(row);
      var start = starting.get(constant);
      if (start != null) {
        anchor(matrix, row, start, startingWeight * startingFade);
      }

      var now = current.get(constant);
      if (now != null) {
        anchor(matrix, row, now, currentWeight);
      }
    }

    var held = new HeldConstants(unknowns, current);
    while (true) {
      var free = held.free();
      var values = LinearSystem.solve(held.withoutHeld(matrix, free));
      if (values.isEmpty()) {
        return Optional.empty();
      }

      if (!held.holdNotPositive(free, values.get())) {
        return Optional.of(held.solved(free, values.get()));
      }
    }
  }

  /** The constants that a solve holds at their current values while it solves the others again. */
  private static final class HeldConstants {

    private final List<CostConstant> unknowns;
    private final Map<CostConstant, Double> current;
    private final Map<Integer, Double> held = new HashMap<>();

    private HeldConstants(List<CostConstant> unknowns, Map<CostConstant, Double> current) {
      this.unknowns = unknowns;
      this.current = current;
    }

    private int[] free() {
      return IntStream.range(0, unknowns.size())
          .filter(index -> !held.containsKey(index))
          .toArray();
    }

    /** Holds every free constant that came out not positive; false when there is none. */
    private boolean holdNotPositive(int[] free, double[] values) {
      var notPositive =
          IntStream.range(0, free.length)
              .filter(index -> values[index] <= 0 || !Double.isFinite(values[index]))
              .map(index -> free[index])
              .toArray();
      Arrays.stream(notPositive)
          .forEach(index -> held.put(index, current.getOrDefault(unknowns.get(index), UNKNOWN)));
      return notPositive.length > 0;
    }

    /** The free constants' system, with each held constant's cost moved to the measured side. */
    private double[][] withoutHeld(double[][] matrix, int[] free) {
      var size = matrix.length;
      var reduced = new double[free.length][free.length + 1];
      for (var row = 0; row < free.length; row++) {
        for (var column = 0; column < free.length; column++) {
          reduced[row][column] = matrix[free[row]][free[column]];
        }

        var measured = matrix[free[row]][size];
        for (var entry : held.entrySet()) {
          measured -= matrix[free[row]][entry.getKey()] * entry.getValue();
        }

        reduced[row][free.length] = measured;
      }

      return reduced;
    }

    private Map<CostConstant, Double> solved(int[] free, double[] values) {
      var solved = new HashMap<CostConstant, Double>();
      for (var index = 0; index < free.length; index++) {
        solved.put(unknowns.get(free[index]), values[index]);
      }

      held.forEach(
          (index, value) -> {
            if (value > 0) {
              solved.put(unknowns.get(index), value);
            }
          });
      return solved;
    }
  }

  /**
   * Pulls the constant toward the value. The pull counts relative to the value, except that a value
   * below the anchor floor counts as if it were the floor, so a constant near zero can still grow.
   */
  private static void anchor(double[][] matrix, int row, double value, double weight) {
    var scale = Math.max(value, ANCHOR_FLOOR);
    matrix[row][row] += weight / (scale * scale);
    matrix[row][matrix.length] += weight * value / (scale * scale);
  }

  private void copyMeasuredRow(double[][] matrix, List<CostConstant> unknowns, int row) {
    var fittedRow = indexes.get(unknowns.get(row));
    if (fittedRow == null) {
      return;
    }

    for (var column = 0; column < unknowns.size(); column++) {
      var fittedColumn = indexes.get(unknowns.get(column));
      if (fittedColumn != null) {
        matrix[row][column] = normal[fittedRow][fittedColumn];
      }
    }

    matrix[row][unknowns.size()] = moments[fittedRow];
  }

  private int indexOf(CostConstant constant) {
    var index = indexes.get(constant);
    if (index != null) {
      return index;
    }

    var added = fitted.size();
    fitted.add(constant);
    indexes.put(constant, added);
    normal = grown(normal, added + 1);
    var grownMoments = new double[added + 1];
    System.arraycopy(moments, 0, grownMoments, 0, moments.length);
    moments = grownMoments;
    return added;
  }

  private static double[][] grown(double[][] matrix, int size) {
    var grown = new double[size][size];
    for (var row = 0; row < matrix.length; row++) {
      System.arraycopy(matrix[row], 0, grown[row], 0, matrix[row].length);
    }

    return grown;
  }
}
