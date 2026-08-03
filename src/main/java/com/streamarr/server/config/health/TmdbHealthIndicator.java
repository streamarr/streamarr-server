package com.streamarr.server.config.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TmdbHealthIndicator implements HealthIndicator {

  private static final URI CONFIGURATION_URI =
      URI.create("https://api.themoviedb.org/3/configuration");

  @Qualifier("tmdb")
  private final HttpClient client;

  @Override
  public Health health() {
    return probe();
  }

  private Health probe() {
    try {
      var response = client.send(probeRequest(), HttpResponse.BodyHandlers.discarding());

      return healthFor(response.statusCode());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("TMDB health check interrupted", ex);
      return Health.down().withException(ex).build();
    } catch (Exception ex) {
      log.warn("TMDB health check failed", ex);
      return Health.down().withException(ex).build();
    }
  }

  private HttpRequest probeRequest() {
    return HttpRequest.newBuilder().uri(CONFIGURATION_URI).GET().build();
  }

  private Health healthFor(int statusCode) {
    // 401 proves reachability: the probe carries no API token, so TMDB rejects it while still
    // answering.
    if (statusCode == 200 || statusCode == 401) {
      return Health.up().withDetail("api", "reachable").build();
    }

    return Health.down().withDetail("statusCode", statusCode).build();
  }
}
