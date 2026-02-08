package com.streamarr.server.services.metadata.series;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.tmdb.TmdbContentRatings;
import com.streamarr.server.services.metadata.tmdb.TmdbCredit;
import com.streamarr.server.services.metadata.tmdb.TmdbCredits;
import com.streamarr.server.services.metadata.tmdb.TmdbProductionCompany;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeries;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.utils.TitleSortUtil;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

  public Optional<MetadataResult<Series>> getMetadata(
      RemoteSearchResult remoteSearchResult, Library library) {
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
              .runtime(runtime)
              .studios(
                  productionCompanies.stream()
                      .map(
                          c ->
                              Company.builder()
                                  .sourceId(String.valueOf(c.getId()))
                                  .name(c.getName())
                                  .build())
                      .collect(Collectors.toSet()))
              .cast(
                  castList.stream()
                      .map(
                          credit ->
                              Person.builder()
                                  .sourceId(String.valueOf(credit.getId()))
                                  .name(credit.getName())
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

      var imageSources = buildImageSources(tmdbSeries);
      var personImageSources = buildPersonImageSources(castList, crewList);
      var companyImageSources = buildCompanyImageSources(productionCompanies);

      return Optional.of(
          new MetadataResult<>(
              seriesBuilder.build(), imageSources, personImageSources, companyImageSources));

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

      var seasonImageSources = buildSeasonImageSources(tmdbSeason.getPosterPath());

      var seasonBuilder =
          SeasonDetails.builder()
              .name(tmdbSeason.getName())
              .seasonNumber(tmdbSeason.getSeasonNumber())
              .overview(tmdbSeason.getOverview())
              .imageSources(seasonImageSources)
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
            .imageSources(buildEpisodeImageSources(ep.getStillPath()))
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

  private List<ImageSource> buildImageSources(TmdbTvSeries tmdbSeries) {
    var sources = new ArrayList<ImageSource>();

    if (StringUtils.isNotBlank(tmdbSeries.getPosterPath())) {
      sources.add(new TmdbImageSource(ImageType.POSTER, tmdbSeries.getPosterPath()));
    }
    if (StringUtils.isNotBlank(tmdbSeries.getBackdropPath())) {
      sources.add(new TmdbImageSource(ImageType.BACKDROP, tmdbSeries.getBackdropPath()));
    }

    return sources;
  }

  private List<ImageSource> buildSeasonImageSources(String posterPath) {
    if (StringUtils.isNotBlank(posterPath)) {
      return List.of(new TmdbImageSource(ImageType.POSTER, posterPath));
    }
    return List.of();
  }

  private List<ImageSource> buildEpisodeImageSources(String stillPath) {
    if (StringUtils.isNotBlank(stillPath)) {
      return List.of(new TmdbImageSource(ImageType.STILL, stillPath));
    }
    return List.of();
  }

  private Map<String, List<ImageSource>> buildPersonImageSources(
      List<TmdbCredit> castList, List<TmdbCredit> crewList) {
    var sources = new HashMap<String, List<ImageSource>>();

    for (var credit : castList) {
      addPersonImageSource(sources, credit);
    }
    for (var crew : crewList) {
      if ("Director".equals(crew.getJob())) {
        addPersonImageSource(sources, crew);
      }
    }

    return sources;
  }

  private void addPersonImageSource(Map<String, List<ImageSource>> sources, TmdbCredit credit) {
    if (StringUtils.isNotBlank(credit.getProfilePath())) {
      sources
          .computeIfAbsent(String.valueOf(credit.getId()), k -> new ArrayList<>())
          .add(new TmdbImageSource(ImageType.PROFILE, credit.getProfilePath()));
    }
  }

  private Map<String, List<ImageSource>> buildCompanyImageSources(
      List<TmdbProductionCompany> companies) {
    var sources = new HashMap<String, List<ImageSource>>();

    for (var company : companies) {
      if (StringUtils.isNotBlank(company.getLogoPath())) {
        sources
            .computeIfAbsent(String.valueOf(company.getId()), k -> new ArrayList<>())
            .add(new TmdbImageSource(ImageType.LOGO, company.getLogoPath()));
      }
    }

    return sources;
  }
}
