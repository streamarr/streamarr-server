package com.streamarr.server.config.observability;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.observation.MeterObservationHandler;
import io.micrometer.observation.Observation;
import io.micrometer.registry.otlp.ExemplarContextProvider;
import io.micrometer.registry.otlp.OtlpExemplarContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Links exported metrics to traces. A counter, timer or distribution summary recorded while a
 * sampled OpenTelemetry span is current carries that span's trace and span IDs as an exemplar. An
 * observation's meters carry the span that was current when the observation started.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("management.otlp.metrics.export.enabled")
class OtlpMetricsExemplarConfiguration {

  // The OpenTelemetry starter's server span filter has the order HIGHEST_PRECEDENCE + 1.
  private static final int INSIDE_THE_SERVER_SPAN = Ordered.HIGHEST_PRECEDENCE + 2;

  @Bean
  ExemplarContextProvider currentSpanExemplarContextProvider() {
    return () -> {
      var span = Span.current().getSpanContext();
      if (!span.isValid() || !span.isSampled()) {
        // Micrometer's contract: null records the measurement without an exemplar.
        return null;
      }

      return new OtlpExemplarContext(span.getTraceId(), span.getSpanId());
    };
  }

  // An asynchronous request's observation stops when the request completes, after the starter
  // has closed the server span's scope, so its timer would find no current span. Micrometer
  // Tracing's meter handler restores the observation's span in the same way.
  @Bean
  static BeanPostProcessor metersRecordedInTheStartingContext() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DefaultMeterObservationHandler handler) {
          return new StartingContextMeterObservationHandler(handler);
        }

        return bean;
      }
    };
  }

  // Spring Boot's request observation filter has the starter's order too, and its registration
  // bean sorts ahead of the starter's plain filter bean. The observation then started before the
  // server span and stopped after it had ended.
  @Bean
  static BeanPostProcessor requestObservationInsideTheServerSpan() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof FilterRegistrationBean<?> registration
            && registration.getFilter() instanceof ServerHttpObservationFilter) {
          registration.setOrder(INSIDE_THE_SERVER_SPAN);
        }

        return bean;
      }
    };
  }

  /** Records an observation's meters while the OpenTelemetry context it started in is current. */
  @RequiredArgsConstructor
  private static final class StartingContextMeterObservationHandler
      implements MeterObservationHandler<Observation.Context> {

    private static final Object STARTING_CONTEXT = new Object();

    private final DefaultMeterObservationHandler delegate;

    @Override
    public void onStart(Observation.Context context) {
      context.put(STARTING_CONTEXT, Context.current());
      delegate.onStart(context);
    }

    @Override
    public void onEvent(Observation.Event event, Observation.Context context) {
      delegate.onEvent(event, context);
    }

    @Override
    public void onStop(Observation.Context context) {
      Context startingContext = context.getRequired(STARTING_CONTEXT);
      try (var _ = startingContext.makeCurrent()) {
        delegate.onStop(context);
      }
    }
  }
}
