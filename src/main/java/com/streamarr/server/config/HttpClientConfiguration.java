package com.streamarr.server.config;

import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.RetryInterceptor;
import com.github.mizosoft.methanol.RetryInterceptor.BackoffStrategy;
import com.streamarr.server.config.http.RateLimitingInterceptor;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfiguration {

  @Bean
  HttpClient httpClient(
      @Value("${tmdb.api.requests-per-second:35}") double requestsPerSecond) {
    var retryInterceptor =
        RetryInterceptor.newBuilder()
            .maxRetries(5)
            .onStatus(429)
            .backoff(
                BackoffStrategy.retryAfterOr(
                    BackoffStrategy.exponential(Duration.ofSeconds(2), Duration.ofSeconds(32))
                        .withJitter()))
            .build();

    var rateLimitingInterceptor = new RateLimitingInterceptor(requestsPerSecond);

    return Methanol.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(15))
        .interceptor(retryInterceptor)
        .interceptor(rateLimitingInterceptor)
        .build();
  }
}
