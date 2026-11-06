package com.streamarr.server.services.streaming.headroom;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A small dense system of symmetric normal equations, solved in place. */
final class LinearSystem {

  // A pivot this small against the matrix's largest entry means the system does not determine
  // that unknown.
  private static final double SINGULAR_PIVOT = 1e-9;

  private LinearSystem() {}

  /** The solution of the augmented system, or empty when it does not determine every unknown. */
  static Optional<double[]> solve(double[][] matrix) {
    var size = matrix.length;
    var determined = solveDetermined(matrix);
    if (determined.size() < size) {
      return Optional.empty();
    }

    var values = new double[size];
    determined.forEach((index, value) -> values[index] = value);
    return Optional.of(values);
  }

  /**
   * The value of each unknown that the augmented system determines, by index, even when it leaves
   * other unknowns free. Each unknown is first scaled so that its diagonal entry is one, so a
   * constant close to zero, whose anchor makes its diagonal entry huge, cannot make the other
   * unknowns look undetermined.
   */
  static Map<Integer, Double> solveDetermined(double[][] matrix) {
    var size = matrix.length;
    var scales = new double[size];
    for (var index = 0; index < size; index++) {
      scales[index] = 1;
      var diagonal = Math.abs(matrix[index][index]);
      if (diagonal > 0) {
        scales[index] = 1 / Math.sqrt(diagonal);
      }
    }

    var scaled = new double[size][size + 1];
    for (var row = 0; row < size; row++) {
      for (var column = 0; column < size; column++) {
        scaled[row][column] = scales[row] * matrix[row][column] * scales[column];
      }

      scaled[row][size] = scales[row] * matrix[row][size];
    }

    var determined = new HashMap<Integer, Double>();
    new ReducedRows(scaled)
        .determined()
        .forEach((index, value) -> determined.put(index, value * scales[index]));
    return determined;
  }

  /** The reduced row echelon form of an augmented system, by Gauss-Jordan elimination. */
  private static final class ReducedRows {

    private final double[][] rows;
    private final int size;
    private final double tolerance;
    private final int[] pivotColumns;
    private int rank;

    private ReducedRows(double[][] rows) {
      this.rows = rows;
      this.size = rows.length;
      this.tolerance = SINGULAR_PIVOT * largestEntry(rows);
      this.pivotColumns = new int[size];
      for (var column = 0; column < size; column++) {
        reduceColumn(column);
      }
    }

    private void reduceColumn(int column) {
      var best = rank;
      for (var row = rank + 1; row < size; row++) {
        if (Math.abs(rows[row][column]) > Math.abs(rows[best][column])) {
          best = row;
        }
      }

      if (best >= size || Math.abs(rows[best][column]) <= tolerance) {
        return;
      }

      var swapped = rows[rank];
      rows[rank] = rows[best];
      rows[best] = swapped;
      var pivot = rows[rank][column];
      for (var entry = column; entry <= size; entry++) {
        rows[rank][entry] /= pivot;
      }

      for (var row = 0; row < size; row++) {
        if (row != rank) {
          subtractRow(row, column);
        }
      }

      pivotColumns[rank] = column;
      rank++;
    }

    private void subtractRow(int row, int column) {
      var factor = rows[row][column];
      for (var entry = column; entry <= size; entry++) {
        rows[row][entry] -= factor * rows[rank][entry];
      }
    }

    /** Each pivot unknown whose row no free unknown enters, with its value. */
    private Map<Integer, Double> determined() {
      var pivots = new boolean[size];
      for (var row = 0; row < rank; row++) {
        pivots[pivotColumns[row]] = true;
      }

      var determined = new HashMap<Integer, Double>();
      for (var row = 0; row < rank; row++) {
        if (isFreeOfUndetermined(row, pivots)) {
          determined.put(pivotColumns[row], rows[row][size]);
        }
      }

      return determined;
    }

    private boolean isFreeOfUndetermined(int row, boolean[] pivots) {
      for (var column = 0; column < size; column++) {
        if (!pivots[column] && Math.abs(rows[row][column]) > tolerance) {
          return false;
        }
      }

      return true;
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
  }
}
