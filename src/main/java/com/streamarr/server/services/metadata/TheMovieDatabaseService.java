package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.external.tmdb.TmdbMovie;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.repositories.MovieRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;


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

    public TmdbMovie waitAndPrintMovieMetadata() {
        var movie = getMovieMetadata("122906").block();

        // What about system actions? Like this will be...
        var fakeUserId = UUID.randomUUID();

        // This will NOT work without a cascade clause. Will these be separate calls? Do I want to manage them alone?
        Set<Company> companies = movie.getProductionCompanies().stream().map(company ->
            Company.builder()
                .name(company.getName())
                .createdBy(fakeUserId)
                .build())
            .collect(Collectors.toSet());

        movieRepository.save(Movie.builder()
            .artwork(movie.getPosterPath())
            .createdBy(fakeUserId)
            .contentRating(String.valueOf(movie.getVoteAverage()))
            .studios(companies)
            .build());

        return movie;
    }

    private Mono<TmdbMovie> getMovieMetadata(String movieId) {
        return getWebClient()
            .get()
            .uri("/movie/{movieId}?api_key={apiKey}&language=en-US", movieId, tmdbApiKey)
            .retrieve()
            .bodyToMono(TmdbMovie.class);
    }

    private WebClient getWebClient() {
        return webClientBuilder
            .baseUrl("https://api.themoviedb.org/3")
            .build();
    }
}
