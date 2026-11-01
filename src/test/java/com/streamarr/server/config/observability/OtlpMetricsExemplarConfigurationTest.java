package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.registry.otlp.ExemplarContextProvider;
import io.micrometer.registry.otlp.OtlpExemplarContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

@Tag("UnitTest")
@DisplayName("OTLP Metrics Exemplar Configuration Tests")
class OtlpMetricsExemplarConfigurationTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String SPAN_ID = "00f067aa0ba902b7";

  private static final ApplicationContextRunner CONTEXT_RUNNER =
      new ApplicationContextRunner()
          .withUserConfiguration(OtlpMetricsExemplarConfiguration.class)
          .withPropertyValues("management.otlp.metrics.export.enabled=true");

  @Test
  @DisplayName("Should give the current span's IDs when a sampled span is current")
  void shouldGiveTheCurrentSpansIdsWhenASampledSpanIsCurrent() {
    CONTEXT_RUNNER.run(
        context ->
            assertThat(
                    exemplarContextWithin(context, spanContext(TRACE_ID, TraceFlags.getSampled())))
                .get()
                .satisfies(
                    exemplar -> assertThat(exemplar.getTraceId()).isEqualTo(TRACE_ID),
                    exemplar -> assertThat(exemplar.getSpanId()).isEqualTo(SPAN_ID)));
  }

  @Test
  @DisplayName("Should give no exemplar context when the current span is not sampled")
  void shouldGiveNoExemplarContextWhenTheCurrentSpanIsNotSampled() {
    CONTEXT_RUNNER.run(
        context ->
            assertThat(
                    exemplarContextWithin(context, spanContext(TRACE_ID, TraceFlags.getDefault())))
                .isEmpty());
  }

  @Test
  @DisplayName("Should give no exemplar context when the current span's IDs are invalid")
  void shouldGiveNoExemplarContextWhenTheCurrentSpansIdsAreInvalid() {
    // Trace context accepts a sampled flag on an all-zero trace ID, which names no trace.
    CONTEXT_RUNNER.run(
        context ->
            assertThat(
                    exemplarContextWithin(
                        context, spanContext(TraceId.getInvalid(), TraceFlags.getSampled())))
                .isEmpty());
  }

  @Test
  @DisplayName("Should give no exemplar context when no span is current")
  void shouldGiveNoExemplarContextWhenNoSpanIsCurrent() {
    CONTEXT_RUNNER.run(
        context ->
            assertThat(
                    Optional.ofNullable(
                        context.getBean(ExemplarContextProvider.class).getExemplarContext()))
                .isEmpty());
  }

  @Test
  @DisplayName("Should provide no exemplar context when metric export is off")
  void shouldProvideNoExemplarContextWhenMetricExportIsOff() {
    new ApplicationContextRunner()
        .withUserConfiguration(OtlpMetricsExemplarConfiguration.class)
        .run(context -> assertThat(context).doesNotHaveBean(ExemplarContextProvider.class));
  }

  private static SpanContext spanContext(String traceId, TraceFlags flags) {
    return SpanContext.create(traceId, SPAN_ID, flags, TraceState.getDefault());
  }

  private static Optional<OtlpExemplarContext> exemplarContextWithin(
      ApplicationContext context, SpanContext spanContext) {
    try (var _ = Span.wrap(spanContext).makeCurrent()) {
      return Optional.ofNullable(
          context.getBean(ExemplarContextProvider.class).getExemplarContext());
    }
  }
}
