package com.streamarr.server.config.http;

import com.github.mizosoft.methanol.Methanol;
import com.google.common.util.concurrent.RateLimiter;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public class RateLimitingInterceptor implements Methanol.Interceptor {

  private final RateLimiter rateLimiter;

  public RateLimitingInterceptor(double requestsPerSecond) {
    this.rateLimiter = RateLimiter.create(requestsPerSecond);
  }

  @Override
  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
      throws IOException, InterruptedException {
    rateLimiter.acquire();
    return chain.forward(request);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
      HttpRequest request, Chain<T> chain) {
    rateLimiter.acquire();
    return chain.forwardAsync(request);
  }
}
