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

  private static final int MAX_RETRIES = 3;
  private static final long BASE_DELAY_MS = 1000;

  private final String tmdbApiToken;
  private final String tmdbApiBaseUrl;

  private final HttpClient client;
  private final ObjectMapper objectMapper;

  public TheMovieDatabaseHttpService(
      @Value("${tmdb.api.token:}") String tmdbApiToken,
      @Value("${tmdb.api.base-url:https://api.themoviedb.org/3}") String tmdbApiBaseUrl,
      HttpClient client,
      ObjectMapper objectMapper) {
    this.tmdbApiToken = tmdbApiToken;
    this.tmdbApiBaseUrl = tmdbApiBaseUrl;
    this.client = client;
    this.objectMapper = objectMapper;
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
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        return objectMapper.readValue(response.body(), responseType);
      }

      if (response.statusCode() != 429) {
        var failure = objectMapper.readValue(response.body(), TmdbFailure.class);
        throw new TmdbApiException(response.statusCode(), failure.getStatusMessage());
      }

      if (attempt < MAX_RETRIES) {
        var delaySeconds =
            response
                .headers()
                .firstValue("Retry-After")
                .map(Long::parseLong)
                .orElse(BASE_DELAY_MS * (1L << attempt) / 1000);

        log.warn(
            "TMDB rate limited (429). Retrying after {}s (attempt {}/{})",
            delaySeconds,
            attempt + 1,
            MAX_RETRIES);

        Thread.sleep(delaySeconds * 1000);
      }
    }

    throw new TmdbApiException(429, "TMDB rate limit exceeded after " + MAX_RETRIES + " retries");
  }

  private HttpRequest.Builder authenticatedRequest(URI uri) {
    return HttpRequest.newBuilder().uri(uri).header("Authorization", "Bearer " + tmdbApiToken);
  }

  private UriBuilder baseUrl() {
    return UriComponentsBuilder.fromUriString(tmdbApiBaseUrl);
  }
}
