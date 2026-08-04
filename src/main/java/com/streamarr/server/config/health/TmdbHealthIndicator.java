package com.streamarr.server.config.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TmdbHealthIndicator implements HealthIndicator {

  private static final URI CONFIGURATION_URI =
      URI.create("https://api.themoviedb.org/3/configuration");

  // Not DOWN: an unreachable TMDB degrades metadata enrichment while playback, auth and browsing
  // keep serving. management.endpoint.health.status.order ranks DEGRADED below UP so it cannot
  // outrank an UP contributor in the aggregate, and the metadata health group surfaces it alone.
  static final Status DEGRADED = new Status("DEGRADED", "TMDB metadata service is unavailable");

  @Qualifier("tmdbHealth")
  private final HttpClient client;

  private final TmdbHealthProperties properties;
  private final Clock clock;

  private final AtomicReference<CachedProbe> lastProbe = new AtomicReference<>(CachedProbe.stale());

  @Override
  public Health health() {
    var now = clock.instant();
    var cached = lastProbe.get();

    if (cached.isFreshAt(now)) {
      return cached.health();
    }

    var result = probe();
    if (result.cacheable()) {
      lastProbe.compareAndSet(
          cached, new CachedProbe(result.health(), clock.instant().plus(properties.cacheTtl())));
    }

    return result.health();
  }

  private ProbeResult probe() {
    try {
      var response = client.send(probeRequest(), HttpResponse.BodyHandlers.discarding());

      return new ProbeResult(healthFor(response.statusCode()), true);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("TMDB health check interrupted", ex);
      return new ProbeResult(degraded(ex), false);
    } catch (Exception ex) {
      log.warn("TMDB health check failed", ex);
      return new ProbeResult(degraded(ex), true);
    }
  }

  private HttpRequest probeRequest() {
    // Keep the request budget explicit even though the dedicated client carries the same timeout.
    return HttpRequest.newBuilder()
        .uri(CONFIGURATION_URI)
        .timeout(properties.probeTimeout())
        .GET()
        .build();
  }

  private Health healthFor(int statusCode) {
    // 401 proves reachability: the probe carries no API token, so TMDB rejects it while still
    // answering.
    if (statusCode == 200 || statusCode == 401) {
      return Health.up().withDetail("api", "reachable").build();
    }

    log.warn("TMDB health check returned HTTP status {}", statusCode);
    return Health.status(DEGRADED).withDetail("statusCode", statusCode).build();
  }

  private Health degraded(Exception ex) {
    return Health.status(DEGRADED).withException(ex).build();
  }

  private record ProbeResult(Health health, boolean cacheable) {}

  // Deliberately unsynchronized: concurrent cache misses may probe in parallel. Optimistic cache
  // publication lets the first completed probe win without holding coordination across I/O.
  private record CachedProbe(Health health, Instant expiresAt) {

    private static CachedProbe stale() {
      return new CachedProbe(Health.unknown().build(), Instant.MIN);
    }

    private boolean isFreshAt(Instant now) {
      return now.isBefore(expiresAt);
    }
  }
}
