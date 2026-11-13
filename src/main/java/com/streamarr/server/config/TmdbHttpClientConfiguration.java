package com.streamarr.server.config;

import com.github.mizosoft.methanol.HttpCache;
import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.RetryInterceptor;
import com.github.mizosoft.methanol.RetryInterceptor.BackoffStrategy;
import com.streamarr.server.config.http.RateLimitingInterceptor;
import com.streamarr.server.properties.TmdbHealthProperties;
import com.streamarr.server.services.concurrency.AdmittedTaskRetries;
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

  @Bean("tmdb")
  HttpClient tmdbHttpClient(
      @Value("${tmdb.api.requests-per-second:40}") double requestsPerSecond,
      @Value("${tmdb.api.request-timeout-seconds:30}") long requestTimeoutSeconds,
      HttpCache tmdbHttpCache) {
    return retryingTmdbClient(requestTimeoutSeconds)
        .version(HttpClient.Version.HTTP_1_1)
        .cache(tmdbHttpCache)
        .interceptor(new RateLimitingInterceptor(requestsPerSecond))
        .build();
  }

  // The image host limits simultaneous connections, not requests: downloads spend none of the API's
  // request rate, and HTTP/2 lets concurrent downloads share a connection once one is open.
  @Bean("tmdbImage")
  HttpClient tmdbImageHttpClient(
      @Value("${tmdb.api.request-timeout-seconds:30}") long requestTimeoutSeconds) {
    return retryingTmdbClient(requestTimeoutSeconds).version(HttpClient.Version.HTTP_2).build();
  }

  // Interceptors added after the retry run again on each retried attempt.
  private static Methanol.Builder retryingTmdbClient(long requestTimeoutSeconds) {
    var retryInterceptor =
        RetryInterceptor.newBuilder()
            .maxRetries(5)
            .onStatus(429)
            .backoff(
                BackoffStrategy.retryAfterOr(
                    BackoffStrategy.exponential(Duration.ofSeconds(2), Duration.ofSeconds(32))
                        .withJitter()))
            .listener(new AdmittedTaskRetryListener())
            .build();

    return Methanol.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .requestTimeout(Duration.ofSeconds(requestTimeoutSeconds))
        .interceptor(retryInterceptor);
  }

  // TMDB requests run on the admitted task's own thread, so the task sees the mark (ADR 0041).
  private static final class AdmittedTaskRetryListener implements RetryInterceptor.Listener {

    @Override
    public void onRetry(
        RetryInterceptor.Context<?> context, HttpRequest nextRequest, Duration delay) {
      AdmittedTaskRetries.markRetried();
    }
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
