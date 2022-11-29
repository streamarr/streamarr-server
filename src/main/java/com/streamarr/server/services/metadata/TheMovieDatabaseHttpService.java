package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.external.tmdb.TmdbCredits;
import com.streamarr.server.domain.external.tmdb.TmdbJsonBodyHandler;
import com.streamarr.server.domain.external.tmdb.TmdbMovie;
import com.streamarr.server.domain.external.tmdb.TmdbSearchResults;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;


@Service
public class TheMovieDatabaseHttpService {

    private final String tmdbApiKey;

    private final Logger log;

    private final HttpClient client;


    public TheMovieDatabaseHttpService(
        @Value("${tmdb.api.key:}")
        String tmdbApiKey,
        Logger log,
        HttpClient client
    ) {
        this.tmdbApiKey = tmdbApiKey;
        this.log = log;
        this.client = client;
    }

    public HttpResponse<TmdbSearchResults> searchForMovie(VideoFileParserResult videoFileParserResult) throws IOException, InterruptedException {
        var query = new LinkedMultiValueMap<String, String>();

        query.add("query", videoFileParserResult.title());

        if (StringUtils.isNotBlank(videoFileParserResult.year())) {
            query.add("year", videoFileParserResult.year());
        }

        return searchForMovieRequest(query);
    }

    public HttpResponse<TmdbMovie> getMovieMetadata(String movieId) throws IOException, InterruptedException {
        var uri = baseUrl()
            .path("/movie/")
            .path(movieId)
            .queryParam("api_key", tmdbApiKey)
            .queryParam("append_to_response", "credits")
            .build();

        var request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .build();

        return client.send(request, new TmdbJsonBodyHandler<>(TmdbMovie.class));
    }

    public void getImage(String imagePath) {
        var uri = baseImageUrl().path("/original/").path(imagePath).build();

        var request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .build();
    }

    public HttpResponse<TmdbCredits> getMovieCreditsMetadata(String movieId) throws IOException, InterruptedException {
        var uri = baseUrl().path("/movie/").path(movieId).path("/credits").queryParam("api_key", tmdbApiKey).build();

        var request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .build();

        return client.send(request, new TmdbJsonBodyHandler<>(TmdbCredits.class));
    }

    private HttpResponse<TmdbSearchResults> searchForMovieRequest(MultiValueMap<String, String> query) throws IOException, InterruptedException {
        var uri = baseUrl().path("/search/movie").queryParams(query).queryParam("api_key", tmdbApiKey).build();

        var request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .build();

        return client.send(request, new TmdbJsonBodyHandler<>(TmdbSearchResults.class));
    }

    private void searchForShowRequest(MultiValueMap<String, String> query) {
        var uri = baseUrl().path("/search/tv").queryParams(query).queryParam("api_key", tmdbApiKey).build();

        var request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .build();
    }

    private UriBuilder baseUrl() {
        return UriComponentsBuilder.fromHttpUrl("https://api.themoviedb.org/3");
    }

    private UriBuilder baseImageUrl() {
        return UriComponentsBuilder.fromHttpUrl("https://image.tmdb.org/t/p");
    }
}
