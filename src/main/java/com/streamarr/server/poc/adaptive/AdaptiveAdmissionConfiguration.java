package com.streamarr.server.poc.adaptive;

import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.FileAdmission;
import com.streamarr.server.services.library.admission.adaptive.AdaptiveFileAdmission;
import com.streamarr.server.services.library.admission.adaptive.AdaptiveLimitAlgorithm;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Throwaway benchmark wiring for variants C1 ({@code poc.admission=adaptive-gradient2}) and C2
 * ({@code adaptive-vegas}). Nothing here is active for any other {@code poc.admission} value.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${poc.admission:unbounded}'.startsWith('adaptive-')")
public class AdaptiveAdmissionConfiguration {

  /** Name of the TMDB client bean ({@code TmdbHttpClientConfiguration}). */
  static final String TMDB_CLIENT_BEAN = "tmdb";

  @Bean
  @ConditionalOnProperty(name = "poc.admission", havingValue = "adaptive-gradient2")
  FileAdmission gradient2FileAdmission(
      AdmissionRuntime runtime,
      MeterRegistry registry,
      @Value("${poc.adaptive.acquire-timeout:30m}") Duration acquireTimeout) {
    return new AdaptiveFileAdmission(
        AdaptiveLimitAlgorithm.GRADIENT2, runtime, acquireTimeout, registry);
  }

  @Bean
  @ConditionalOnProperty(name = "poc.admission", havingValue = "adaptive-vegas")
  FileAdmission vegasFileAdmission(
      AdmissionRuntime runtime,
      MeterRegistry registry,
      @Value("${poc.adaptive.acquire-timeout:30m}") Duration acquireTimeout,
      @Value("${poc.adaptive.vegas-rtt:queueing}") String vegasRtt) {
    var queueingRtt =
        switch (vegasRtt) {
          case "queueing" -> true;
          case "task" -> false;
          default ->
              throw new IllegalArgumentException(
                  "poc.adaptive.vegas-rtt must be queueing or task: " + vegasRtt);
        };
    log.info("POC adaptive-vegas round trip: {}", vegasRtt);
    return new AdaptiveFileAdmission(
        AdaptiveLimitAlgorithm.VEGAS, runtime, acquireTimeout, registry, queueingRtt);
  }

  /** Observes TMDB timeouts on the task thread; the client itself is unchanged. */
  @Bean
  static BeanPostProcessor adaptiveTmdbTimeoutObserver() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (TMDB_CLIENT_BEAN.equals(beanName) && bean instanceof HttpClient client) {
          log.info("POC adaptive admission observes TMDB timeouts on bean '{}'", beanName);
          return new TimeoutObservingHttpClient(client);
        }

        return bean;
      }
    };
  }

  /** Observes connection-pool timeouts on the task thread; the pool itself is unchanged. */
  @Bean
  static BeanPostProcessor adaptiveConnectionTimeoutObserver() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource pool) {
          log.info("POC adaptive admission observes connection timeouts on bean '{}'", beanName);
          return new ConnectionTimeoutObservingDataSource(pool);
        }

        return bean;
      }
    };
  }
}
