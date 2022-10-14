package com.streamarr.server.services.metadata.movie;

import com.github.mizosoft.methanol.Methanol;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TMDBMovieProvider implements MetadataProvider<Movie> {

    private final TheMovieDatabaseHttpService theMovieDatabaseHttpService;
    private final Logger log;

    @Getter
    private final ExternalAgentStrategy agentStrategy = ExternalAgentStrategy.TMDB;

    public Optional<RemoteSearchResult> search(VideoFileParserResult videoInformation, HttpClient client) {
        try {
            var searchResult = theMovieDatabaseHttpService.searchForMovie(videoInformation, client);

            if (searchResult.body().getResults().isEmpty()) {
                return Optional.empty();
            }

            var tmdbResult = searchResult.body().getResults().get(0);

            return Optional.of(RemoteSearchResult.builder()
                .externalSourceType(ExternalSourceType.TMDB)
                .externalId(String.valueOf(tmdbResult.getId()))
                .title(tmdbResult.getTitle())
                .build());

        } catch (Exception ex) {
            log.error("Failure requesting search results:", ex);
        }

        return Optional.empty();
    }

    public Optional<Movie> getMetadata(RemoteSearchResult remoteSearchResult, Library library) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // TODO: is this actually more performant compared to using the above client?
            var client = Methanol.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

            // TODO: Replace with append_to TMDB feature; credits. What about release_dates / releases?
            var movieFuture = executor.submit(() -> theMovieDatabaseHttpService.getMovieMetadata(remoteSearchResult.externalId(), client));

            var tmdbMovie = movieFuture.get().body();
            var tmdbCredits = tmdbMovie.getCredits();

            return Optional.of(Movie.builder()
                .libraryId(library.getId())
                .title(tmdbMovie.getTitle())
                .studios(tmdbMovie.getProductionCompanies().stream()
                    .map(c -> Company.builder()
                        .name(c.getName())
                        .build())
                    .collect(Collectors.toSet()))
                .cast(tmdbCredits.getCast().stream()
                    .map(credit -> Person.builder()
                        .name(credit.getName())
                        .build())
                    .collect(Collectors.toSet()))
                .build());

        } catch (Exception ex) {
            log.error("Failure enriching movie metadata using TMDB id '{}'", remoteSearchResult.externalId(), ex);
        }

        return Optional.empty();
    }

}
