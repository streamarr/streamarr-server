package com.streamarr.server.config.http;

import com.github.mizosoft.methanol.CacheControl;
import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.RetryInterceptor;
import com.streamarr.server.config.TmdbHttpClientConfiguration;
import com.streamarr.server.services.metadata.TmdbImageDownloader;
import com.streamarr.server.services.metadata.tmdb.TmdbApiException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Lever {@code poc.tmdb.images=separate}: TMDB image downloads use their own HTTP client and their
 * own gate at {@code poc.tmdb.images.requests-per-second}, so artwork stops queueing behind API
 * calls in the shared gate (and API calls behind artwork). Replaces the TMDB service as the artwork
 * fetcher's {@link TmdbImageDownloader}; the request it sends is the one the service sends.
 *
 * <p>The client copies the {@code tmdb} client's timeouts and reuses its retry interceptor instance
 * (same 429 policy, same retry counting). It has no HTTP cache: image requests are {@code no-store}
 * anyway. The gate follows {@code poc.tmdb.limiter-position}.
 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(name = "poc.tmdb.images", havingValue = "separate")
public class PocSeparateImageDownloader implements TmdbImageDownloader, AutoCloseable {

  private static final CacheControl NO_STORE = CacheControl.newBuilder().noStore().build();

  private final String imageBaseUrl;
  private final Methanol client;

  public PocSeparateImageDownloader(
      @Qualifier("tmdb") HttpClient tmdbClient,
      PacedTmdbGate sharedGate,
      @Value("${tmdb.image.base-url:https://image.tmdb.org/t/p/original}") String imageBaseUrl,
      @Value("${poc.tmdb.images.requests-per-second:35}") double imageRequestsPerSecond,
      @Value("${poc.tmdb.limiter-position:client}") String limiterPosition,
      @Value("${poc.tmdb.secondary-priority:fifo}") String secondaryPriority,
      MeterRegistry registry) {
    this.imageBaseUrl = imageBaseUrl;
    var imageGate = new PacedTmdbGate(imageRequestsPerSecond, Duration.ofSeconds(1));
    this.client =
        buildImageClient(
            (Methanol) PocDelegatingHttpClient.unwrap(tmdbClient),
            new PocImageGateInterceptor(
                imageGate, sharedGate, TmdbHttpClientConfiguration.secondaryYields(secondaryPriority)),
            "backend".equals(limiterPosition));

    Gauge.builder("poc.tmdb.image.backlog", imageGate, gate -> gate.backlogNanos() / 1e6)
        .baseUnit("milliseconds")
        .description("Queued image gate schedule when images use their own gate")
        .register(registry);
    log.info(
        "POC TMDB images: separate client, own gate at {}/s as a {} interceptor.",
        imageRequestsPerSecond,
        "backend".equals(limiterPosition) ? "backend" : "client");
  }

  static Methanol buildImageClient(
      Methanol tmdbClient, PocImageGateInterceptor gate, boolean gateAsBackend) {
    var retries =
        tmdbClient.interceptors().stream().filter(RetryInterceptor.class::isInstance).toList();
    if (retries.size() != 1) {
      throw new IllegalStateException(
          "Expected exactly one retry interceptor on the TMDB client, found " + retries.size());
    }

    var builder = Methanol.newBuilder().version(tmdbClient.version());
    tmdbClient.connectTimeout().ifPresent(builder::connectTimeout);
    tmdbClient.requestTimeout().ifPresent(builder::requestTimeout);
    builder.interceptor(retries.getFirst());
    if (gateAsBackend) {
      builder.backendInterceptor(gate);
    } else {
      builder.interceptor(gate);
    }

    return builder.build();
  }

  @Override
  public byte[] downloadImage(String pathFragment) throws IOException, InterruptedException {
    var uri = URI.create(imageBaseUrl + pathFragment);
    var request = MutableRequest.GET(uri).cacheControl(NO_STORE);
    var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

    if (response.statusCode() != 200) {
      throw new TmdbApiException(
          response.statusCode(), "Failed to download image: " + pathFragment);
    }

    return response.body();
  }

  @Override
  public void close() {
    client.close();
  }
}
