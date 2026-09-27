package com.streamarr.server.config;

import com.github.mizosoft.methanol.Methanol;
import com.streamarr.server.config.http.RateLimitingInterceptor;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Lever {@code poc.tmdb.limiter-position=backend}: rebuilds the {@code tmdb} client with its TMDB
 * gate registered as a backend interceptor instead of a client interceptor. Everything else is
 * copied from the client {@link TmdbHttpClientConfiguration} built, so the two positions differ in
 * the gate's slot only.
 *
 * <p>Methanol calls client interceptors, then request decoration, then the HTTP cache, then backend
 * interceptors, then the network. A backend gate therefore spends no permit on a request the cache
 * answers, and still spends one per network attempt: the retry interceptor stays a client
 * interceptor, so each 429 retry goes back through the cache and the gate. A synchronous send runs
 * the whole chain on the caller's thread, so the per-task TMDB signals still reach the task.
 *
 * <p>Runs before every unordered post-processor, so it sees the Methanol client before any wrapper
 * (for example the adaptive variants' timeout observer) replaces it.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "poc.tmdb.limiter-position", havingValue = "backend")
public class PocTmdbLimiterPosition implements BeanPostProcessor, Ordered {

  static final String TMDB_CLIENT = "tmdb";

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (!TMDB_CLIENT.equals(beanName)) {
      return bean;
    }

    if (!(bean instanceof Methanol client)) {
      throw new IllegalStateException(
          "poc.tmdb.limiter-position=backend expects the 'tmdb' bean to be a Methanol client, got "
              + bean.getClass().getName());
    }

    var rebuilt = withGateAsBackendInterceptor(client);
    client.close();
    log.info(
        "POC TMDB gate position: backend (client interceptors {}, backend interceptors {}).",
        rebuilt.interceptors(),
        rebuilt.backendInterceptors());
    return rebuilt;
  }

  /** Returns a copy of {@code client} whose TMDB gate runs as a backend interceptor. */
  static Methanol withGateAsBackendInterceptor(Methanol client) {
    if (client.caches().size() > 1) {
      throw new IllegalStateException("Expected at most one HTTP cache on the TMDB client");
    }

    var builder =
        Methanol.newBuilder()
            .version(client.version())
            .followRedirects(client.followRedirects())
            .autoAcceptEncoding(client.autoAcceptEncoding());
    client.connectTimeout().ifPresent(builder::connectTimeout);
    client.requestTimeout().ifPresent(builder::requestTimeout);
    client.headersTimeout().ifPresent(builder::headersTimeout);
    client.readTimeout().ifPresent(builder::readTimeout);
    client.userAgent().ifPresent(builder::userAgent);
    client.baseUri().ifPresent(builder::baseUri);
    client
        .defaultHeaders()
        .map()
        .forEach((name, values) -> values.forEach(value -> builder.defaultHeader(name, value)));
    client.cache().ifPresent(builder::cache);

    var gates = new ArrayList<Methanol.Interceptor>();
    for (var interceptor : client.interceptors()) {
      if (interceptor instanceof RateLimitingInterceptor) {
        gates.add(interceptor);
        continue;
      }

      builder.interceptor(interceptor);
    }

    if (gates.size() != 1) {
      throw new IllegalStateException(
          "Expected exactly one TMDB gate among the client interceptors, found " + gates.size());
    }

    client.backendInterceptors().forEach(builder::backendInterceptor);
    gates.forEach(builder::backendInterceptor);
    return builder.build();
  }
}
