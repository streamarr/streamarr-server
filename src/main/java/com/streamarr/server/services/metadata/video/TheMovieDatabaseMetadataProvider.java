package com.streamarr.server.services.metadata.video;

import com.github.mizosoft.methanol.Methanol;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.parsers.video.VideoFileMetadata;
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
public class TheMovieDatabaseMetadataProvider implements MovieMetadataProvider {

    private final TheMovieDatabaseHttpService theMovieDatabaseHttpService;
    private final Logger log;

    public Optional<String> searchForMovie(VideoFileMetadata videoInformation, HttpClient client) {
        try {
            var searchResult = theMovieDatabaseHttpService.searchForMovie(videoInformation, client);


            if (searchResult.body().getResults().isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(String.valueOf(searchResult.body().getResults().get(0).getId()));

        } catch (Exception ex) {
            log.error("Failure requesting search results:", ex);
        }

        return Optional.empty();
    }

    public Optional<Movie> buildEnrichedMovie(Library library, String externalId) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // TODO: is this actually more performant compared to using the above client?
            var client = Methanol.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

            var movieFuture = executor.submit(() -> theMovieDatabaseHttpService.getMovieMetadata(externalId, client));
            var creditsFuture = executor.submit(() -> theMovieDatabaseHttpService.getMovieCreditsMetadata(externalId, client));

            var tmdbMovie = movieFuture.get().body();
            var tmdbCredits = creditsFuture.get().body();

            return Optional.of(Movie.builder()
                .libraryId(library.getId())
                .tmdbId(String.valueOf(tmdbMovie.getId()))
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
            log.error("Failure enriching movie metadata using TMDB id '{}'", externalId, ex);
        }

        return Optional.empty();
    }

}
