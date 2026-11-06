package com.streamarr.server.services.streaming.headroom;

import com.streamarr.server.domain.streaming.headroom.CostConstant;
import com.streamarr.server.domain.streaming.headroom.CostTerm;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A weighted least-squares fit of the headroom model's constants to measured costs. Each residual
 * counts relative to its measured cost, so a cheap 360p encode weighs as much as a costly 1080p
 * encode. Older measurements fade, and starting constants pull each constant toward themselves with
 * a fixed weight.
 */
final class ConstantFit {

  // A pivot this small against the matrix's largest entry means the measurements do not separate
  // the constants.
  private static final double SINGULAR_PIVOT = 1e-9;

  private final double forgetting;
  private final List<CostConstant> fitted = new ArrayList<>();
  private final Map<CostConstant, Integer> indexes = new HashMap<>();
  private double[][] normal = new double[0][0];
  private double[] moments = new double[0];

  ConstantFit(double forgetting) {
    this.forgetting = forgetting;
  }

  void add(List<CostTerm> terms, double measuredCost) {
    terms.forEach(term -> indexOf(term.constant()));
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
   * starting value pays for its relative change from that value. A constant without one pays, more
   * lightly, for its relative change from its current value, so a constant that the measurements no
   * longer support keeps that value. Empty when the measurements cannot separate the constants, or
   * when a constant comes out not positive.
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
        anchor(matrix, row, start, startingWeight);
        continue;
      }

      var now = current.get(constant);
      if (now != null) {
        anchor(matrix, row, now, currentWeight);
      }
    }

    return eliminate(matrix).map(values -> constantsByName(unknowns, values));
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
      if (!Double.isFinite(values[row]) || values[row] <= 0) {
        return Optional.empty();
      }
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

  private static Map<CostConstant, Double> constantsByName(
      List<CostConstant> unknowns, double[] values) {
    var solved = new HashMap<CostConstant, Double>();
    for (var index = 0; index < values.length; index++) {
      solved.put(unknowns.get(index), values[index]);
    }

    return solved;
  }
}
