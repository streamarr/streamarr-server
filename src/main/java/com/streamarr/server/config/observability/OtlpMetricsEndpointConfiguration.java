package com.streamarr.server.config.observability;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsConnectionDetails;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Sends metrics to the OpenTelemetry starter's OTLP endpoint, so {@code
 * OTEL_EXPORTER_OTLP_ENDPOINT} moves metrics together with spans and logs. {@code
 * OTEL_EXPORTER_OTLP_METRICS_ENDPOINT}, when set, names the metrics URL as written. It takes the
 * place of {@code management.otlp.metrics.export.url}; the SSL bundle still comes from {@code
 * management.otlp.metrics.export.ssl.bundle}.
 */
@Configuration(proxyBeanMethods = false)
class OtlpMetricsEndpointConfiguration {

  private static final String METRICS_PATH = "/v1/metrics";

  @Bean
  OtlpMetricsConnectionDetails otlpMetricsConnectionDetails(
      @Value("${otel.exporter.otlp.endpoint:http://localhost:4318}") String endpoint,
      @Value("${otel.exporter.otlp.metrics.endpoint:}") String metricsEndpoint,
      @Value("${management.otlp.metrics.export.ssl.bundle:}") String sslBundleName,
      ObjectProvider<SslBundles> sslBundles) {
    var url = metricsUrl(endpoint, metricsEndpoint);
    if (!StringUtils.hasText(sslBundleName)) {
      return () -> url;
    }

    var sslBundle = sslBundles.getObject().getBundle(sslBundleName);
    return new StarterEndpointConnectionDetails(url, sslBundle);
  }

  // Micrometer attaches OTEL_EXPORTER_OTLP_METRICS_HEADERS to every export, so metrics must go to
  // the endpoint those credentials belong to. OpenTelemetry uses a signal's endpoint as written.
  private static String metricsUrl(String endpoint, String metricsEndpoint) {
    if (StringUtils.hasText(metricsEndpoint)) {
      return metricsEndpoint;
    }

    // An endpoint may end in a slash; the starter's span and log paths tolerate one.
    return StringUtils.trimTrailingCharacter(endpoint, '/') + METRICS_PATH;
  }

  private record StarterEndpointConnectionDetails(String url, SslBundle sslBundle)
      implements OtlpMetricsConnectionDetails {

    @Override
    public String getUrl() {
      return url;
    }

    @Override
    public SslBundle getSslBundle() {
      return sslBundle;
    }
  }
}
