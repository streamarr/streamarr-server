package com.streamarr.server.services.streaming.headroom;

import java.util.Optional;

/** A small dense linear system, solved in place. */
final class LinearSystem {

  // A pivot this small against the matrix's largest entry means the system does not determine
  // every unknown.
  private static final double SINGULAR_PIVOT = 1e-9;

  private LinearSystem() {}

  /**
   * The solution of the augmented system, by Gauss-Jordan elimination with partial pivoting, or
   * empty when the system is singular.
   */
  static Optional<double[]> solve(double[][] matrix) {
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
