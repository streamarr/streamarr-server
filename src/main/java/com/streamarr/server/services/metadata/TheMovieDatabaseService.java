package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.external.tmdb.TmdbSearchResults;
import com.streamarr.server.repositories.movie.MovieRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;


@Service
public class TheMovieDatabaseService {

    private final WebClient.Builder webClientBuilder;
    private final MovieRepository movieRepository;
    private final String tmdbApiKey;

    public TheMovieDatabaseService(
        WebClient.Builder webClientBuilder,
        MovieRepository movieRepository,
        @Value("${tmdb.api.key:}") String tmdbApiKey
    ) {
        this.webClientBuilder = webClientBuilder;
        this.movieRepository = movieRepository;
        this.tmdbApiKey = tmdbApiKey;
    }

    public TmdbSearchResults searchAndWait(String title, String year) {
        var results = getMovieMetadata(title, year).block();

        return results;
    }

    private Mono<TmdbSearchResults> getMovieMetadata(String title, String year) {
        return getWebClient()
            .get()
            .uri("/search/movie?query={title}&year={year}&api_key={apiKey}&language=en-US", title, year, tmdbApiKey)
            .retrieve()
            .bodyToMono(TmdbSearchResults.class);
    }

    private WebClient getWebClient() {
        return webClientBuilder
            .baseUrl("https://api.themoviedb.org/3")
            .build();
    }
}
