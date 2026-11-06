package com.streamarr.server.services.streaming.headroom;

import com.streamarr.server.domain.streaming.headroom.CostConstant;
import com.streamarr.server.domain.streaming.headroom.CostTerm;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
   * constants are held at these values. Waiting measurements that share no waiting constant are
   * solved apart, so measurements that cannot separate two constants leave the others free. A
   * measurement that prices a constant which comes out not positive is set aside while the rest are
   * solved again, so its cost never moves another constant.
   */
  Map<CostConstant, Double> solve(Map<CostConstant, Double> known) {
    return solveSeparately(waiting, known);
  }

  private static Map<CostConstant, Double> solveSeparately(
      List<Measured> measurements, Map<CostConstant, Double> known) {
    var solved = new HashMap<CostConstant, Double>();
    groupsSharingConstants(measurements, known)
        .forEach(group -> solved.putAll(solveGroup(group, known)));
    return solved;
  }

  private static Map<CostConstant, Double> solveGroup(
      List<Measured> group, Map<CostConstant, Double> known) {
    var unknowns = List.copyOf(unknownsOf(group, known));
    var values = LinearSystem.solve(normalEquations(group, unknowns, known));
    if (values.isEmpty()) {
      return Map.of();
    }

    var notPositive = new HashSet<CostConstant>();
    var solved = new HashMap<CostConstant, Double>();
    for (var index = 0; index < unknowns.size(); index++) {
      var value = values.get()[index];
      if (value <= 0 || !Double.isFinite(value)) {
        notPositive.add(unknowns.get(index));
      }

      solved.put(unknowns.get(index), value);
    }

    if (notPositive.isEmpty()) {
      return solved;
    }

    var usable =
        group.stream()
            .filter(
                measured ->
                    measured.terms().stream().noneMatch(t -> notPositive.contains(t.constant())))
            .toList();
    return solveSeparately(usable, known);
  }

  /** The measurements that price at least one waiting constant, grouped by shared constants. */
  private static List<List<Measured>> groupsSharingConstants(
      List<Measured> measurements, Map<CostConstant, Double> known) {
    var groups = new ArrayList<List<Measured>>();
    var groupConstants = new ArrayList<Set<CostConstant>>();
    for (var measured : measurements) {
      var constants = unknownsOf(List.of(measured), known);
      if (constants.isEmpty()) {
        continue;
      }

      var merged = new ArrayList<Measured>(List.of(measured));
      var mergedConstants = new HashSet<>(constants);
      for (var index = groups.size() - 1; index >= 0; index--) {
        if (!Collections.disjoint(groupConstants.get(index), mergedConstants)) {
          merged.addAll(groups.remove(index));
          mergedConstants.addAll(groupConstants.remove(index));
        }
      }

      groups.add(merged);
      groupConstants.add(mergedConstants);
    }

    return groups;
  }

  private static Set<CostConstant> unknownsOf(
      List<Measured> measurements, Map<CostConstant, Double> known) {
    var constants = new LinkedHashSet<CostConstant>();
    measurements.forEach(
        measured ->
            measured.terms().stream()
                .map(CostTerm::constant)
                .filter(constant -> !known.containsKey(constant))
                .forEach(constants::add));
    return constants;
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

  /**
   * The weighted normal equations for the unknowns, with each known constant's cost taken off the
   * measured cost.
   */
  private static double[][] normalEquations(
      List<Measured> measurements, List<CostConstant> unknowns, Map<CostConstant, Double> known) {
    var size = unknowns.size();
    var matrix = new double[size][size + 1];
    for (var measured : measurements) {
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
