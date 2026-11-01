package com.streamarr.server.config.observability;

import static com.streamarr.server.config.observability.OtlpExports.attribute;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.registry.otlp.OtlpMetricsSender;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.instrumentation.spring.autoconfigure.OpenTelemetryAutoConfiguration;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.resources.Resource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsConnectionDetails;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
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
                  SimpleMetricsExportAutoConfiguration.class,
                  OpenTelemetryAutoConfiguration.class))
          .withUserConfiguration(
              OtlpMetricsEndpointConfiguration.class, OtlpMetricsResourceConfiguration.class)
          // The starter's SDK builds its resource; its span and log exporters stay quiet here.
          .withPropertyValues("otel.traces.exporter=none", "otel.logs.exporter=none");

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
  @DisplayName(
      "Should export metrics under the SDK's resource when the observability profile is active")
  void shouldExportMetricsUnderTheSdksResourceWhenTheObservabilityProfileIsActive() {
    CONTEXT_RUNNER
        .withPropertyValues("spring.profiles.active=observability")
        .run(
            context ->
                assertThat(context.getBean(OtlpConfig.class).resourceAttributes())
                    .containsKeys("service.instance.id", "host.name", "process.pid")
                    .doesNotContainKeys("telemetry.sdk.name", "telemetry.distro.name"));
  }

  @Test
  @DisplayName("Should send the SDK's resource when the registry is built before the SDK")
  void shouldSendTheSdksResourceWhenTheRegistryIsBuiltBeforeTheSdk() {
    var sent = new CopyOnWriteArrayList<ExportMetricsServiceRequest>();
    OtlpMetricsSender sender =
        request -> sent.add(ExportMetricsServiceRequest.parseFrom(request.getMetricsData()));
    CONTEXT_RUNNER
        .withBean(MeterRegistryUser.class)
        .withBean(OtlpMetricsSender.class, () -> sender)
        .withPropertyValues("spring.profiles.active=observability")
        .run(
            context -> {
              var registry = context.getBean(OtlpMeterRegistry.class);
              registry.counter("metric_export_test.counter").increment();
              // Closing the registry sends its last export.
              registry.close();

              assertThat(sent)
                  .flatMap(ExportMetricsServiceRequest::getResourceMetricsList)
                  .extracting(resource -> attribute(resource.getResource(), "service.instance.id"))
                  .isNotEmpty()
                  .allSatisfy(instance -> assertThat(instance).isPresent());
            });
  }

  @Test
  @DisplayName("Should export an array resource attribute as a JSON array when the SDK has one")
  void shouldExportAnArrayResourceAttributeAsAJsonArrayWhenTheSdkHasOne() {
    ResourceProvider listProvider =
        config ->
            Resource.create(
                Attributes.of(
                    AttributeKey.stringArrayKey("test.names"),
                    List.of("first", "quoted \"second\""),
                    AttributeKey.longArrayKey("test.numbers"),
                    List.of(1L, 2L)));
    CONTEXT_RUNNER
        .withBean(ResourceProvider.class, () -> listProvider)
        .withPropertyValues("spring.profiles.active=observability")
        .run(
            context ->
                assertThat(context.getBean(OtlpConfig.class).resourceAttributes())
                    .containsEntry("test.names", "[\"first\",\"quoted \\\"second\\\"\"]")
                    .containsEntry("test.numbers", "[1,2]"));
  }

  @Test
  @DisplayName("Should export metrics under the profile's service name when the SDK is disabled")
  void shouldExportMetricsUnderTheProfilesServiceNameWhenTheSdkIsDisabled() {
    CONTEXT_RUNNER
        .withPropertyValues("spring.profiles.active=observability", "otel.sdk.disabled=true")
        .run(
            context ->
                assertThat(context.getBean(OtlpConfig.class).resourceAttributes())
                    .containsEntry("service.name", "streamarr-server")
                    .containsEntry("deployment.environment", "dev")
                    .doesNotContainKey("service.instance.id"));
  }

  @Test
  @DisplayName("Should export metrics once a minute when the observability profile is active")
  void shouldExportMetricsOnceAMinuteWhenTheObservabilityProfileIsActive() {
    CONTEXT_RUNNER
        .withPropertyValues("spring.profiles.active=observability")
        .run(
            context ->
                assertThat(context.getBean(OtlpConfig.class).step())
                    .isEqualTo(Duration.ofMinutes(1)));
  }

  @Test
  @DisplayName("Should export metrics under the deployment environment when DEPLOYMENT_ENV is set")
  void shouldExportMetricsUnderTheDeploymentEnvironmentWhenDeploymentEnvIsSet() {
    CONTEXT_RUNNER
        .withPropertyValues("spring.profiles.active=observability", "DEPLOYMENT_ENV=staging")
        .run(
            context ->
                assertThat(context.getBean(OtlpConfig.class).resourceAttributes())
                    .containsEntry("deployment.environment", "staging"));
  }

  @Test
  @DisplayName("Should append the metrics path once when the endpoint ends in a slash")
  void shouldAppendTheMetricsPathOnceWhenTheEndpointEndsInASlash() {
    CONTEXT_RUNNER
        .withPropertyValues(
            "spring.profiles.active=observability",
            "otel.exporter.otlp.endpoint=http://collector.example:4318/")
        .run(
            context ->
                assertThat(context.getBean(OtlpConfig.class).url())
                    .isEqualTo("http://collector.example:4318/v1/metrics"));
  }

  @Test
  @DisplayName("Should send metrics to the metrics endpoint as written when one is set")
  void shouldSendMetricsToTheMetricsEndpointAsWrittenWhenOneIsSet() {
    CONTEXT_RUNNER
        .withPropertyValues(
            "spring.profiles.active=observability",
            "otel.exporter.otlp.endpoint=http://traces.example:4318",
            "otel.exporter.otlp.metrics.endpoint=https://metrics.example:4318/custom/metrics")
        .run(
            context ->
                assertThat(context.getBean(OtlpConfig.class).url())
                    .isEqualTo("https://metrics.example:4318/custom/metrics"));
  }

  @Test
  @DisplayName("Should send only the metrics headers when metrics headers are set")
  void shouldSendOnlyTheMetricsHeadersWhenMetricsHeadersAreSet() {
    CONTEXT_RUNNER
        .withPropertyValues(
            "spring.profiles.active=observability",
            "otel.exporter.otlp.headers=x-shared-key=shared",
            "otel.exporter.otlp.metrics.headers=x-metrics-key=metrics%20token")
        .run(
            context ->
                assertThat(context.getBean(OtlpConfig.class).headers())
                    .containsExactlyEntriesOf(Map.of("x-metrics-key", "metrics token")));
  }

  @Test
  @DisplayName("Should send the shared headers when no metrics headers are set")
  void shouldSendTheSharedHeadersWhenNoMetricsHeadersAreSet() {
    CONTEXT_RUNNER
        .withPropertyValues(
            "spring.profiles.active=observability",
            "otel.exporter.otlp.headers=x-shared-key=shared, x-tenant=one")
        .run(
            context ->
                assertThat(context.getBean(OtlpConfig.class).headers())
                    .containsExactlyInAnyOrderEntriesOf(
                        Map.of("x-shared-key", "shared", "x-tenant", "one")));
  }

  @Test
  @DisplayName("Should keep the configured SSL bundle when metrics go to the starter's endpoint")
  void shouldKeepTheConfiguredSslBundleWhenMetricsGoToTheStartersEndpoint() {
    var collectorBundle = SslBundle.of(SslStoreBundle.NONE);
    CONTEXT_RUNNER
        .withBean(
            SslBundles.class, () -> new DefaultSslBundleRegistry("collector", collectorBundle))
        .withPropertyValues(
            "spring.profiles.active=observability",
            "management.otlp.metrics.export.ssl.bundle=collector")
        .run(
            context ->
                assertThat(context.getBean(OtlpMetricsConnectionDetails.class))
                    .satisfies(
                        details ->
                            assertThat(details.getUrl())
                                .isEqualTo("http://otel-collector:4318/v1/metrics"),
                        details -> assertThat(details.getSslBundle()).isSameAs(collectorBundle)));
  }

  @Test
  @DisplayName("Should leave OpenTelemetry variables to the starter when the server starts")
  void shouldLeaveOpenTelemetryVariablesToTheStarterWhenTheServerStarts() {
    // Spring Boot reads the variables from System.getenv(), which a test cannot set, so the test
    // reads the switch that stops it from mapping them.
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

  // Registered before the auto-configurations, so the meter registry is built before the SDK.
  record MeterRegistryUser(MeterRegistry meterRegistry) {}
}
