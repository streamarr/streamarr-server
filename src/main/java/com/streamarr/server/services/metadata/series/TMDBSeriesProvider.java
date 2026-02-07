package com.streamarr.server.services.metadata.series;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.metadata.tmdb.TmdbContentRatings;
import com.streamarr.server.services.metadata.tmdb.TmdbCredits;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeries;
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
public class TMDBSeriesProvider implements SeriesMetadataProvider {

  private final TheMovieDatabaseHttpService theMovieDatabaseHttpService;

  @Getter private final ExternalAgentStrategy agentStrategy = ExternalAgentStrategy.TMDB;

  public Optional<RemoteSearchResult> search(VideoFileParserResult videoInformation) {
    try {
      var searchResult = theMovieDatabaseHttpService.searchForTvSeries(videoInformation);

      if (searchResult.getResults().isEmpty()) {
        return Optional.empty();
      }

      var tmdbResult = searchResult.getResults().get(0);

      return Optional.of(
          RemoteSearchResult.builder()
              .externalSourceType(ExternalSourceType.TMDB)
              .externalId(String.valueOf(tmdbResult.getId()))
              .title(tmdbResult.getName())
              .build());

    } catch (IOException ex) {
      log.error("Failure requesting TV search results:", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("TV search interrupted:", ex);
    }

    return Optional.empty();
  }

  public Optional<Series> getMetadata(RemoteSearchResult remoteSearchResult, Library library) {
    try {
      var tmdbSeries =
          theMovieDatabaseHttpService.getTvSeriesMetadata(remoteSearchResult.externalId());

      var credits = Optional.ofNullable(tmdbSeries.getCredits());
      var castList = credits.map(TmdbCredits::getCast).orElse(Collections.emptyList());
      var crewList = credits.map(TmdbCredits::getCrew).orElse(Collections.emptyList());
      var contentRatingsResult = Optional.ofNullable(tmdbSeries.getContentRatings());
      var productionCompanies =
          Optional.ofNullable(tmdbSeries.getProductionCompanies()).orElse(Collections.emptyList());
      var tmdbGenres = Optional.ofNullable(tmdbSeries.getGenres()).orElse(Collections.emptyList());

      var tvRating =
          contentRatingsResult
              .map(TmdbContentRatings::getResults)
              .orElse(Collections.emptyList())
              .stream()
              .filter(r -> "US".equals(r.getIso31661()))
              .filter(r -> StringUtils.isNotBlank(r.getRating()))
              .findFirst();

      var runtime = computeRuntime(tmdbSeries);

      var seriesBuilder =
          Series.builder()
              .library(library)
              .title(tmdbSeries.getName())
              .originalTitle(tmdbSeries.getOriginalName())
              .titleSort(TitleSortUtil.computeTitleSort(tmdbSeries.getName()))
              .externalIds(mapExternalIds(tmdbSeries))
              .tagline(tmdbSeries.getTagline())
              .summary(tmdbSeries.getOverview())
              .backdropPath(tmdbSeries.getBackdropPath())
              .posterPath(tmdbSeries.getPosterPath())
              .runtime(runtime)
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

      if (StringUtils.isNotBlank(tmdbSeries.getFirstAirDate())) {
        seriesBuilder.firstAirDate(LocalDate.parse(tmdbSeries.getFirstAirDate()));
      }

      tvRating.ifPresent(
          rating ->
              seriesBuilder.contentRating(
                  new ContentRating("TV Parental Guidelines", rating.getRating(), "US")));

      return Optional.of(seriesBuilder.build());

    } catch (IOException ex) {
      log.error(
          "Failure enriching series metadata using TMDB id '{}'",
          remoteSearchResult.externalId(),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error(
          "Series metadata enrichment interrupted for TMDB id '{}'",
          remoteSearchResult.externalId(),
          ex);
    }

    return Optional.empty();
  }

  public Optional<SeasonDetails> getSeasonDetails(String seriesExternalId, int seasonNumber) {
    try {
      var tmdbSeason =
          theMovieDatabaseHttpService.getTvSeasonDetails(seriesExternalId, seasonNumber);

      var episodes =
          Optional.ofNullable(tmdbSeason.getEpisodes()).orElse(Collections.emptyList()).stream()
              .map(this::mapEpisodeDetails)
              .toList();

      var seasonBuilder =
          SeasonDetails.builder()
              .name(tmdbSeason.getName())
              .seasonNumber(tmdbSeason.getSeasonNumber())
              .overview(tmdbSeason.getOverview())
              .posterPath(tmdbSeason.getPosterPath())
              .episodes(episodes);

      if (StringUtils.isNotBlank(tmdbSeason.getAirDate())) {
        seasonBuilder.airDate(LocalDate.parse(tmdbSeason.getAirDate()));
      }

      return Optional.of(seasonBuilder.build());

    } catch (IOException ex) {
      log.error(
          "Failure fetching season {} details for series TMDB id '{}'",
          seasonNumber,
          seriesExternalId,
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error(
          "Season details fetch interrupted for series TMDB id '{}' season {}",
          seriesExternalId,
          seasonNumber,
          ex);
    }

    return Optional.empty();
  }

  private SeasonDetails.EpisodeDetails mapEpisodeDetails(
      com.streamarr.server.services.metadata.tmdb.TmdbTvEpisode ep) {
    var builder =
        SeasonDetails.EpisodeDetails.builder()
            .episodeNumber(ep.getEpisodeNumber())
            .name(ep.getName())
            .overview(ep.getOverview())
            .stillPath(ep.getStillPath())
            .runtime(ep.getRuntime());

    if (StringUtils.isNotBlank(ep.getAirDate())) {
      builder.airDate(LocalDate.parse(ep.getAirDate()));
    }

    return builder.build();
  }

  private Integer computeRuntime(TmdbTvSeries tmdbSeries) {
    var episodeRunTime = tmdbSeries.getEpisodeRunTime();

    if (episodeRunTime == null || episodeRunTime.isEmpty()) {
      return null;
    }

    return (int) episodeRunTime.stream().mapToInt(Integer::intValue).average().orElse(0);
  }

  private Set<ExternalIdentifier> mapExternalIds(TmdbTvSeries tmdbSeries) {
    var externalIdSet = new HashSet<ExternalIdentifier>();

    externalIdSet.add(
        ExternalIdentifier.builder()
            .externalSourceType(ExternalSourceType.TMDB)
            .externalId(String.valueOf(tmdbSeries.getId()))
            .build());

    var externalIds = tmdbSeries.getExternalIds();
    if (externalIds != null && StringUtils.isNotBlank(externalIds.getImdbId())) {
      externalIdSet.add(
          ExternalIdentifier.builder()
              .externalSourceType(ExternalSourceType.IMDB)
              .externalId(externalIds.getImdbId())
              .build());
    }

    return externalIdSet;
  }
}
