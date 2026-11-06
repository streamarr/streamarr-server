package com.streamarr.server.services.streaming.headroom;

import java.util.Optional;

/** A small dense linear system, solved in place. */
final class LinearSystem {

  // A pivot this small against the matrix's largest entry means the system does not determine
  // every unknown.
  private static final double SINGULAR_PIVOT = 1e-9;

  private LinearSystem() {}

  /**
   * The solution of the augmented system of symmetric normal equations, or empty when the system is
   * singular. Each unknown is first scaled so that its diagonal entry is one, so a constant close
   * to zero, whose anchor makes its diagonal entry huge, cannot make the other unknowns look
   * singular.
   */
  static Optional<double[]> solve(double[][] matrix) {
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

    return eliminate(scaled)
        .map(
            values -> {
              for (var index = 0; index < size; index++) {
                values[index] *= scales[index];
              }

              return values;
            });
  }

  /**
   * The solution of the augmented system, by Gauss-Jordan elimination with partial pivoting, or
   * empty when the system is singular.
   */
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
