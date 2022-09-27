package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.external.tmdb.TmdbCredits;
import com.streamarr.server.domain.external.tmdb.TmdbMovie;
import com.streamarr.server.domain.external.tmdb.TmdbSearchResults;
import com.streamarr.server.services.library.JsonBodyHandler;
import com.streamarr.server.services.parsers.video.VideoFileMetadata;
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

    public TheMovieDatabaseHttpService(
        @Value("${tmdb.api.key:}")
        String tmdbApiKey,
        Logger log
    ) {
        this.tmdbApiKey = tmdbApiKey;
        this.log = log;
    }

    public HttpResponse<TmdbSearchResults> searchForMovie(VideoFileMetadata videoFileMetadata, HttpClient client) throws IOException, InterruptedException {
        var query = new LinkedMultiValueMap<String, String>();

        query.add("query", videoFileMetadata.title());

        if (StringUtils.isNotBlank(videoFileMetadata.year())) {
            query.add("year", videoFileMetadata.year());
        }

        return searchForMovieRequest(query, client);
    }

    public HttpResponse<TmdbMovie> getMovieMetadata(String movieId, HttpClient client) throws IOException, InterruptedException {
        var uri = baseUrl().path("/movie/").path(movieId).queryParam("api_key", tmdbApiKey).build();

        var request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .build();

        return client.send(request, new JsonBodyHandler<>(TmdbMovie.class));
    }

    public void getImage(String imagePath, HttpClient client) {
        var uri = baseImageUrl().path("/original/").path(imagePath).build();

        var request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .build();
    }

    public HttpResponse<TmdbCredits> getMovieCreditsMetadata(String movieId, HttpClient client) throws IOException, InterruptedException {
        var uri = baseUrl().path("/movie/").path(movieId).path("/credits").queryParam("api_key", tmdbApiKey).build();

        var request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .build();

        return client.send(request, new JsonBodyHandler<>(TmdbCredits.class));
    }

    private HttpResponse<TmdbSearchResults> searchForMovieRequest(MultiValueMap<String, String> query, HttpClient client) throws IOException, InterruptedException {
        var uri = baseUrl().path("/search/movie").queryParams(query).queryParam("api_key", tmdbApiKey).build();

        var request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .build();


        return client.send(request, new JsonBodyHandler<>(TmdbSearchResults.class));
    }

    private void searchForShowRequest(MultiValueMap<String, String> query, HttpClient client) {
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
