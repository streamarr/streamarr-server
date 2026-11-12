package com.streamarr.server.config.observability;

import io.micrometer.registry.otlp.OtlpMetricsSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;

/**
 * Bounds the metrics publishes that the server's stop waits for. Spring Boot closes the OTLP meter
 * registry as soon as the context closes, before the server stops anything else. The close waits
 * for a publish in flight and then publishes once more, and each publish waits for a metrics store
 * that does not answer up to its connect and read timeouts, 11 s by default.
 */
@Configuration(proxyBeanMethods = false)
class OtlpMetricsShutdownConfiguration {

  private OtlpMetricsShutdownConfiguration() {}

  @Bean
  static MetricsSendersAtShutdown metricsSendersAtShutdown() {
    return new MetricsSendersAtShutdown();
  }

  /**
   * Wraps each OTLP metrics sender, and starts their shutdown bound when the context closes, before
   * Spring Boot closes the meter registries.
   */
  static final class MetricsSendersAtShutdown
      implements BeanPostProcessor, ApplicationListener<ContextClosedEvent>, Ordered {

    private final List<ShutdownBoundedMetricsSender> senders = new CopyOnWriteArrayList<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
      if (!(bean instanceof OtlpMetricsSender sender)) {
        return bean;
      }

      var bounded = new ShutdownBoundedMetricsSender(sender);
      senders.add(bounded);
      return bounded;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
      senders.forEach(ShutdownBoundedMetricsSender::beginShutdown);
    }

    @Override
    public int getOrder() {
      return Ordered.HIGHEST_PRECEDENCE;
    }
  }
}
