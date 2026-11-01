package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("UnitTest")
@DisplayName("Metric Export Configuration Tests")
class MetricExportConfigurationTest {

  private static final ApplicationContextRunner CONTEXT_RUNNER =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(
              AutoConfigurations.of(
                  MetricsAutoConfiguration.class,
                  OtlpMetricsExportAutoConfiguration.class,
                  SimpleMetricsExportAutoConfiguration.class));

  @Test
  @DisplayName("Should export no metrics when the observability profile is inactive")
  void shouldExportNoMetricsWhenTheObservabilityProfileIsInactive() {
    CONTEXT_RUNNER.run(
        context -> assertThat(context).hasNotFailed().doesNotHaveBean(OtlpMeterRegistry.class));
  }

  @Test
  @DisplayName("Should export metrics to the collector when the observability profile is active")
  void shouldExportMetricsToTheCollectorWhenTheObservabilityProfileIsActive() {
    CONTEXT_RUNNER
        .withPropertyValues("spring.profiles.active=observability")
        .run(
            context -> {
              assertThat(context).hasSingleBean(OtlpMeterRegistry.class);
              var config = context.getBean(OtlpConfig.class);
              assertThat(config.url()).isEqualTo("http://otel-collector:4318/v1/metrics");
              assertThat(config.resourceAttributes())
                  .containsEntry("service.name", "streamarr-server")
                  .containsEntry("deployment.environment", "dev");
            });
  }

  @Test
  @DisplayName("Should leave OpenTelemetry variables to the starter when the server starts")
  void shouldLeaveOpenTelemetryVariablesToTheStarterWhenTheServerStarts() {
    CONTEXT_RUNNER
        .withPropertyValues("spring.profiles.active=observability")
        .run(
            context ->
                assertThat(
                        context
                            .getEnvironment()
                            .getProperty(
                                "management.opentelemetry.map-environment-variables",
                                Boolean.class))
                    .isFalse());
  }
}
