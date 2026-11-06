package com.streamarr.server.services.streaming.headroom;

import com.streamarr.server.domain.streaming.headroom.CostConstant;
import com.streamarr.server.domain.streaming.headroom.CostTerm;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Measurements that price a constant without a value. They wait apart from the fit of the known
 * constants, so a cost that new constants cannot yet separate never moves a known constant. Once
 * every constant that a measurement prices has a value, the measurement joins the fit.
 */
final class PendingMeasurements {

  // A measurement that has faded below this weight no longer counts.
  private static final double FADED = 1e-6;

  private final double forgetting;
  private final List<Measured> waiting = new ArrayList<>();

  PendingMeasurements(double forgetting) {
    this.forgetting = forgetting;
  }

  /** One measured cost and its weight, which fades with every later measurement. */
  record Measured(List<CostTerm> terms, double measuredCost, double fade) {

    double weight() {
      return fade / (measuredCost * measuredCost);
    }
  }

  void fade() {
    waiting.replaceAll(
        measured ->
            new Measured(measured.terms(), measured.measuredCost(), measured.fade() * forgetting));
    waiting.removeIf(measured -> measured.fade() < FADED);
  }

  void add(List<CostTerm> terms, double measuredCost) {
    waiting.add(new Measured(terms, measuredCost, 1));
  }

  /**
   * The value of each waiting constant that the waiting measurements determine once the known
   * constants are held at these values. A constant that comes out not positive stays without a
   * value while the others are solved again. Empty when the measurements cannot separate the
   * waiting constants.
   */
  Map<CostConstant, Double> solve(Map<CostConstant, Double> known) {
    var unknowns = new ArrayList<>(waitingConstants(known));
    while (!unknowns.isEmpty()) {
      var values = LinearSystem.solve(normalEquations(unknowns, known));
      if (values.isEmpty()) {
        return Map.of();
      }

      var solved = new HashMap<CostConstant, Double>();
      for (var index = 0; index < unknowns.size(); index++) {
        solved.put(unknowns.get(index), values.get()[index]);
      }

      if (solved.values().stream().allMatch(value -> value > 0 && Double.isFinite(value))) {
        return solved;
      }

      unknowns.removeIf(
          constant -> solved.get(constant) <= 0 || !Double.isFinite(solved.get(constant)));
    }

    return Map.of();
  }

  /** Hands every waiting measurement whose constants all have values to the fit. */
  void release(Set<CostConstant> valued, Consumer<Measured> fit) {
    var ready =
        waiting.stream()
            .filter(
                measured -> measured.terms().stream().allMatch(t -> valued.contains(t.constant())))
            .toList();
    waiting.removeAll(ready);
    ready.forEach(fit);
  }

  private Set<CostConstant> waitingConstants(Map<CostConstant, Double> known) {
    var constants = new LinkedHashSet<CostConstant>();
    waiting.forEach(
        measured ->
            measured.terms().stream()
                .map(CostTerm::constant)
                .filter(constant -> !known.containsKey(constant))
                .forEach(constants::add));
    return constants;
  }

  /**
   * The weighted normal equations for the waiting constants, with each known constant's cost taken
   * off the measured cost. A constant that a measurement prices but that is neither known nor among
   * the unknowns adds nothing.
   */
  private double[][] normalEquations(List<CostConstant> unknowns, Map<CostConstant, Double> known) {
    var size = unknowns.size();
    var matrix = new double[size][size + 1];
    for (var measured : waiting) {
      var remaining = measured.measuredCost();
      for (var term : measured.terms()) {
        remaining -= known.getOrDefault(term.constant(), 0.0) * term.megapixelsPerSecond();
      }

      var weight = measured.weight();
      for (var term : measured.terms()) {
        var row = unknowns.indexOf(term.constant());
        if (row < 0) {
          continue;
        }

        matrix[row][size] += weight * term.megapixelsPerSecond() * remaining;
        for (var other : measured.terms()) {
          var column = unknowns.indexOf(other.constant());
          if (column >= 0) {
            matrix[row][column] +=
                weight * term.megapixelsPerSecond() * other.megapixelsPerSecond();
          }
        }
      }
    }

    return matrix;
  }
}
