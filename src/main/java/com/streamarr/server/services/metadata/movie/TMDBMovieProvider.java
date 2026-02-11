package com.streamarr.server.services.metadata.movie;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.metadata.TmdbMetadataMapper;
import com.streamarr.server.services.metadata.tmdb.TmdbCredits;
import com.streamarr.server.services.metadata.tmdb.TmdbMovie;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.utils.TitleSortUtil;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

@Slf4j
@Service
@RequiredArgsConstructor
public class TMDBMovieProvider implements MetadataProvider<Movie> {

  private final TheMovieDatabaseHttpService theMovieDatabaseHttpService;

  @Getter private final ExternalAgentStrategy agentStrategy = ExternalAgentStrategy.TMDB;

  private static final Map<ExternalSourceType, String> TMDB_EXTERNAL_SOURCES =
      Map.of(ExternalSourceType.IMDB, "imdb_id", ExternalSourceType.TVDB, "tvdb_id");

  public Optional<RemoteSearchResult> search(VideoFileParserResult videoInformation) {
    var findResult = searchByExternalId(videoInformation);
    if (findResult.isPresent()) {
      return findResult;
    }

    return searchByText(videoInformation);
  }

  private Optional<RemoteSearchResult> searchByExternalId(VideoFileParserResult videoInformation) {
    if (StringUtils.isBlank(videoInformation.externalId())
        || videoInformation.externalSource() == null) {
      return Optional.empty();
    }

    if (videoInformation.externalSource() == ExternalSourceType.TMDB) {
      return searchByDirectTmdbId(videoInformation);
    }

    var tmdbSource = TMDB_EXTERNAL_SOURCES.get(videoInformation.externalSource());
    if (tmdbSource == null) {
      return Optional.empty();
    }

    try {
      var findResults =
          theMovieDatabaseHttpService.findByExternalId(videoInformation.externalId(), tmdbSource);

      var movieResults = findResults.getMovieResults();
      if (movieResults != null && !movieResults.isEmpty()) {
        var tmdbResult = movieResults.getFirst();
        return Optional.of(
            RemoteSearchResult.builder()
                .externalSourceType(ExternalSourceType.TMDB)
                .externalId(String.valueOf(tmdbResult.getId()))
                .title(tmdbResult.getTitle())
                .build());
      }
    } catch (IOException | JacksonException ex) {
      log.warn(
          "TMDB /find failed for external ID '{}', falling back to text search",
          videoInformation.externalId(),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("TMDB /find interrupted for external ID '{}'", videoInformation.externalId(), ex);
      return Optional.empty();
    }

    return Optional.empty();
  }

  private Optional<RemoteSearchResult> searchByDirectTmdbId(
      VideoFileParserResult videoInformation) {
    try {
      var tmdbMovie =
          theMovieDatabaseHttpService.getMovieMetadata(videoInformation.externalId());

      return Optional.of(
          RemoteSearchResult.builder()
              .externalSourceType(ExternalSourceType.TMDB)
              .externalId(String.valueOf(tmdbMovie.getId()))
              .title(tmdbMovie.getTitle())
              .build());
    } catch (IOException | JacksonException ex) {
      log.warn(
          "TMDB direct lookup failed for ID '{}', falling back to text search",
          videoInformation.externalId(),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("TMDB direct lookup interrupted for ID '{}'", videoInformation.externalId(), ex);
      return Optional.empty();
    }

    return Optional.empty();
  }

  private Optional<RemoteSearchResult> searchByText(VideoFileParserResult videoInformation) {
    try {
      var searchResult = theMovieDatabaseHttpService.searchForMovie(videoInformation);

      if (searchResult.getResults().isEmpty()) {
        return Optional.empty();
      }

      var tmdbResult = searchResult.getResults().getFirst();

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

  public Optional<MetadataResult<Movie>> getMetadata(
      RemoteSearchResult remoteSearchResult, Library library) {
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
              .runtime(tmdbMovie.getRuntime())
              .originalTitle(tmdbMovie.getOriginalTitle())
              .titleSort(TitleSortUtil.computeTitleSort(tmdbMovie.getTitle()))
              .studios(TmdbMetadataMapper.mapCompanies(productionCompanies))
              .cast(TmdbMetadataMapper.mapCast(castList))
              .directors(TmdbMetadataMapper.mapDirectors(crewList))
              .genres(TmdbMetadataMapper.mapGenres(tmdbGenres));

      if (StringUtils.isNotBlank(tmdbMovie.getReleaseDate())) {
        movieBuilder.releaseDate(LocalDate.parse(tmdbMovie.getReleaseDate()));
      }

      movieRating.ifPresent(
          movieRelease ->
              movieBuilder.contentRating(
                  new ContentRating("MPAA", movieRelease.getCertification(), "US")));

      var imageSources =
          TmdbMetadataMapper.buildPosterAndBackdropSources(
              tmdbMovie.getPosterPath(), tmdbMovie.getBackdropPath());
      var personImageSources = TmdbMetadataMapper.buildPersonImageSources(castList, crewList);
      var companyImageSources = TmdbMetadataMapper.buildCompanyImageSources(productionCompanies);

      return Optional.of(
          new MetadataResult<>(
              movieBuilder.build(), imageSources, personImageSources, companyImageSources));

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
