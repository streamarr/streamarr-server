package com.streamarr.server.config.observability;

import io.micrometer.registry.otlp.ExemplarContextProvider;
import io.micrometer.registry.otlp.OtlpExemplarContext;
import io.opentelemetry.api.trace.Span;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Links exported metrics to traces. A counter, timer or distribution summary recorded while a
 * sampled OpenTelemetry span is current carries that span's trace and span IDs as an exemplar.
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

  // Spring Boot's request observation filter has the starter's order too, and its registration
  // bean sorts ahead of the starter's plain filter bean. The observation then recorded
  // http.server.requests after the server span had ended, so the timer found no current span.
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
}
