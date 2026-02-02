package com.streamarr.server.services.metadata.movie;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.metadata.tmdb.TmdbCredits;
import com.streamarr.server.services.metadata.tmdb.TmdbMovie;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.utils.TitleSortUtil;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TMDBMovieProvider implements MetadataProvider<Movie> {

  private final TheMovieDatabaseHttpService theMovieDatabaseHttpService;

  @Getter private final ExternalAgentStrategy agentStrategy = ExternalAgentStrategy.TMDB;

  public Optional<RemoteSearchResult> search(VideoFileParserResult videoInformation) {
    try {
      var searchResult = theMovieDatabaseHttpService.searchForMovie(videoInformation);

      if (searchResult.getResults().isEmpty()) {
        return Optional.empty();
      }

      var tmdbResult = searchResult.getResults().get(0);

      return Optional.of(
          RemoteSearchResult.builder()
              .externalSourceType(ExternalSourceType.TMDB)
              .externalId(String.valueOf(tmdbResult.getId()))
              .title(tmdbResult.getTitle())
              .build());

    } catch (IOException ex) {
      log.error("Failure requesting search results:", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("Search interrupted:", ex);
    }

    return Optional.empty();
  }

  public Optional<Movie> getMetadata(RemoteSearchResult remoteSearchResult, Library library) {
    try {
      var tmdbMovie = theMovieDatabaseHttpService.getMovieMetadata(remoteSearchResult.externalId());

      var credits = Optional.ofNullable(tmdbMovie.getCredits());
      var castList = credits.map(TmdbCredits::getCast).orElse(Collections.emptyList());
      var crewList = credits.map(TmdbCredits::getCrew).orElse(Collections.emptyList());
      var releaseDateResults = Optional.ofNullable(tmdbMovie.getReleaseDates());
      var productionCompanies =
          Optional.ofNullable(tmdbMovie.getProductionCompanies()).orElse(Collections.emptyList());
      var tmdbGenres = Optional.ofNullable(tmdbMovie.getGenres()).orElse(Collections.emptyList());

      var movieRating =
          releaseDateResults.map(r -> r.getResults()).orElse(Collections.emptyList()).stream()
              .filter(info -> "US".equals(info.getIso31661()))
              .flatMap(info -> info.getReleaseDates().stream())
              .filter(release -> StringUtils.isNotBlank(release.getCertification()))
              .findFirst();

      var movieBuilder =
          Movie.builder()
              .library(library)
              .title(tmdbMovie.getTitle())
              .externalIds(mapExternalIds(tmdbMovie))
              .tagline(tmdbMovie.getTagline())
              .summary(tmdbMovie.getOverview())
              .backdropPath(tmdbMovie.getBackdropPath())
              .posterPath(tmdbMovie.getPosterPath())
              .runtime(tmdbMovie.getRuntime())
              .originalTitle(tmdbMovie.getOriginalTitle())
              .titleSort(TitleSortUtil.computeTitleSort(tmdbMovie.getTitle()))
              .studios(
                  productionCompanies.stream()
                      .map(
                          c ->
                              Company.builder()
                                  .sourceId(String.valueOf(c.getId()))
                                  .name(c.getName())
                                  .logoPath(c.getLogoPath())
                                  .build())
                      .collect(Collectors.toSet()))
              .cast(
                  castList.stream()
                      .map(
                          credit ->
                              Person.builder()
                                  .sourceId(String.valueOf(credit.getId()))
                                  .name(credit.getName())
                                  .profilePath(credit.getProfilePath())
                                  .build())
                      .collect(Collectors.toList()))
              .directors(
                  crewList.stream()
                      .filter(crew -> "Director".equals(crew.getJob()))
                      .map(
                          crew ->
                              Person.builder()
                                  .sourceId(String.valueOf(crew.getId()))
                                  .name(crew.getName())
                                  .profilePath(crew.getProfilePath())
                                  .build())
                      .collect(Collectors.toList()))
              .genres(
                  tmdbGenres.stream()
                      .map(
                          g ->
                              Genre.builder()
                                  .sourceId(String.valueOf(g.getId()))
                                  .name(g.getName())
                                  .build())
                      .collect(Collectors.toSet()));

      if (StringUtils.isNotBlank(tmdbMovie.getReleaseDate())) {
        movieBuilder.releaseDate(LocalDate.parse(tmdbMovie.getReleaseDate()));
      }

      movieRating.ifPresent(
          movieRelease ->
              movieBuilder.contentRating(
                  new ContentRating("MPAA", movieRelease.getCertification(), "US")));

      return Optional.of(movieBuilder.build());

    } catch (IOException ex) {
      log.error(
          "Failure enriching movie metadata using TMDB id '{}'",
          remoteSearchResult.externalId(),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error(
          "Metadata enrichment interrupted for TMDB id '{}'", remoteSearchResult.externalId(), ex);
    }

    return Optional.empty();
  }

  private Set<ExternalIdentifier> mapExternalIds(TmdbMovie tmdbMovie) {

    var externalIdSet = new HashSet<ExternalIdentifier>();

    externalIdSet.add(
        ExternalIdentifier.builder()
            .externalSourceType(ExternalSourceType.TMDB)
            .externalId(String.valueOf(tmdbMovie.getId()))
            .build());

    if (StringUtils.isNotBlank(tmdbMovie.getImdbId())) {
      externalIdSet.add(
          ExternalIdentifier.builder()
              .externalSourceType(ExternalSourceType.IMDB)
              .externalId(tmdbMovie.getImdbId())
              .build());
    }

    return externalIdSet;
  }
}
