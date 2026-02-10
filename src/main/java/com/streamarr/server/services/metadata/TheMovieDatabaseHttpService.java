package com.streamarr.server.services.metadata;

import com.streamarr.server.services.metadata.tmdb.TmdbApiException;
import com.streamarr.server.services.metadata.tmdb.TmdbCredits;
import com.streamarr.server.services.metadata.tmdb.TmdbFailure;
import com.streamarr.server.services.metadata.tmdb.TmdbMovie;
import com.streamarr.server.services.metadata.tmdb.TmdbSearchResults;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSearchResults;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeason;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeries;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class TheMovieDatabaseHttpService {

  private static final int MAX_RETRIES = 5;
  private static final long BASE_DELAY_MS = 2000;

  private final String tmdbApiToken;
  private final String tmdbApiBaseUrl;
  private final String tmdbImageBaseUrl;

  private final HttpClient client;
  private final ObjectMapper objectMapper;
  private final Semaphore concurrencyLimit;

  public TheMovieDatabaseHttpService(
      @Value("${tmdb.api.token:}") String tmdbApiToken,
      @Value("${tmdb.api.base-url:https://api.themoviedb.org/3}") String tmdbApiBaseUrl,
      @Value("${tmdb.image.base-url:https://image.tmdb.org/t/p/original}") String tmdbImageBaseUrl,
      @Value("${tmdb.api.max-concurrent-requests:10}") int maxConcurrentRequests,
      HttpClient client,
      ObjectMapper objectMapper) {
    if (maxConcurrentRequests <= 0) {
      throw new IllegalArgumentException(
          "tmdb.api.max-concurrent-requests must be positive, got: " + maxConcurrentRequests);
    }
    this.tmdbApiToken = tmdbApiToken;
    this.tmdbApiBaseUrl = tmdbApiBaseUrl;
    this.tmdbImageBaseUrl = tmdbImageBaseUrl;
    this.client = client;
    this.objectMapper = objectMapper;
    this.concurrencyLimit = new Semaphore(maxConcurrentRequests);
  }

  public TmdbSearchResults searchForMovie(VideoFileParserResult videoFileParserResult)
      throws IOException, InterruptedException {
    var query = new LinkedMultiValueMap<String, String>();

    query.add("query", videoFileParserResult.title());

    if (StringUtils.isNotBlank(videoFileParserResult.year())) {
      query.add("year", videoFileParserResult.year());
    }

    return searchForMovieRequest(query);
  }

  public TmdbMovie getMovieMetadata(String movieId) throws IOException, InterruptedException {
    var uri =
        baseUrl()
            .path("/movie/")
            .path(movieId)
            .queryParam("append_to_response", "credits,release_dates")
            .build();

    var request = authenticatedRequest(uri).GET().build();

    return sendWithRetry(request, TmdbMovie.class);
  }

  public TmdbCredits getMovieCreditsMetadata(String movieId)
      throws IOException, InterruptedException {
    var uri = baseUrl().path("/movie/").path(movieId).path("/credits").build();

    var request = authenticatedRequest(uri).GET().build();

    return sendWithRetry(request, TmdbCredits.class);
  }

  public TmdbTvSearchResults searchForTvSeries(VideoFileParserResult parserResult)
      throws IOException, InterruptedException {
    var query = new LinkedMultiValueMap<String, String>();

    query.add("query", parserResult.title());

    if (StringUtils.isNotBlank(parserResult.year())) {
      query.add("first_air_date_year", parserResult.year());
    }

    var uri = baseUrl().path("/search/tv").queryParams(query).build();
    var request = authenticatedRequest(uri).GET().build();

    return sendWithRetry(request, TmdbTvSearchResults.class);
  }

  public TmdbTvSeries getTvSeriesMetadata(String seriesId)
      throws IOException, InterruptedException {
    var uri =
        baseUrl()
            .path("/tv/")
            .path(seriesId)
            .queryParam("append_to_response", "content_ratings,credits,external_ids")
            .build();

    var request = authenticatedRequest(uri).GET().build();

    return sendWithRetry(request, TmdbTvSeries.class);
  }

  public byte[] downloadImage(String pathFragment) throws IOException, InterruptedException {
    var uri = URI.create(tmdbImageBaseUrl + pathFragment);
    var request = HttpRequest.newBuilder().uri(uri).GET().build();
    var response = executeWithRetry(request, HttpResponse.BodyHandlers.ofByteArray(), Set.of(429));

    if (response.statusCode() != 200) {
      throw new TmdbApiException(
          response.statusCode(), "Failed to download image: " + pathFragment);
    }

    return response.body();
  }

  public TmdbTvSeason getTvSeasonDetails(String seriesId, int seasonNumber)
      throws IOException, InterruptedException {
    var uri =
        baseUrl()
            .path("/tv/")
            .path(seriesId)
            .path("/season/")
            .path(String.valueOf(seasonNumber))
            .build();

    var request = authenticatedRequest(uri).GET().build();

    return sendWithRetry(request, TmdbTvSeason.class);
  }

  private TmdbSearchResults searchForMovieRequest(MultiValueMap<String, String> query)
      throws IOException, InterruptedException {
    var uri = baseUrl().path("/search/movie").queryParams(query).build();

    var request = authenticatedRequest(uri).GET().build();

    return sendWithRetry(request, TmdbSearchResults.class);
  }

  private <T> T sendWithRetry(HttpRequest request, Class<T> responseType)
      throws IOException, InterruptedException {
    var response = executeWithRetry(request, HttpResponse.BodyHandlers.ofString(), Set.of(429));

    if (response.statusCode() == 200) {
      return objectMapper.readValue(response.body(), responseType);
    }

    var failure = objectMapper.readValue(response.body(), TmdbFailure.class);
    throw new TmdbApiException(response.statusCode(), failure.getStatusMessage());
  }

  private <T> HttpResponse<T> executeWithRetry(
      HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler, Set<Integer> retryableStatuses)
      throws IOException, InterruptedException {
    var lastStatusCode = 0;

    concurrencyLimit.acquire();
    try {
      for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
        var response = client.send(request, bodyHandler);

        if (!retryableStatuses.contains(response.statusCode())) {
          return response;
        }

        lastStatusCode = response.statusCode();

        if (attempt == MAX_RETRIES) {
          break;
        }

        var delayMs = calculateRetryDelayMs(response, attempt);
        log.warn(
            "TMDB rate limited ({}). Retrying after {}ms (attempt {}/{})",
            lastStatusCode,
            delayMs,
            attempt + 1,
            MAX_RETRIES);
        Thread.sleep(delayMs);
      }
    } finally {
      concurrencyLimit.release();
    }

    throw new TmdbApiException(
        lastStatusCode, "TMDB retryable status persisted after " + MAX_RETRIES + " retries");
  }

  private long calculateRetryDelayMs(HttpResponse<?> response, int attempt) {
    var baseDelayMs =
        response
            .headers()
            .firstValue("Retry-After")
            .map(Long::parseLong)
            .map(seconds -> seconds * 1000)
            .orElse(BASE_DELAY_MS * (1L << attempt));
    var jitterMs = ThreadLocalRandom.current().nextLong(BASE_DELAY_MS / 2);
    return baseDelayMs + jitterMs;
  }

  private HttpRequest.Builder authenticatedRequest(URI uri) {
    return HttpRequest.newBuilder().uri(uri).header("Authorization", "Bearer " + tmdbApiToken);
  }

  private UriBuilder baseUrl() {
    return UriComponentsBuilder.fromUriString(tmdbApiBaseUrl);
  }
}
