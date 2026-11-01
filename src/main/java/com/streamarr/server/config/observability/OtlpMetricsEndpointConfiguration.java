package com.streamarr.server.config.observability;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsConnectionDetails;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsProperties;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Sends metrics to the OpenTelemetry starter's OTLP endpoint, so {@code
 * OTEL_EXPORTER_OTLP_ENDPOINT} moves metrics together with spans and logs. {@code
 * OTEL_EXPORTER_OTLP_METRICS_ENDPOINT}, when set, names the metrics URL as written, and {@code
 * OTEL_EXPORTER_OTLP_METRICS_HEADERS}, when set, replaces {@code OTEL_EXPORTER_OTLP_HEADERS}. It
 * takes the place of {@code management.otlp.metrics.export.url}; the SSL bundle still comes from
 * {@code management.otlp.metrics.export.ssl.bundle}, and {@code
 * management.otlp.metrics.export.headers}, when set, still names the headers.
 */
@Configuration(proxyBeanMethods = false)
class OtlpMetricsEndpointConfiguration {

  private static final String METRICS_PATH = "/v1/metrics";
  private static final String HEADERS = "otel.exporter.otlp.headers";
  private static final String METRICS_HEADERS = "otel.exporter.otlp.metrics.headers";

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

  // Without headers of its own, Micrometer sends OTEL_EXPORTER_OTLP_HEADERS and
  // OTEL_EXPORTER_OTLP_METRICS_HEADERS together, so one collector's credentials would reach the
  // other's endpoint. OpenTelemetry sends a signal's own headers in place of the shared ones.
  @Bean
  static BeanPostProcessor otlpMetricsHeaders(Environment environment) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof OtlpMetricsProperties properties && properties.getHeaders() == null) {
          properties.setHeaders(metricsHeaders(environment));
        }

        return bean;
      }
    };
  }

  private static Map<String, String> metricsHeaders(Environment environment) {
    var listed = environment.getProperty(METRICS_HEADERS, "");
    if (!StringUtils.hasText(listed)) {
      listed = environment.getProperty(HEADERS, "");
    }

    var headers = new LinkedHashMap<String, String>();
    for (var header : listed.split(",")) {
      var separator = header.indexOf('=');
      if (separator > 0) {
        var value = header.substring(separator + 1).trim();
        headers.put(
            header.substring(0, separator).trim(),
            URLDecoder.decode(value, StandardCharsets.UTF_8));
      }
    }

    return headers;
  }

  // OpenTelemetry uses a signal's own endpoint as written.
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
