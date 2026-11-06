package com.streamarr.server.services.streaming.headroom;

import com.streamarr.server.domain.streaming.headroom.CostConstant;
import com.streamarr.server.domain.streaming.headroom.CostTerm;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.ojalgo.matrix.decomposition.Cholesky;
import org.ojalgo.matrix.store.R064Store;
import org.ojalgo.optimisation.convex.ConvexSolver;
import org.ojalgo.structure.Access2D;

/**
 * A weighted least-squares fit of the headroom model's constants to measured costs, with a prior on
 * every constant and every constant kept positive. Each residual counts relative to its measured
 * cost, so a cheap 360p encode weighs as much as a costly 1080p encode. Older measurements fade,
 * and so does the pull of the starting constants, as if the starting constants were measurements
 * taken before the first one.
 */
final class ConstantFit {

  // CPU seconds per megapixel. Measured constants range from about 0.002 to 0.1.
  private static final double ANCHOR_FLOOR = 0.01;
  // A constant this small adds under a thousandth of a CPU second per media second at 2160p60.
  private static final double SMALLEST_CONSTANT = 1e-6;
  // A constant that a measurement names for the first time, with no starting or current value,
  // starts in the middle of the measured range on a log scale, with a pull so light that the
  // measurement's own cost, not this guess, gives it its value.
  private static final double NEW_CONSTANT = 0.01;
  private static final double NEW_CONSTANT_WEIGHT = 1e-4;
  // A new constant gets a value once the measurements, not its nominal prior, give at least 90 %
  // of its precision: its posterior variance is at most a tenth of its prior variance.
  private static final double SETTLED_BY_PRIOR = 0.1;
  private static final double NEW_CONSTANT_PRECISION = precision(NEW_CONSTANT, NEW_CONSTANT_WEIGHT);
  // A measurement whose weight has faded below this no longer counts.
  private static final double FADED = 1e-6;

  private final double forgetting;
  private final List<Measured> measurements = new ArrayList<>();
  private final Set<CostConstant> measured = new HashSet<>();
  private double startingFade = 1;

  private record Measured(List<CostTerm> terms, double measuredCost, double fade) {

    double weight() {
      return fade / (measuredCost * measuredCost);
    }
  }

  ConstantFit(double forgetting) {
    this.forgetting = forgetting;
  }

  void add(List<CostTerm> terms, double measuredCost) {
    startingFade *= forgetting;
    measurements.replaceAll(
        older -> new Measured(older.terms(), older.measuredCost(), older.fade() * forgetting));
    measurements.removeIf(older -> older.fade() < FADED);
    measurements.add(new Measured(terms, measuredCost, 1));
    terms.forEach(term -> measured.add(term.constant()));
  }

  /** The constants that a measurement has priced. */
  Set<CostConstant> measuredConstants() {
    return Set.copyOf(measured);
  }

  /**
   * Solves for every constant that a measurement or a starting or current value names. A constant
   * with a starting value pays for its relative change from that value, with a weight that fades as
   * the measurements do. A constant with a current value also pays, lightly and without fading, for
   * its relative change from that value, so a constant that the measurements no longer support
   * keeps it. A new constant, with neither, pays very lightly for its change from a nominal value.
   * Every constant therefore has a prior, so the solution is unique, and none comes out below the
   * smallest constant. A new constant joins the solution only once the measurements settle it, so a
   * split of one cost that only the nominal priors decide never becomes a constant. Empty only when
   * the solver fails.
   */
  Optional<Map<CostConstant, Double>> solve(
      Map<CostConstant, Double> starting,
      double startingWeight,
      Map<CostConstant, Double> current,
      double currentWeight) {
    var unknowns = new LinkedHashSet<>(starting.keySet());
    unknowns.addAll(current.keySet());
    measurements.forEach(m -> m.terms().forEach(term -> unknowns.add(term.constant())));
    var index = new HashMap<CostConstant, Integer>();
    unknowns.forEach(constant -> index.put(constant, index.size()));

    var size = unknowns.size();
    var normal = new double[size][size];
    var moments = new double[size];
    for (var measurement : measurements) {
      var weight = measurement.weight();
      for (var term : measurement.terms()) {
        var row = index.get(term.constant());
        moments[row] += weight * term.megapixelsPerSecond() * measurement.measuredCost();
        for (var other : measurement.terms()) {
          normal[row][index.get(other.constant())] +=
              weight * term.megapixelsPerSecond() * other.megapixelsPerSecond();
        }
      }
    }

    var newConstants = new HashSet<CostConstant>();
    index.forEach(
        (constant, row) -> {
          var start = starting.get(constant);
          if (start != null) {
            anchor(normal, moments, row, start, startingWeight * startingFade);
          }

          var now = current.get(constant);
          if (now != null) {
            anchor(normal, moments, row, now, currentWeight);
            return;
          }

          if (start == null) {
            anchor(normal, moments, row, NEW_CONSTANT, NEW_CONSTANT_WEIGHT);
            newConstants.add(constant);
          }
        });

    var variances = posteriorVariances(normal);
    return smallestPositiveSolution(normal, moments)
        .map(
            values -> {
              var solved = new HashMap<CostConstant, Double>();
              index.forEach(
                  (constant, row) -> {
                    if (!newConstants.contains(constant)
                        || variances[row] * NEW_CONSTANT_PRECISION <= SETTLED_BY_PRIOR) {
                      solved.put(constant, values[row]);
                    }
                  });
              return solved;
            });
  }

  /** The diagonal of the inverse of the positive-definite normal matrix. */
  private static double[] posteriorVariances(double[][] normal) {
    var cholesky = Cholesky.R064.make();
    cholesky.decompose(R064Store.FACTORY.copy(Access2D.wrap(normal)));
    var inverse = cholesky.getInverse();
    var variances = new double[normal.length];
    for (var row = 0; row < normal.length; row++) {
      variances[row] = inverse.doubleValue(row, row);
    }

    return variances;
  }

  /**
   * Pulls the constant toward the value. The pull counts relative to the value, except that a value
   * below the anchor floor counts as if it were the floor, so a constant near zero can still grow.
   */
  private static void anchor(
      double[][] normal, double[] moments, int row, double value, double weight) {
    var precision = precision(value, weight);
    normal[row][row] += precision;
    moments[row] += precision * value;
  }

  private static double precision(double value, double weight) {
    var scale = Math.max(value, ANCHOR_FLOOR);
    return weight / (scale * scale);
  }

  /**
   * Minimises x'Nx/2 - m'x with every x at least the smallest constant. A prior on every constant
   * keeps N positive definite, so the minimum is unique.
   */
  private static Optional<double[]> smallestPositiveSolution(double[][] normal, double[] moments) {
    var size = moments.length;
    var problem = ConvexSolver.newBuilder(size);
    for (var row = 0; row < size; row++) {
      problem.objective(row, moments[row]);
      for (var column = 0; column < size; column++) {
        problem.objective(row, column, normal[row][column]);
      }

      var atLeastSmallest = new double[size];
      atLeastSmallest[row] = -1;
      problem.inequality(-SMALLEST_CONSTANT, atLeastSmallest);
    }

    var result = problem.solve();
    if (!result.getState().isOptimal()) {
      return Optional.empty();
    }

    var values = new double[size];
    for (var row = 0; row < size; row++) {
      // The solver meets the bound to within its tolerance.
      values[row] = Math.max(result.doubleValue(row), SMALLEST_CONSTANT);
    }

    return Optional.of(values);
  }
}
