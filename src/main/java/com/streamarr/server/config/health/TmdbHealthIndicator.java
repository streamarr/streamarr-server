package com.streamarr.server.config.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TmdbHealthIndicator implements HealthIndicator {

  private final HttpClient client;

  @Override
  public Health health() {
    try {
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://api.themoviedb.org/3/configuration"))
              .GET()
              .build();

      var response = client.send(request, HttpResponse.BodyHandlers.discarding());

      if (response.statusCode() == 200 || response.statusCode() == 401) {
        return Health.up().withDetail("api", "reachable").build();
      }

      return Health.down().withDetail("statusCode", response.statusCode()).build();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("TMDB health check interrupted", ex);
      return Health.down().withException(ex).build();
    } catch (Exception ex) {
      log.warn("TMDB health check failed", ex);
      return Health.down().withException(ex).build();
    }
  }
}
