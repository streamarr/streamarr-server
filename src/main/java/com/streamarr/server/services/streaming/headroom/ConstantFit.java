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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.ojalgo.matrix.decomposition.Cholesky;
import org.ojalgo.matrix.store.MatrixStore;
import org.ojalgo.matrix.store.R064Store;
import org.ojalgo.optimisation.convex.ConvexSolver;

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
  private static final double NEW_CONSTANT_WEIGHT = 1e-6;
  // The measurements settle a new constant when the nominal value of every new constant could move
  // it, through the whole fit, by at most this share of its value, or of the anchor floor for a
  // smaller constant. Each nominal value may be off by up to the largest measured constant, in
  // either direction.
  private static final double SETTLED_SHARE = 1e-3;
  private static final double NOMINAL_ERROR = 0.1;
  private static final double NOMINAL_PRECISION =
      NEW_CONSTANT_WEIGHT / (NEW_CONSTANT * NEW_CONSTANT);
  // Each set of these constants held at the floor costs one more inversion of the normal matrix.
  private static final int MOST_CONSTANTS_NEAR_FLOOR = 6;
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

    var newConstants =
        index.keySet().stream()
            .filter(constant -> !starting.containsKey(constant) && !current.containsKey(constant))
            .toList();
    index.forEach(
        (constant, row) -> {
          var start = starting.get(constant);
          if (start != null) {
            anchor(normal, moments, row, start, startingWeight * startingFade);
          }

          var now = current.get(constant);
          if (now != null) {
            anchor(normal, moments, row, now, currentWeight);
          }
        });
    newConstants.forEach(
        constant ->
            anchor(normal, moments, index.get(constant), NEW_CONSTANT, NEW_CONSTANT_WEIGHT));

    var newRows = newConstants.stream().map(index::get).collect(Collectors.toSet());
    return smallestPositiveSolution(normal, moments)
        .map(
            values -> {
              var nominalPull = nominalPull(normal, values, newRows);
              var solved = new HashMap<CostConstant, Double>();
              index.forEach(
                  (constant, row) -> {
                    if (!newRows.contains(row)
                        || nominalPull[row]
                            <= SETTLED_SHARE * Math.max(values[row], ANCHOR_FLOOR)) {
                      solved.put(constant, values[row]);
                    }
                  });
              return solved;
            });
  }

  /**
   * For each constant, the most that the nominal values of the new constants can move it when each
   * nominal value is off by up to the nominal error. The fit's solution moves piecewise linearly
   * with the nominal values: while a set of constants sits at the floor, the others move by
   * (N_FF)^-1 p times the nominal values' errors, where N_FF is the fit's normal matrix over the
   * other constants and p the nominal precision. The pull is the largest such move over every set
   * of constants that can reach the floor or leave it. When too many constants can, the pull is the
   * coarser bound of every set at once.
   */
  private static double[] nominalPull(double[][] normal, double[] values, Set<Integer> newRows) {
    var moveBound = moveBound(normal, newRows);
    var nearFloor =
        IntStream.range(0, values.length)
            .filter(row -> values[row] - moveBound[row] < 2 * SMALLEST_CONSTANT)
            .boxed()
            .toList();
    if (nearFloor.size() > MOST_CONSTANTS_NEAR_FLOOR) {
      return moveBound;
    }

    var pull = new double[values.length];
    for (var held = 0; held < 1 << nearFloor.size(); held++) {
      var heldRows = new HashSet<Integer>();
      for (var bit = 0; bit < nearFloor.size(); bit++) {
        if ((held >> bit & 1) == 1) {
          heldRows.add(nearFloor.get(bit));
        }
      }

      var moving = IntStream.range(0, values.length).filter(row -> !heldRows.contains(row));
      raiseToNominalPull(pull, normal, moving.boxed().toList(), newRows);
    }

    return pull;
  }

  /**
   * For each constant, a bound on how far the nominal values of the new constants can move it,
   * whichever constants reach the floor. The fit's solution is the projection, in the norm of N, of
   * the unbounded solution onto the constants at or above the floor, and that projection never
   * lengthens a move in that norm. A change d of the nominal values moves the unbounded solution by
   * N^-1 p d, and a move of length L in that norm moves a constant by at most L times the root of
   * its diagonal entry of N^-1.
   */
  private static double[] moveBound(double[][] normal, Set<Integer> newRows) {
    var inverse = inverseOver(normal, IntStream.range(0, normal.length).boxed().toList());
    var spread = 0.0;
    for (var row : newRows) {
      for (var column : newRows) {
        spread += Math.abs(inverse.doubleValue(row, column));
      }
    }

    var longestMove = NOMINAL_ERROR * NOMINAL_PRECISION * Math.sqrt(spread);
    var bound = new double[normal.length];
    for (var row = 0; row < normal.length; row++) {
      bound[row] = Math.sqrt(inverse.doubleValue(row, row)) * longestMove;
    }

    return bound;
  }

  private static void raiseToNominalPull(
      double[] pull, double[][] normal, List<Integer> moving, Set<Integer> newRows) {
    if (moving.isEmpty()) {
      return;
    }

    var inverse = inverseOver(normal, moving);
    for (var row = 0; row < moving.size(); row++) {
      var move = 0.0;
      for (var column = 0; column < moving.size(); column++) {
        if (newRows.contains(moving.get(column))) {
          move += NOMINAL_ERROR * NOMINAL_PRECISION * Math.abs(inverse.doubleValue(row, column));
        }
      }

      pull[moving.get(row)] = Math.max(pull[moving.get(row)], move);
    }
  }

  private static MatrixStore<Double> inverseOver(double[][] normal, List<Integer> rows) {
    var matrix = R064Store.FACTORY.make(rows.size(), rows.size());
    for (var row = 0; row < rows.size(); row++) {
      for (var column = 0; column < rows.size(); column++) {
        matrix.set(row, column, normal[rows.get(row)][rows.get(column)]);
      }
    }

    var cholesky = Cholesky.R064.make();
    cholesky.decompose(matrix);
    return cholesky.getInverse();
  }

  /**
   * Pulls the constant toward the value. The pull counts relative to the value, except that a value
   * below the anchor floor counts as if it were the floor, so a constant near zero can still grow.
   */
  private static void anchor(
      double[][] normal, double[] moments, int row, double value, double weight) {
    var scale = Math.max(value, ANCHOR_FLOOR);
    normal[row][row] += weight / (scale * scale);
    moments[row] += weight * value / (scale * scale);
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
