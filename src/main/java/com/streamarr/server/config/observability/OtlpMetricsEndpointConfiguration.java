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
 * OTEL_EXPORTER_OTLP_ENDPOINT} moves metrics together with spans and logs. The SSL bundle still
 * comes from {@code management.otlp.metrics.export.ssl.bundle}.
 */
@Configuration(proxyBeanMethods = false)
class OtlpMetricsEndpointConfiguration {

  private static final String METRICS_PATH = "/v1/metrics";

  @Bean
  OtlpMetricsConnectionDetails otlpMetricsConnectionDetails(
      @Value("${otel.exporter.otlp.endpoint:http://localhost:4318}") String endpoint,
      @Value("${management.otlp.metrics.export.ssl.bundle:}") String sslBundleName,
      ObjectProvider<SslBundles> sslBundles) {
    // An endpoint may end in a slash; the starter's span and log paths tolerate one.
    var url = StringUtils.trimTrailingCharacter(endpoint, '/') + METRICS_PATH;
    if (!StringUtils.hasText(sslBundleName)) {
      return () -> url;
    }

    var sslBundle = sslBundles.getObject().getBundle(sslBundleName);
    return new StarterEndpointConnectionDetails(url, sslBundle);
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
