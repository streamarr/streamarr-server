package com.streamarr.server.config;

import com.github.mizosoft.methanol.HttpCache;
import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.RetryInterceptor;
import com.github.mizosoft.methanol.RetryInterceptor.BackoffStrategy;
import com.streamarr.server.config.health.TmdbHealthProperties;
import com.streamarr.server.config.http.PacedTmdbGate;
import com.streamarr.server.config.http.RateLimitingInterceptor;
import com.streamarr.server.services.library.admission.TmdbTaskSignals;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TmdbHttpClientConfiguration {

  @Bean
  HttpCache tmdbHttpCache(@Value("${tmdb.api.cache-size-mb:200}") long cacheSizeMb) {
    return HttpCache.newBuilder().cacheOnMemory(cacheSizeMb * 1024 * 1024).build();
  }

  /** The pacing gate every TMDB API and image request passes; injectable for its backlog. */
  @Bean
  PacedTmdbGate tmdbGate(@Value("${tmdb.api.requests-per-second:35}") double requestsPerSecond) {
    return new PacedTmdbGate(requestsPerSecond, Duration.ofSeconds(1));
  }

  /**
   * Lever {@code poc.tmdb.secondary-priority}: {@code fifo} (default, today's behavior) lets
   * secondary artwork queue at the gate like core work; {@code yield} gives it only the slots core
   * work leaves idle.
   */
  public static boolean secondaryYields(String secondaryPriority) {
    return switch (secondaryPriority) {
      case "fifo" -> false;
      case "yield" -> true;
      default ->
          throw new IllegalArgumentException(
              "poc.tmdb.secondary-priority must be fifo or yield: " + secondaryPriority);
    };
  }

  @Bean("tmdb")
  HttpClient tmdbPacedHttpClient(
      PacedTmdbGate tmdbGate,
      @Value("${tmdb.image.base-url:https://image.tmdb.org/t/p/original}") String imageBaseUrl,
      @Value("${tmdb.api.request-timeout-seconds:30}") long requestTimeoutSeconds,
      @Value("${poc.tmdb.secondary-priority:fifo}") String secondaryPriority,
      HttpCache tmdbHttpCache) {
    return buildTmdbHttpClient(
        new RateLimitingInterceptor(tmdbGate, imageBaseUrl, secondaryYields(secondaryPriority)),
        tmdbGate,
        requestTimeoutSeconds,
        tmdbHttpCache);
  }

  HttpClient tmdbHttpClient(
      double requestsPerSecond, long requestTimeoutSeconds, HttpCache tmdbHttpCache) {
    var gate = new PacedTmdbGate(requestsPerSecond, Duration.ofSeconds(1));
    return buildTmdbHttpClient(
        new RateLimitingInterceptor(gate, null), gate, requestTimeoutSeconds, tmdbHttpCache);
  }

  private static HttpClient buildTmdbHttpClient(
      RateLimitingInterceptor rateLimitingInterceptor,
      PacedTmdbGate gate,
      long requestTimeoutSeconds,
      HttpCache tmdbHttpCache) {
    var retryInterceptor =
        RetryInterceptor.newBuilder()
            .maxRetries(5)
            .onStatus(429)
            .backoff(
                BackoffStrategy.retryAfterOr(
                    BackoffStrategy.exponential(Duration.ofSeconds(2), Duration.ofSeconds(32))
                        .withJitter()))
            .listener(
                new RetryInterceptor.Listener() {
                  @Override
                  public void onRetry(
                      RetryInterceptor.Context<?> context, HttpRequest nextRequest, Duration delay) {
                    gate.countRetry();
                    TmdbTaskSignals.markRetried();
                  }
                })
            .build();

    return Methanol.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(15))
        .requestTimeout(Duration.ofSeconds(requestTimeoutSeconds))
        .cache(tmdbHttpCache)
        .interceptor(retryInterceptor)
        .interceptor(rateLimitingInterceptor)
        .build();
  }

  @Bean("tmdbHealth")
  HttpClient tmdbHealthHttpClient(TmdbHealthProperties properties) {
    return Methanol.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(properties.probeTimeout())
        .requestTimeout(properties.probeTimeout())
        .build();
  }
}
