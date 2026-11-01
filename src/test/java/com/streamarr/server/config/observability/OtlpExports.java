package com.streamarr.server.config.observability;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Exemplar;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Reads what an OTLP export carries. */
final class OtlpExports {

  static final String MICROMETER = "io.micrometer";

  private OtlpExports() {}

  static boolean carriesMeter(ExportMetricsServiceRequest export, String meter) {
    return metricNames(export).contains(meter);
  }

  static List<ExportMetricsServiceRequest> carryingMeter(
      List<ExportMetricsServiceRequest> exports, String meter) {
    return exports.stream().filter(export -> carriesMeter(export, meter)).toList();
  }

  /** The SDK that sent the export: {@code io.micrometer} for Micrometer's OTLP registry. */
  static Optional<String> exporter(ExportMetricsServiceRequest export) {
    return export.getResourceMetricsList().stream()
        .flatMap(resource -> attribute(resource.getResource(), "telemetry.sdk.name").stream())
        .findFirst();
  }

  static List<ExportMetricsServiceRequest> fromMicrometer(
      List<ExportMetricsServiceRequest> exports) {
    return exports.stream()
        .filter(export -> exporter(export).equals(Optional.of(MICROMETER)))
        .toList();
  }

  static Set<String> metricNames(ExportMetricsServiceRequest export) {
    return scopes(export).flatMap(OtlpExports::metricNames).collect(Collectors.toSet());
  }

  static List<Metric> metricsNamed(ExportMetricsServiceRequest export, String meter) {
    return scopes(export)
        .flatMap(scope -> scope.getMetricsList().stream())
        .filter(metric -> metric.getName().equals(meter))
        .toList();
  }

  /** The exemplars on every histogram data point of the meter in the exports. */
  static List<Exemplar> histogramExemplars(
      List<ExportMetricsServiceRequest> exports, String meter) {
    return exports.stream()
        .flatMap(export -> metricsNamed(export, meter).stream())
        .flatMap(metric -> metric.getHistogram().getDataPointsList().stream())
        .flatMap(point -> point.getExemplarsList().stream())
        .toList();
  }

  /** A trace or span ID as the lower-case hex that W3C trace context carries. */
  static String hex(ByteString id) {
    return HexFormat.of().formatHex(id.toByteArray());
  }

  static Set<String> scopesExporting(
      List<ExportMetricsServiceRequest> exports, Predicate<String> metricName) {
    return exports.stream()
        .flatMap(OtlpExports::scopes)
        .filter(scope -> metricNames(scope).anyMatch(metricName))
        .map(scope -> scope.getScope().getName())
        .collect(Collectors.toSet());
  }

  static Optional<String> attribute(Resource resource, String key) {
    return stringValue(resource.getAttributesList(), key);
  }

  static Optional<String> stringValue(List<KeyValue> attributes, String key) {
    return attributes.stream()
        .filter(attribute -> attribute.getKey().equals(key))
        .map(KeyValue::getValue)
        .map(AnyValue::getStringValue)
        .findFirst();
  }

  private static Stream<ScopeMetrics> scopes(ExportMetricsServiceRequest export) {
    return export.getResourceMetricsList().stream()
        .flatMap(resource -> resource.getScopeMetricsList().stream());
  }

  private static Stream<String> metricNames(ScopeMetrics scope) {
    return scope.getMetricsList().stream().map(Metric::getName);
  }
}
