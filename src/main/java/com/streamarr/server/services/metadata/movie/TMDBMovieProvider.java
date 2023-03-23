package com.streamarr.server.services.metadata.movie;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.external.tmdb.TmdbMovie;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TMDBMovieProvider implements MetadataProvider<Movie> {

    private final TheMovieDatabaseHttpService theMovieDatabaseHttpService;
    private final Logger log;

    @Getter
    private final ExternalAgentStrategy agentStrategy = ExternalAgentStrategy.TMDB;

    public Optional<RemoteSearchResult> search(VideoFileParserResult videoInformation) {
        try {
            var searchResult = theMovieDatabaseHttpService.searchForMovie(videoInformation);

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
        // TODO: Should we be using an executor here?
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            var movieFuture = executor.submit(() -> theMovieDatabaseHttpService.getMovieMetadata(remoteSearchResult.externalId()));

            var tmdbMovie = movieFuture.get().body();

            // TODO: null & empty list check
            var tmdbCredits = tmdbMovie.getCredits();
            // TODO: null & empty list check
            var tmdbReleases = tmdbMovie.getReleases();

            var movieRating = tmdbReleases.getCountries().stream()
                .filter(release -> StringUtils.isNotBlank(release.getCertification()))
                // TODO: Support other country codes.
                .filter(release -> release.getIso31661().equals("US"))
                .findFirst();

            // TODO: Saving people and cast at this point will create duplicates.
            var movieBuilder = Movie.builder()
                .libraryId(library.getId())
                .title(tmdbMovie.getTitle())
                .externalIds(mapExternalIds(tmdbMovie))
                .tagline(tmdbMovie.getTagline())
                .summary(tmdbMovie.getOverview())
                .releaseDate(LocalDate.parse(tmdbMovie.getReleaseDate()))
                .studios(tmdbMovie.getProductionCompanies().stream()
                    .map(c -> Company.builder()
                        .sourceId(String.valueOf(c.getId()))
                        .name(c.getName())
                        .build())
                    .collect(Collectors.toSet()))
                // TODO: What happens if no results? null / empty list?
                .cast(tmdbCredits.getCast().stream()
                    .map(credit -> Person.builder()
                        .sourceId(String.valueOf(credit.getId()))
                        .name(credit.getName())
                        .build())
                    .collect(Collectors.toSet()));

            // TODO: is this the cleanest way?
            movieRating.ifPresent(movieRelease -> movieBuilder.contentRating(movieRelease.getCertification()));

            return Optional.of(movieBuilder.build());

        } catch (Exception ex) {
            log.error("Failure enriching movie metadata using TMDB id '{}'", remoteSearchResult.externalId(), ex);
        }

        return Optional.empty();
    }

    private Set<ExternalIdentifier> mapExternalIds(TmdbMovie tmdbMove) {

        var externalIdSet = new HashSet<ExternalIdentifier>();

        externalIdSet.add(ExternalIdentifier.builder()
            .externalSourceType(ExternalSourceType.TMDB)
            .externalId(String.valueOf(tmdbMove.getId()))
            .build()
        );

        if (StringUtils.isNotBlank(tmdbMove.getImdbId())) {
            externalIdSet.add(ExternalIdentifier.builder()
                .externalSourceType(ExternalSourceType.IMDB)
                .externalId(tmdbMove.getImdbId())
                .build());
        }

        return externalIdSet;

    }

}
