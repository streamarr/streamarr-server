package com.streamarr.server.config.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.Methanol;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("RateLimitingInterceptor Unit Tests")
class RateLimitingInterceptorTest {

  @Test
  @DisplayName("Should throttle requests to the configured rate")
  @SuppressWarnings("unchecked")
  void shouldThrottleRequestsToConfiguredRate() throws Exception {
    var rate = 10.0;
    var interceptor = new RateLimitingInterceptor(rate);
    var request = HttpRequest.newBuilder().uri(URI.create("http://example.com")).build();

    var chain = Mockito.mock(Methanol.Interceptor.Chain.class);
    var response = Mockito.mock(HttpResponse.class);
    Mockito.when(chain.forward(Mockito.any())).thenReturn(response);

    // Exhaust burst capacity so that subsequent calls are fully governed by the
    // token-bucket rate
    for (int i = 0; i < (int) rate + 2; i++) {
      interceptor.intercept(request, chain);
    }

    var start = System.nanoTime();
    for (int i = 0; i < 10; i++) {
      interceptor.intercept(request, chain);
    }
    var elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(elapsed).isGreaterThan(Duration.ofMillis(900));
  }
}
