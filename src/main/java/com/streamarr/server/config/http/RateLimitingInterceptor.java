package com.streamarr.server.config.http;

import com.github.mizosoft.methanol.Methanol;
import com.google.common.util.concurrent.Uninterruptibles;
import com.streamarr.server.services.library.admission.TmdbTaskSignals;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Paces every TMDB request through a {@link PacedTmdbGate}. The wait is uninterruptible and runs
 * on the calling thread for both sync and async sends, as Guava's {@code RateLimiter.acquire()}
 * did.
 *
 * <p>Requests made by secondary artwork ({@link TmdbTaskSignals#isSecondaryArtwork()}) reserve as
 * background work: FIFO like every request unless {@code secondaryYields} (lever {@code
 * poc.tmdb.secondary-priority=yield}), in which case they take only slots core work leaves idle.
 */
public class RateLimitingInterceptor implements Methanol.Interceptor {

  private final PacedTmdbGate gate;
  private final String imageBaseUrl;
  private final boolean secondaryYields;

  public RateLimitingInterceptor(double requestsPerSecond) {
    this(new PacedTmdbGate(requestsPerSecond, Duration.ofSeconds(1)), null);
  }

  /**
   * @param imageBaseUrl requests whose URI starts with it count as image requests; null counts
   *     every request as an API request
   */
  public RateLimitingInterceptor(PacedTmdbGate gate, String imageBaseUrl) {
    this(gate, imageBaseUrl, false);
  }

  public RateLimitingInterceptor(PacedTmdbGate gate, String imageBaseUrl, boolean secondaryYields) {
    this.gate = gate;
    this.imageBaseUrl = imageBaseUrl;
    this.secondaryYields = secondaryYields;
  }

  @Override
  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
      throws IOException, InterruptedException {
    pace(request);
    return chain.forward(request);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
      HttpRequest request, Chain<T> chain) {
    pace(request);
    return chain.forwardAsync(request);
  }

  private void pace(HttpRequest request) {
    var image = imageBaseUrl != null && request.uri().toString().startsWith(imageBaseUrl);
    if (TmdbTaskSignals.isSecondaryArtwork()) {
      gate.awaitBackgroundSlot(secondaryYields);
      gate.countDispatched(image);
      return;
    }

    var waitNanos = gate.reserveNanos();
    TmdbTaskSignals.markGateReservation(image, waitNanos);
    Uninterruptibles.sleepUninterruptibly(waitNanos, TimeUnit.NANOSECONDS);
    gate.countDispatched(image);
  }
}
