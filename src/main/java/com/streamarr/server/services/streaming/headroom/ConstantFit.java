package com.streamarr.server.services.streaming.headroom;

import com.streamarr.server.domain.streaming.headroom.CostConstant;
import com.streamarr.server.domain.streaming.headroom.CostTerm;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * A weighted least-squares fit of the headroom model's constants to measured costs. Each residual
 * counts relative to its measured cost, so a cheap 360p encode weighs as much as a costly 1080p
 * encode. Older measurements fade, and so does the pull of the starting constants, as if the
 * starting constants were measurements taken before the first one.
 */
final class ConstantFit {

  // A pivot this small against the matrix's largest entry means the measurements do not separate
  // the constants.
  private static final double SINGULAR_PIVOT = 1e-9;
  // A held constant at zero adds nothing to any cost, so it stays out of the solved constants.
  private static final double UNKNOWN = 0;

  private final double forgetting;
  private final List<CostConstant> fitted = new ArrayList<>();
  private final Map<CostConstant, Integer> indexes = new HashMap<>();
  private double[][] normal = new double[0][0];
  private double[] moments = new double[0];
  private double startingFade = 1;

  ConstantFit(double forgetting) {
    this.forgetting = forgetting;
  }

  void add(List<CostTerm> terms, double measuredCost) {
    terms.forEach(term -> indexOf(term.constant()));
    startingFade *= forgetting;
    for (var row = 0; row < moments.length; row++) {
      moments[row] *= forgetting;
      for (var column = 0; column < moments.length; column++) {
        normal[row][column] *= forgetting;
      }
    }

    var weight = 1 / (measuredCost * measuredCost);
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
   * again. A constant without a current value then stays unknown, and so does every constant
   * without a value when the measurements cannot separate the constants, so that a new constant
   * never stops the fit of the known ones. Empty when the measurements cannot separate the known
   * constants.
   */
  Optional<Map<CostConstant, Double>> solve(
      Map<CostConstant, Double> starting,
      double startingWeight,
      Map<CostConstant, Double> current,
      double currentWeight) {
    var unknowns = new ArrayList<CostConstant>(fitted);
    starting.keySet().stream()
        .filter(constant -> !indexes.containsKey(constant))
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
      var values = eliminate(held.withoutHeld(matrix, free));
      if (values.isEmpty() && !held.holdValueless(free)) {
        return Optional.empty();
      }

      if (values.isPresent() && !held.holdNotPositive(free, values.get())) {
        return Optional.of(held.solved(free, values.get()));
      }
    }
  }

  /**
   * The constants that a solve holds at a fixed value while it solves the others again: a known
   * constant at its current value, and a constant without one at zero, which leaves it unknown.
   */
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

    /** Holds every free constant without a current value; false when there is none to hold. */
    private boolean holdValueless(int[] free) {
      var valueless =
          Arrays.stream(free).filter(index -> !current.containsKey(unknowns.get(index))).toArray();
      Arrays.stream(valueless).forEach(index -> held.put(index, UNKNOWN));
      return valueless.length > 0;
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

  private static void anchor(double[][] matrix, int row, double value, double weight) {
    matrix[row][row] += weight / (value * value);
    matrix[row][matrix.length] += weight / value;
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

  /** True once a measurement has priced the constant. */
  boolean isMeasured(CostConstant constant) {
    return indexes.containsKey(constant);
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

  private static Optional<double[]> eliminate(double[][] matrix) {
    var size = matrix.length;
    var scale = largestEntry(matrix);
    for (var pivot = 0; pivot < size; pivot++) {
      var best = pivot;
      for (var row = pivot + 1; row < size; row++) {
        if (Math.abs(matrix[row][pivot]) > Math.abs(matrix[best][pivot])) {
          best = row;
        }
      }

      var swapped = matrix[pivot];
      matrix[pivot] = matrix[best];
      matrix[best] = swapped;
      if (Math.abs(matrix[pivot][pivot]) <= SINGULAR_PIVOT * scale) {
        return Optional.empty();
      }

      for (var row = 0; row < size; row++) {
        if (row != pivot) {
          subtractRow(matrix, row, pivot);
        }
      }
    }

    var values = new double[size];
    for (var row = 0; row < size; row++) {
      values[row] = matrix[row][size] / matrix[row][row];
    }

    return Optional.of(values);
  }

  private static double largestEntry(double[][] matrix) {
    var largest = 0.0;
    for (var row : matrix) {
      for (var column = 0; column < row.length - 1; column++) {
        largest = Math.max(largest, Math.abs(row[column]));
      }
    }

    return largest;
  }

  private static void subtractRow(double[][] matrix, int row, int pivot) {
    var factor = matrix[row][pivot] / matrix[pivot][pivot];
    for (var column = pivot; column < matrix[row].length; column++) {
      matrix[row][column] -= factor * matrix[pivot][column];
    }
  }
}
