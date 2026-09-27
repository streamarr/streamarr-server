package com.streamarr.server.config.http;

import com.github.mizosoft.methanol.Methanol;
import com.google.common.util.concurrent.Uninterruptibles;
import com.streamarr.server.services.library.admission.TmdbTaskSignals;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Lever {@code poc.tmdb.images=separate}: paces image downloads through their own gate. Each
 * dispatched download is still counted as an image request on the shared TMDB gate's counters, so
 * {@code poc.tmdb.requests{kind=image}} and the sampler's {@code tmdb_image} column keep counting
 * every image request; the shared gate's schedule (and so {@code poc.tmdb.backlog}) no longer sees
 * them.
 */
public class PocImageGateInterceptor implements Methanol.Interceptor {

  private final PacedTmdbGate imageGate;
  private final PacedTmdbGate countingGate;
  private final boolean secondaryYields;

  public PocImageGateInterceptor(PacedTmdbGate imageGate, PacedTmdbGate countingGate) {
    this(imageGate, countingGate, false);
  }

  /** {@code secondaryYields}: secondary artwork takes only image slots required artwork leaves idle. */
  public PocImageGateInterceptor(
      PacedTmdbGate imageGate, PacedTmdbGate countingGate, boolean secondaryYields) {
    this.imageGate = imageGate;
    this.countingGate = countingGate;
    this.secondaryYields = secondaryYields;
  }

  @Override
  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
      throws IOException, InterruptedException {
    pace();
    return chain.forward(request);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
      HttpRequest request, Chain<T> chain) {
    pace();
    return chain.forwardAsync(request);
  }

  private void pace() {
    if (TmdbTaskSignals.isSecondaryArtwork()) {
      imageGate.awaitBackgroundSlot(secondaryYields);
    } else {
      var waitNanos = imageGate.reserveNanos();
      TmdbTaskSignals.markGateReservation(true, waitNanos);
      Uninterruptibles.sleepUninterruptibly(waitNanos, TimeUnit.NANOSECONDS);
    }

    imageGate.countDispatched(true);
    countingGate.countDispatched(true);
  }
}
