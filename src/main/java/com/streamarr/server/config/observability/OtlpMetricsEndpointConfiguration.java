package com.streamarr.server.config.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Sends metrics to the OpenTelemetry starter's OTLP endpoint, so {@code
 * OTEL_EXPORTER_OTLP_ENDPOINT} moves metrics together with spans and logs.
 */
@Configuration(proxyBeanMethods = false)
class OtlpMetricsEndpointConfiguration {

  private static final String METRICS_PATH = "/v1/metrics";

  @Bean
  OtlpMetricsConnectionDetails otlpMetricsConnectionDetails(
      @Value("${otel.exporter.otlp.endpoint:http://localhost:4318}") String endpoint) {
    // An endpoint may end in a slash; the starter's span and log paths tolerate one.
    var url = StringUtils.trimTrailingCharacter(endpoint, '/') + METRICS_PATH;
    return () -> url;
  }
}
