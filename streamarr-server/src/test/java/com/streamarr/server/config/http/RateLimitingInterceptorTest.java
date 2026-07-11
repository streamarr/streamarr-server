package com.streamarr.server.config.http;

import static org.awaitility.Awaitility.await;

import com.github.mizosoft.methanol.Methanol;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
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

    var completed = new AtomicInteger();
    Thread.startVirtualThread(
        () -> {
          for (int i = 0; i < 10; i++) {
            try {
              interceptor.intercept(request, chain);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            completed.incrementAndGet();
          }
        });

    await()
        .atLeast(Duration.ofMillis(900))
        .atMost(Duration.ofSeconds(5))
        .until(() -> completed.get() == 10);
  }
}
